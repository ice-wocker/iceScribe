package com.ice.scribe.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * 流式音频链路的回归测试。
 *
 * <p>README 的核心卖点是「边录边重采样、转写按段流式读取，录一小时内存也不涨」。
 * 这话能不能站住，全看两个东西：分块处理的输出**必须**和不分块完全一致，
 * 且任意块大小下都不能越界。这两点此前没有任何测试兜着——而它们恰恰是
 * 纯函数、离线可跑、最容易出静默错误的部位。
 */
public class StreamingAudioTest {

    /** 生成一段确定性的测试信号。 */
    private static short[] signal(int len) {
        short[] s = new short[len];
        for (int i = 0; i < len; i++) {
            s[i] = (short) (Math.sin(i * 2 * Math.PI * 440 / 48000) * 20000);
        }
        return s;
    }

    /**
     * 分块重采样的结果必须与「一次性喂完」逐样本一致。
     *
     * <p>这是流式设计的命门：块之间要保留插值游标，一旦实现有偏差，
     * 拼接处会出现周期性断点，转写时间轴就会漂。
     */
    @Test
    public void chunkedResampleEqualsSinglePass() {
        short[] input = signal(48000);                  // 1 秒 48k

        // 参照：一次喂完
        Resampler ref = new Resampler(48000, 16000);
        short[] refOut = new short[Resampler.outCapacity(input.length, 48000, 16000)];
        int refN = ref.process(input, input.length, refOut);

        // 被测量：用一组互不相同的块大小切着喂
        int[] chunkSizes = {1, 7, 480, 1000, 4096};
        for (int chunk : chunkSizes) {
            Resampler rs = new Resampler(48000, 16000);
            short[] tmp = new short[Resampler.outCapacity(chunk, 48000, 16000)];
            short[] all = new short[refN + 8];
            int total = 0;
            for (int off = 0; off < input.length; off += chunk) {
                int n = Math.min(chunk, input.length - off);
                short[] part = new short[n];
                System.arraycopy(input, off, part, 0, n);
                int got = rs.process(part, n, tmp);
                System.arraycopy(tmp, 0, all, total, got);
                total += got;
            }
            assertEquals("块大小 " + chunk + " 的输出长度应与单次处理一致", refN, total);
            assertArrayEquals("块大小 " + chunk + " 的输出样本应与单次处理一致",
                    java.util.Arrays.copyOf(refOut, refN), java.util.Arrays.copyOf(all, total));
        }
    }

    /**
     * 输出缓冲恰好等于 outCapacity() 时不能越界。
     *
     * <p>调用方就是按这个函数分配缓冲的，它算小了就会静默丢样本或抛异常。
     */
    @Test
    public void outCapacityIsSufficient() {
        int[][] cases = {{1, 48000, 16000}, {480, 48000, 16000}, {44100, 44100, 16000},
                {16000, 16000, 16000}, {3, 8000, 16000}, {100000, 48000, 16000}};
        for (int[] c : cases) {
            int inLen = c[0], src = c[1], dst = c[2];
            Resampler rs = new Resampler(src, dst);
            short[] in = signal(inLen);
            short[] out = new short[Resampler.outCapacity(inLen, src, dst)];
            int n = rs.process(in, inLen, out);           // 缓冲不够这里会抛 ArrayIndexOutOfBounds
            assertTrue("输出样本数不应为负", n >= 0);
            assertTrue("输出不应超过缓冲容量", n <= out.length);
        }
    }

    /** 上采样（8k → 16k）也要能用，导入的音频未必都是 48k。 */
    @Test
    public void upsamplingKeepsDuration() {
        short[] input = signal(8000);                   // 1 秒 8k
        Resampler rs = new Resampler(8000, 16000);
        short[] out = new short[Resampler.outCapacity(input.length, 8000, 16000)];
        int n = rs.process(input, input.length, out);
        assertTrue("1 秒 8k → 16k 应接近 16000 个样本，实际 " + n, Math.abs(n - 16000) <= 2);
    }

