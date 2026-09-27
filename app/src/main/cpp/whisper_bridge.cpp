// iceScribe —— whisper.cpp 的 JNI 桥接
// 提供：加载 whisper 模型、把 16kHz 单声道 PCM 整段转写成文本、取消、释放。
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "IceScribeWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::string toStd(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

struct Session {
    whisper_context  *ctx = nullptr;
    std::mutex        mu;
    /// 让 whisper 在窗口之间检查一次，从而支持「中途取消」
    std::atomic<bool> cancel{false};
};

}  // namespace

extern "C" {

/** whisper.cpp 的版本号，界面上用来核对引擎版本。 */
JNIEXPORT jstring JNICALL
Java_com_ice_scribe_stt_WhisperBridge_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_version());
}

/** 加载模型，返回会话句柄；失败返回 0（上层据此提示「模型文件不可用」）。 */
JNIEXPORT jlong JNICALL
Java_com_ice_scribe_stt_WhisperBridge_nativeLoad(JNIEnv *env, jclass, jstring jPath) {
    const std::string path = toStd(env, jPath);
    if (path.empty()) {
        LOGE("模型路径为空");
        return 0;
    }

    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;                 // 手机上是纯 CPU 推理
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (ctx == nullptr) {
        LOGE("模型加载失败: %s", path.c_str());
        return 0;
    }

    auto *s = new Session();
    s->ctx = ctx;
    LOGI("模型已加载: %s (whisper %s)", path.c_str(), whisper_version());
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_ice_scribe_stt_WhisperBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    s->cancel = true;
    std::lock_guard<std::mutex> lock(s->mu);
    if (s->ctx != nullptr) whisper_free(s->ctx);
    s->ctx = nullptr;
    delete s;
}

JNIEXPORT void JNICALL
Java_com_ice_scribe_stt_WhisperBridge_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel = true;
}

/**
 * 把整段 PCM（16kHz、单声道、float 归一化到 [-1,1]）转写成文本。
 *
 * @param lang      "zh" / "en" / "" 表示自动检测
 * @param translate true 时把结果翻译成英文
 * @param prompt    可选的首段提示词，能给中文加上标点、影响用词风格
 * @return 按段落拼好的纯文本；失败返回空串
 */
JNIEXPORT jstring JNICALL
Java_com_ice_scribe_stt_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray jSamples, jstring jLang,
        jint nThreads, jboolean translate, jstring jPrompt) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(jSamples);
    if (n <= 0) return env->NewStringUTF("");
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jSamples, 0, n, pcm.data());

    const std::string lang   = toStd(env, jLang);
    const std::string prompt = toStd(env, jPrompt);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads        = nThreads > 0 ? nThreads : 4;
    wp.translate        = translate == JNI_TRUE;
    wp.language         = lang.empty() ? "auto" : lang.c_str();
    wp.print_progress   = false;
    wp.print_realtime   = false;
    wp.print_timestamps = false;
    wp.no_context       = true;         // 一次性整段转写，不让上一段影响下一段
    wp.single_segment   = false;
    wp.token_timestamps = false;
    if (!prompt.empty()) wp.initial_prompt = prompt.c_str();

    // 允许在窗口之间被打断，这样「取消」才真的能停下来
    wp.abort_callback           = [](void *ud) -> bool {
        return static_cast<Session *>(ud)->cancel.load();
    };
    wp.abort_callback_user_data = s;

    s->cancel = false;
    std::lock_guard<std::mutex> lock(s->mu);

    if (whisper_full(s->ctx, wp, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("whisper_full 执行失败");
        return env->NewStringUTF("");
    }

    const int nSeg = whisper_full_n_segments(s->ctx);
    std::string out;
    for (int i = 0; i < nSeg; ++i) {
        const char *t = whisper_full_get_segment_text(s->ctx, i);
        if (t == nullptr) continue;
        std::string seg(t);
        // whisper 的段落常以空格开头；段间用换行，读起来像自然段落
        size_t at = seg.find_first_not_of(" \t\n");
        if (at == std::string::npos) continue;
        seg = seg.substr(at);
        if (seg.empty()) continue;
        if (!out.empty()) out += "\n";
        out += seg;
    }
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"