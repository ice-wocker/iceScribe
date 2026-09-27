package com.ice.scribe.stt;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ice.scribe.audio.WavFile;
import com.ice.scribe.audio.WavReader;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 转写引擎：管理 whisper 模型的加载 / 卸载，并在后台线程执行转写。
 *
 * <p>转写按 {@value #CHUNK_SECONDS} 秒一段从 WAV 里流式读取，所以
 * 一小时的录音也能转，内存占用只跟单段有关，同时还能给出百分比进度。
 * 每段会把上一段的结尾文字作为提示词喂进去，跨段语气和用词更连贯。
 */
public final class TranscribeEngine {

    private static final String TAG = "TranscribeEngine";

    /** 单段时长；whisper 内部本来就是 30 秒窗口滑动，分段只影响内存与进度粒度。 */
    private static final int CHUNK_SECONDS = 240;

    /** 中文场景给个带标点的提示词，whisper 会更愿意输出标点和断句。 */
    private static final String PROMPT_ZH = "以下是普通话的句子，请加上标点符号。";
    private static final String PROMPT_EN = "The following is an English sentence, please add punctuation.";

    /** 跨段提示词最多带多少字符，太多会挤掉 whisper 的 224 token 预算。 */
    private static final int PROMPT_TAIL_CHARS = 90;

    public interface LoadCallback {
        void onLoaded(String modelName);

        void onFailed(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ice-transcribe");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile long handle = 0L;
    private volatile String modelPath;
    private volatile String modelName = "";
    private volatile boolean busy;

    public boolean isReady() {
        return handle != 0L;
    }

    public boolean isBusy() {
        return busy;
    }

    public String modelName() {
        return modelName;
    }

    public String modelPath() {
        return modelPath;
    }

    /** 同步加载（必须在后台线程调用）；失败时抛异常，信息可直接展示给用户。 */
    public void loadBlocking(String path) throws Exception {
        unload();
        File f = new File(path);
        if (!f.isFile()) throw new IllegalStateException("模型文件不存在");
        if (!f.canRead()) throw new IllegalStateException("模型文件不可读，请重新导入");

        long h = WhisperBridge.nativeLoad(path);
        if (h == 0L) {
            throw new IllegalStateException("模型加载失败，文件可能不完整或不是 whisper 模型");
        }
        handle = h;
        modelPath = path;
        modelName = displayNameOf(f.getName());
        Log.i(TAG, "模型就绪: " + modelName);
    }

    /** 异步加载，回调在主线程。 */
    public void load(String path, LoadCallback cb) {
        worker.execute(() -> {
            try {
                loadBlocking(path);
                main.post(() -> cb.onLoaded(modelName));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "模型加载失败" : e.getMessage();
                Log.e(TAG, "加载失败: " + msg, e);
                main.post(() -> cb.onFailed(msg));
            }
        });
    }

    /** 释放模型占用的内存。 */
    public void unload() {
        long h = handle;
        handle = 0L;
        modelPath = null;
        modelName = "";
        if (h != 0L) {
            WhisperBridge.nativeFree(h);
            Log.i(TAG, "模型已释放");
        }
    }

    /** 让正在跑的转写尽快停下。 */
    public void cancel() {
        cancelled.set(true);
        long h = handle;
        if (h != 0L) WhisperBridge.nativeCancel(h);
    }

    /**
     * 转写一个 16kHz 单声道 WAV 文件。
     *
     * @param wav 录音或导入后转换出来的 16k WAV
     */
    public void transcribe(File wav, String lang, int threads, boolean translate,
                           TranscribeCallback cb) {
        if (!isReady()) {
            cb.onError("还没有可用的模型");
            return;
        }
        if (wav == null || !wav.isFile()) {
            cb.onError("音频文件不存在");
            return;
        }
        if (busy) {
            cb.onError("上一段还在转写中");
            return;
        }
        busy = true;
        cancelled.set(false);
        final long h = handle;
        final String langCode = lang == null ? "auto" : lang;

        worker.execute(() -> {
            long t0 = System.currentTimeMillis();
            StringBuilder all = new StringBuilder();
            String failure = null;
            try (WavReader reader = new WavReader(wav)) {
                if (reader.sampleRate != WavFile.RATE_16K) {
                    throw new IOException("音频采样率不是 16kHz（" + reader.sampleRate + "）");
                }
                final long totalFrames = Math.max(1, reader.totalFrames());
                final int chunkFrames = CHUNK_SECONDS * WavFile.RATE_16K;
                final float[] chunk = new float[chunkFrames];

                int index = 0;
                int got;
                while ((got = reader.read(chunk)) > 0) {
                    if (cancelled.get()) break;
                    index++;

                    float[] slice = got == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, got);
                    String prompt = promptFor(all, langCode, translate);
                    post(cb, "正在转写第 " + index + " 段…",
                            (int) Math.min(99, reader.framesRead() * 100 / totalFrames));

                    String part = WhisperBridge.nativeTranscribe(h, slice, langCode, threads,
                            translate, prompt);
                    if (cancelled.get()) break;
                    if (part != null) {
                        part = part.trim();
                        if (!part.isEmpty()) {
                            if (all.length() > 0) all.append('\n');
                            all.append(part);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "转写失败", t);
                failure = t.getMessage() == null ? "转写出错" : t.getMessage();
            }

            final long cost = System.currentTimeMillis() - t0;
            final String text = all.toString().trim();
            final String fail = failure;
            final boolean wasCancelled = cancelled.get();
            busy = false;

            main.post(() -> {
                if (wasCancelled) {
                    cb.onError("已取消");
                } else if (fail != null) {
                    cb.onError(fail);
                } else {
                    cb.onDone(text, cost);
                }
            });
        });
    }

    /** 第一段给标点提示，之后带上一段的结尾，让断句和用词接得上。 */
    private static String promptFor(StringBuilder soFar, String lang, boolean translate) {
        if (soFar.length() == 0) {
            return "en".equals(lang) || translate ? PROMPT_EN : PROMPT_ZH;
        }
        String s = soFar.toString();
        if (s.length() > PROMPT_TAIL_CHARS) s = s.substring(s.length() - PROMPT_TAIL_CHARS);
        return s.replace('\n', ' ');
    }

    private void post(TranscribeCallback cb, String stage, int percent) {
        main.post(() -> cb.onProgress(stage, percent));
    }

    public void shutdown() {
        cancel();
        worker.execute(this::unload);
        worker.shutdown();
    }

    /** "ggml-medium-q5_0.bin" → "medium · q5_0"，列表里更好认。 */
    public static String displayNameOf(String fileName) {
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        for (String p : new String[]{"ggml-", "whisper-", "whisper_"}) {
            if (n.regionMatches(true, 0, p, 0, p.length())) {
                n = n.substring(p.length());
                break;
            }
        }
        // 量化档位用间隔号分开一眼能看出来，但量化名本身（q5_0）保持原样
        return n.replace("-q", " · q");
    }

    /** whisper.cpp 引擎版本。 */
    public static String engineVersion() {
        try {
            return WhisperBridge.nativeVersion();
        } catch (Throwable t) {
            return "未知";
        }
    }
}