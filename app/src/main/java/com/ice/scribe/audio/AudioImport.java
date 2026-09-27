package com.ice.scribe.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 把任意能解码的音频（m4a / mp3 / wav / ogg / aac…）转成 16kHz 单声道 WAV。
 *
 * <p>用系统自带的解码器，边解码边重采样边写盘，不把整段音频读进内存。
 */
public final class AudioImport {

    private static final String TAG = "AudioImport";

    public interface Progress {
        /** 0~100；拿不到总时长时回调 -1。 */
        void onPercent(int percent);
    }

    private AudioImport() {
    }

    /**
     * @return 生成的 16k WAV（等于 out 参数）
     */
    public static File to16kWav(Context ctx, Uri uri, File out, Progress progress)
            throws IOException {
        try {
            decode(ctx, uri, out, progress);
        } catch (Exception e) {
            Log.w(TAG, "系统解码失败，尝试按 WAV 直接解析", e);
            out.delete();
            if (!isRiff(uri, ctx)) {
                throw e instanceof IOException ? (IOException) e
                        : new IOException("这个音频格式无法解码：" + e.getClass().getSimpleName());
            }
            fromWav(ctx, uri, out, progress);
        }
        if (!out.isFile() || out.length() <= 44) {
            out.delete();
            throw new IOException("没有解析到音频内容");
        }
        return out;
    }

    // ---- 通用解码路径 ----

    private static void decode(Context ctx, Uri uri, File out, Progress progress) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        WavFile.Writer writer = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("文件里没有音频轨道");
            ex.selectTrack(track);

            long totalUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : -1;

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            writer = new WavFile.Writer(out, WavFile.RATE_16K);

            Resampler resampler = null;
            short[] scratch = null;
            short[] converted = null;
            int outRate = 0, outChannels = 1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    outChannels = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                    resampler = new Resampler(outRate, WavFile.RATE_16K);
                    continue;
                }
                if (outIdx < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                boolean hasData = info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (hasData) {
                    ByteBuffer buf = codec.getOutputBuffer(outIdx);
                    if (buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        MediaFormat of = codec.getOutputFormat();
                        int encoding = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                : AudioFormat.ENCODING_PCM_16BIT;
                        final boolean floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT;
                        final int ch = Math.max(1, outChannels);
                        int frames = info.size / (floatPcm ? 4 : 2) / ch;
                        if (frames <= 0) {
                            codec.releaseOutputBuffer(outIdx, false);
                            continue;
                        }

                        if (scratch == null || scratch.length < frames + 64) {
                            scratch = new short[frames + 4096];
                            converted = new short[resampler == null ? scratch.length
                                    : Resampler.outCapacity(scratch.length,
                                    Math.max(1, outRate), WavFile.RATE_16K) + 4096];
                        }

                        if (floatPcm) {
                            FloatBuffer fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                            for (int i = 0; i < frames; i++) {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += fb.get(i * ch + c);
                                scratch[i] = clamp(sum / ch * 32768f);
                            }
                        } else {
                            ShortBuffer sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            sb.get(scratch, 0, Math.min(frames * ch, sb.remaining()));
                            if (ch > 1) {
                                for (int i = 0; i < frames; i++) {
                                    int sum = 0;
                                    for (int c = 0; c < ch; c++) sum += scratch[i * ch + c];
                                    scratch[i] = (short) (sum / ch);
                                }
                            }
                        }

                        int n = resampler == null ? frames
                                : resampler.process(scratch, frames, converted);
                        if (n > 0) writer.write(resampler == null ? scratch : converted, n);
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);

                if (progress != null && totalUs > 0) {
                    long t = ex.getSampleTime();
                    if (t > 0) {
                        int pct = (int) Math.min(100, t * 100 / totalUs);
                        progress.onPercent(pct);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解码失败", e);
            throw e instanceof IOException ? (IOException) e
                    : new IOException("音频解码失败：" + e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {
                    // 尽力而为
                }
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {
                    // 已经停了
                }
                codec.release();
            }
            ex.release();
        }
    }

    // ---- WAV 兜底路径 ----

    /** 系统解码器不支持（少见）时，直接按 WAV 解析并重采样。 */
    private static void fromWav(Context ctx, Uri uri, File out, Progress progress) throws IOException {
        File tmp = File.createTempFile("import", ".wav", ctx.getCacheDir());
        try {
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new IOException("无法读取文件");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            try (WavReader reader = new WavReader(tmp);
                 WavFile.Writer writer = new WavFile.Writer(out, WavFile.RATE_16K)) {
                Resampler rs = reader.sampleRate == WavFile.RATE_16K
                        ? null : new Resampler(reader.sampleRate, WavFile.RATE_16K);
                float[] chunk = new float[WavFile.RATE_16K];
                short[] src = new short[chunk.length];
                short[] dst = new short[Resampler.outCapacity(chunk.length,
                        reader.sampleRate, WavFile.RATE_16K)];
                long total = Math.max(1, reader.totalFrames());
                int got;
                while ((got = reader.read(chunk)) > 0) {
                    for (int i = 0; i < got; i++) {
                        src[i] = (short) Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, chunk[i] * 32768f));
                    }
                    int n = rs == null ? got : rs.process(src, got, dst);
                    if (n > 0) writer.write(rs == null ? src : dst, n);
                    if (progress != null) {
                        progress.onPercent((int) Math.min(100, reader.framesRead() * 100 / total));
                    }
                }
            }
        } finally {
            tmp.delete();
        }
    }

    private static boolean isRiff(Uri uri, Context ctx) {
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] h = new byte[12];
            int n = 0;
            while (n < 12) {
                int r = in.read(h, n, 12 - n);
                if (r <= 0) break;
                n += r;
            }
            return n == 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private static short clamp(float v) {
        int i = Math.round(v);
        if (i > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (i < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) i;
    }
}