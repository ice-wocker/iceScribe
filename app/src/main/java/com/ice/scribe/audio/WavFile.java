package com.ice.scribe.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 读写 16-bit PCM 单声道 WAV。 */
public final class WavFile {

    public static final int RATE_16K = 16000;

    private WavFile() {
    }

    /** 增量写 WAV：先写好占位头，结束时回填真实长度，中途崩溃也不会留下坏文件头。 */
    public static final class Writer implements AutoCloseable {
        private final File file;
        private final OutputStream out;
        private long dataBytes;

        public Writer(File file, int sampleRate) throws IOException {
            this.file = file;
            this.out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(header(0, sampleRate, 1));
        }

        public void write(short[] pcm, int len) throws IOException {
            byte[] b = new byte[len * 2];
            for (int i = 0; i < len; i++) {
                b[i * 2] = (byte) (pcm[i] & 0xff);
                b[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            out.write(b);
            dataBytes += b.length;
        }

        @Override
        public void close() throws IOException {
            out.flush();
            out.close();
            // 回填 RIFF size 与 data size
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(4);
                raf.write(le32(36 + dataBytes));
                raf.seek(40);
                raf.write(le32(dataBytes));
            }
        }

        public long bytes() {
            return dataBytes;
        }
    }

    private static byte[] header(int dataBytes, int sampleRate, int channels) {
        int byteRate = sampleRate * channels * 2;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataBytes);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);                       // PCM
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) (channels * 2));          // block align
        b.putShort((short) 16);                      // bits
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataBytes);
        return b.array();
    }

    private static byte[] le32(long v) {
        return new byte[]{(byte) (v & 0xff), (byte) ((v >> 8) & 0xff),
                (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)};
    }

    /** 读 WAV 的声道数与采样率，用于展示；读不到返回 null。 */
    public static int[] probe(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[44];
            if (raf.read(head) < 44) return null;
            ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x46464952) return null;      // "RIFF"
            int ch = b.getShort(22) & 0xffff;
            int rate = b.getInt(24);
            return new int[]{rate, ch};
        } catch (Exception e) {
            return null;
        }
    }
}