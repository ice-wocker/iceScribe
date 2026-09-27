package com.ice.scribe.stt;

/**
 * whisper.cpp 的 JNI 入口。
 *
 * <p>全部是静态方法，句柄由 native 层维护；调用方只负责「加载 → 转写 → 释放」。
 * 转写是同步阻塞的，必须放到后台线程执行。
 */
public final class WhisperBridge {

    static {
        System.loadLibrary("ice_scribe");
    }

    private WhisperBridge() {
    }

    /** whisper.cpp 版本号，例如 "1.9.4"。 */
    public static native String nativeVersion();

    /**
     * 加载模型文件。
     *
     * @param path .bin / .gguf 模型在磁盘上的绝对路径
     * @return 会话句柄；失败返回 0
     */
    public static native long nativeLoad(String path);

    /** 释放句柄与其占用的内存；重复调用安全。 */
    public static native void nativeFree(long handle);

    /** 让正在进行的转写尽快停下来。 */
    public static native void nativeCancel(long handle);

    /**
     * 把整段音频转写成文字。
     *
     * @param handle    由 {@link #nativeLoad} 返回
     * @param samples   16kHz 单声道浮点采样，取值 [-1, 1]
     * @param lang      "zh" / "en" / ""（自动检测）
     * @param threads   解码线程数
     * @param translate true 表示把结果翻成英文
     * @param prompt    可选提示词，能给中文补上标点
     * @return 转写文本；失败返回空串
     */
    public static native String nativeTranscribe(long handle, float[] samples, String lang,
                                                 int threads, boolean translate, String prompt);
}