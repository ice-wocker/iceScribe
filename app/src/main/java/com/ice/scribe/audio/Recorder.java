package com.ice.scribe.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 麦克风录音：采集 → 混单声道 → 重采样 16k → 直接落 WAV 文件。
 *
 * <p>边录边写盘，不在内存里堆整段音频，所以录多久都行，内存占用是常数级。
 */
public final class Recorder {

    private static final String TAG = "IceRecorder";

    public static final int TARGET_RATE = 16000;

    /** 采集块大小（源采样率下的样本数）。 */
    private static final int CHUNK = 2048;

    public interface Listener {
        /** 音量电平，0~1，用于让麦克风按钮随声音呼吸。 */
        void onLevel(float level);

        /** 已录时长（毫秒）。 */
        void onElapsed(long ms);

        void onError(String message);
    }

    private final Context ctx;
    private final Listener listener;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile long frames;         // 已写入的 16k 帧数
    private int srcRate;
    private File wav;
    private String failure;

    public Recorder(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRecording() {
        return running;
    }

    public File file() {
        return wav;
    }

    public long elapsedMs() {
        return frames * 1000L / TARGET_RATE;
    }

    public int sourceRate() {
        return srcRate;
    }

    /** 开始录音；失败返回 false，原因见 {@link #errorMessage()}。 */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running) return false;
        failure = null;
        frames = 0;

        record = openRecord();
        if (record == null) {
            failure = "无法打开麦克风（可能被其它应用占用）";
            return false;
        }
        try {
            wav = newFile();
        } catch (IOException e) {
            record.release();
            record = null;
            failure = "无法创建录音文件";
            return false;
        }

        running = true;
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            running = false;
            record.release();
            record = null;
            failure = "麦克风启动失败";
            return false;
        }

        thread = new Thread(this::loop, "ice-record");
        thread.start();
        Log.i(TAG, "开始录音 @" + srcRate + "Hz → 16kHz");
        return true;
    }

    /** 停止并收尾，返回录音文件；没有有效内容时返回 null。 */
    public File stop() {
        if (!running && thread == null) return wav;
        running = false;
        try {
            if (thread != null) thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        releaseRecord();
        if (wav != null && wav.length() <= 44) {    // 只有文件头，等于没录到
            wav.delete();
            wav = null;
        }
        return wav;
    }

    public String errorMessage() {
        return failure;
    }

    private void releaseRecord() {
        AudioRecord r = record;
        record = null;
        if (r == null) return;
        try {
            if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop();
        } catch (IllegalStateException ignore) {
            // 已经停了
        }
        r.release();
    }

    private void loop() {
        final boolean needResample = srcRate != TARGET_RATE;
        final Resampler resampler = needResample ? new Resampler(srcRate, TARGET_RATE) : null;
        final short[] in = new short[CHUNK];
        final short[] out = needResample
                ? new short[Resampler.outCapacity(CHUNK, srcRate, TARGET_RATE)]
                : in;

        WavFile.Writer writer = null;
        try {
            writer = new WavFile.Writer(wav, TARGET_RATE);
        } catch (IOException e) {
            failure = "录音文件写入失败";
            running = false;
            releaseRecord();
            listener.onError(failure);
            return;
        }

        long lastTick = 0;
        try {
            while (running) {
                int n = record.read(in, 0, in.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        failure = "录音读取失败";
                        break;
                    }
                    continue;
                }
                int outLen = needResample ? resampler.process(in, n, out) : n;
                if (outLen <= 0) continue;
                writer.write(out, outLen);
                frames += outLen;

                float level = peak(out, outLen);
                listener.onLevel(level);

                long now = System.currentTimeMillis();
                if (now - lastTick >= 200) {
                    lastTick = now;
                    listener.onElapsed(elapsedMs());
                }
            }
        } catch (IOException e) {
            failure = "录音文件写入失败：" + e.getMessage();
            Log.e(TAG, "写入失败", e);
        } catch (Throwable t) {
            failure = "录音中断：" + t.getClass().getSimpleName();
            Log.e(TAG, "录音异常", t);
        } finally {
            try {
                writer.close();
            } catch (IOException ignore) {
                // 已经尽力了
            }
            running = false;
            releaseRecord();
            listener.onElapsed(elapsedMs());
        }
    }

    private static float peak(short[] buf, int len) {
        int max = 0;
        for (int i = 0; i < len; i++) {
            int v = Math.abs(buf[i]);
            if (v > max) max = v;
        }
        // 平方根压缩一下，小声说话时也能看到明显变化
        return (float) Math.sqrt(max / 32768.0);
    }

    /** 优先 16k 直采；设备不支持就退回 48k/44.1k 再重采样。 */
    private AudioRecord openRecord() {
        int[] rates = {TARGET_RATE, 48000, 44100};
        for (int source : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC}) {
            for (int rate : rates) {
                AudioRecord r = tryOpen(source, rate);
                if (r != null) {
                    srcRate = rate;
                    return r;
                }
            }
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private AudioRecord tryOpen(int source, int rate) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        int bufSize = Math.max(min, CHUNK * 2 * 4);
        AudioRecord r = null;
        try {
            r = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        return r;
    }

    private File newFile() throws IOException {
        File dir = new File(ctx.getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, "rec_" + stamp + ".wav");
    }

    /** 清理没人要的录音文件（转写后用户没保存音频时调用）。 */
    public static void cleanup(Context ctx, File keep) {
        File dir = new File(ctx.getFilesDir(), "recordings");
        File[] files = dir.listFiles();
        if (files == null) return;
        // 保留最近的 20 个，其余删掉，避免无声无息占满空间
        List<File> all = new ArrayList<>();
        for (File f : files) if (f.isFile()) all.add(f);
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 20; i < all.size(); i++) {
            if (keep != null && all.get(i).equals(keep)) continue;
            all.get(i).delete();
        }
    }
}