    /**
     * 分块读取的 WAV 必须与一次读完得到完全相同的样本序列。
     *
     * <p>「按段流式读取」的实现细节（凑满缓冲、混声道、字节序）只要错一处，
     * 结果就会与整读不一致——而整读是肉眼可验证的参照。
     */
    @Test
    public void wavChunkedReadMatchesWholeRead() throws IOException {
        short[] pcm = signal(16000);
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((i * 37) % 20000 - 10000);

        File tmp = File.createTempFile("ice-stream", ".wav");
        tmp.deleteOnExit();
        try (WavFile.Writer w = new WavFile.Writer(tmp, WavFile.RATE_16K)) {
            w.write(pcm, pcm.length);
        }

        // 参照：一次读完
        float[] whole = new float[pcm.length];
        int wholeN;
        try (WavReader r = new WavReader(tmp)) {
            wholeN = r.read(whole);
        }

        // 被测量：用不规则块大小分多次读
        for (int chunk : new int[]{1, 13, 512, 4000}) {
            float[] all = new float[pcm.length + chunk];
            int total = 0;
            try (WavReader r = new WavReader(tmp)) {
                float[] buf = new float[chunk];
                int n;
                while ((n = r.read(buf)) > 0) {
                    System.arraycopy(buf, 0, all, total, n);
                    total += n;
                }
            }
            assertEquals("块大小 " + chunk + " 读出的总帧数应一致", wholeN, total);
            for (int i = 0; i < total; i++) {
                assertEquals("块大小 " + chunk + " 第 " + i + " 个样本应一致",
                        whole[i], all[i], 0f);
            }
        }
    }

    /** 立体声应按声道求平均混成单声道——只取一个声道会让声音忽大忽小。 */
    @Test
    public void stereoDownmix() throws IOException {
        File tmp = File.createTempFile("ice-stereo", ".wav");
        tmp.deleteOnExit();
        // 手工构造 16-bit 立体声 WAV：左 +1000，右 -1000 → 混音应接近 0
        short[] interleaved = {1000, -1000, 2000, -2000, 32767, -32768};
        writeStereoWav(tmp, interleaved, 16000);

        try (WavReader r = new WavReader(tmp)) {
            assertEquals(3, r.totalFrames());
            float[] buf = new float[3];
            int n = r.read(buf);
            assertEquals(3, n);
            assertEquals(0f, buf[0], 0.001f);
            assertEquals(0f, buf[1], 0.001f);
            assertEquals(-1f / 32768f, buf[2], 0.001f);   // (32767 + -32768)/2 / 32768
        }
    }

    /** 帧数与时长要把声道数算进去，否则进度条会走快走慢。 */
    @Test
    public void durationAccountsForChannels() throws IOException {
        File tmp = File.createTempFile("ice-dur", ".wav");
        tmp.deleteOnExit();
        writeStereoWav(tmp, new short[2 * 16000], 16000);   // 16000 帧 × 2 声道 = 1 秒
        try (WavReader r = new WavReader(tmp)) {
            assertEquals(16000, r.totalFrames());
            assertEquals(1.0, r.durationSeconds(), 0.001);
        }
    }

    /** 手工写一个最小立体声 WAV（本仓库的 Writer 只出单声道，为测试单独构造）。 */
    private static void writeStereoWav(File f, short[] interleaved, int rate) throws IOException {
        int dataBytes = interleaved.length * 2;
        try (java.io.DataOutputStream d = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            d.writeBytes("RIFF");
            d.writeInt(Integer.reverseBytes(36 + dataBytes));
            d.writeBytes("WAVE");
            d.writeBytes("fmt ");
            d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));       // PCM
            d.writeShort(Short.reverseBytes((short) 2));       // 2 声道
            d.writeInt(Integer.reverseBytes(rate));
            d.writeInt(Integer.reverseBytes(rate * 2 * 2));    // byte rate
            d.writeShort(Short.reverseBytes((short) 4));       // block align
            d.writeShort(Short.reverseBytes((short) 16));      // bits
            d.writeBytes("data");
            d.writeInt(Integer.reverseBytes(dataBytes));
            for (short s : interleaved) d.writeShort(Short.reverseBytes(s));
        }
    }
}
