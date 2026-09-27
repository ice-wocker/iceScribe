package com.ice.scribe.stt;

/** 转写的进度与结果回调，全部在主线程触发，可以直接更新界面。 */
public interface TranscribeCallback {

    /**
     * 阶段性提示。
     *
     * @param stage   例如「正在加载模型」「正在转写第 2 段…」
     * @param percent 0~100，未知时给 0
     */
    void onProgress(String stage, int percent);

    /**
     * 转写成功。
     *
     * @param text      识别出的文字（可能为空串，表示这段音频没人声）
     * @param elapsedMs 耗时毫秒
     */
    void onDone(String text, long elapsedMs);

    /** 转写失败，message 已经是可读的中文原因。 */
    void onError(String message);
}