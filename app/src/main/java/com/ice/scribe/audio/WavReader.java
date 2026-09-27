package com.ice.scribe.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 读 16-bit PCM WAV。
 *
 * <p>只服务两类文件：本应用录制的（16k 单声道）和导入时转出来的（16k 单声道）。
 * 输出统一混成单声道，并按块吐出，避免整段音频常驻内存。
 */
public final class WavReader implements AutoCloseable {

    private final RandomAccessFile raf;
    private final int channels;
    private final long dataStart;
    private final long dataBytes;
    private long readBytes;

    public final int sampleRate;

    public WavReader(File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
        long[] info = parseHeader(raf);
        if (info == null) {
            raf.close();
            throw new IOException("不是有效的 WAV 文件");
        }
        dataStart = info[0];
        dataBytes = info[1];
        channels = (int) info[2];
        sampleRate = (int) info[3];
    }

    /** 总样本数（按单声道计）。 */
    public long totalFrames() {
        return dataBytes / 2 / channels;
    }

    public double durationSeconds() {
        return sampleRate <= 0 ? 0 : (double) totalFrames() / sampleRate;
    }

    /** 已读出的帧数，用来算转写进度。 */
    public long framesRead() {
        return readBytes / 2 / channels;
    }

    /**
     * 读一段并混成单声道 float。
     *
     * @param out 目标缓冲
     * @return 写入的样本数；0 表示读完
     */
    public int read(float[] out) throws IOException {
        final int wantFrames = out.length;
        final int wantBytes = wantFrames * channels * 2;
        if (scratch == null || scratch.length < wantBytes) scratch = new byte[wantBytes + 8192];

        // RandomAccessFile 可能只读回一部分，凑够一整块再转换
        int got = 0;
        while (got < wantBytes) {
            int n = raf.read(scratch, got, wantBytes - got);
            if (n <= 0) break;
            got += n;
        }
        if (got < 2 * channels) return 0;

        final int gotFrames = got / 2 / channels;
        readBytes += (long) gotFrames * channels * 2;
        for (int i = 0; i < gotFrames; i++) {
            if (channels == 1) {
                out[i] = (short) le16(scratch, i * 2) / 32768f;
            } else {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += (short) le16(scratch, (i * channels + c) * 2);
                out[i] = (sum / (float) channels) / 32768f;
            }
        }
        return gotFrames;
    }

    private byte[] scratch;

    private static long[] parseHeader(RandomAccessFile raf) throws IOException {
        byte[] h = new byte[12];
        if (raf.read(h) < 12) return null;
        if (!tag(h, 0, "RIFF") || !tag(h, 8, "WAVE")) return null;

        int channels = 1, rate = 16000, bits = 16;
        long dataStart = -1, dataLen = -1;
        while (true) {
            byte[] ch = new byte[8];
            if (raf.read(ch) < 8) break;
            String id = new String(ch, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = le32(ch, 4);
            if (id.equals("fmt ")) {
                byte[] fmt = new byte[(int) Math.min(size, 32)];
                raf.readFully(fmt);
                channels = le16(fmt, 2);
                rate = (int) le32(fmt, 4);
                bits = le16(fmt, 14);
                if (size > fmt.length) raf.seek(raf.getFilePointer() + (size - fmt.length));
            } else if (id.equals("data")) {
                dataStart = raf.getFilePointer();
                long fileLen = raf.length();
                dataLen = Math.min(size, fileLen - dataStart);
                break;
            } else {
                raf.seek(raf.getFilePointer() + size + (size & 1));
            }
        }
        if (dataStart < 0 || dataLen <= 0 || channels <= 0) return null;
        if (bits != 16) return null;   // 我们自己的 WAV 一定是 16-bit
        return new long[]{dataStart, dataLen, channels, rate};
    }

    private static boolean tag(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) if (b[off + i] != s.charAt(i)) return false;
        return true;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}