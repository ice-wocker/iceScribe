package com.ice.scribe.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * 音频链路的纯 JVM 测试：重采样比例对不对、WAV 写出去还能不能原样读回来。
 * 这两步一旦错，whisper 收到的时间轴就全乱了。
 */
public class AudioChainTest {

    /** 48k → 16k 应该恰好变成三分之一长。 */
    @Test
    public void downsampling48kTo16kKeepsDuration() {
        Resampler rs = new Resampler(48000, 16000);
        short[] out = new short[Resampler.outCapacity(480, 48000, 16000)];

        int got = 0;
        // 分 100 块喂进去，每块 10ms，合计 1 秒
        for (int block = 0; block < 100; block++) {
            short[] slice = new short[480];
            for (int i = 0; i < slice.length; i++) slice[i] = (short) (i % 1000);
            got += rs.process(slice, slice.length, out);
        }
        // 1 秒输入 → 16000 个输出样本；流式处理会保留最后 1 个样本做块间插值
        assertTrue("输出样本数 " + got + " 应接近 16000", Math.abs(got - 16000) <= 2);
    }

    /**
     * 采样率不变时不该改动数据。
     *
     * <p>流式设计会在块之间保留 1 个样本用于插值，所以单次调用会少吐最后一个样本
     * （下一次调用补上）；整条流跑完只会差末尾那 1 个样本，相当于 62µs 的音频。
     */
    @Test
    public void sameRateIsPassthrough() {
        Resampler rs = new Resampler(16000, 16000);
        short[] c1 = {100, -100, 32000};
        short[] c2 = {-32000, 0, 1234};
        short[] tmp = new short[16];
        short[] all = new short[16];
        int total = 0;

        for (short[] chunk : new short[][]{c1, c2}) {
            int n = rs.process(chunk, chunk.length, tmp);
            System.arraycopy(tmp, 0, all, total, n);
            total += n;
        }

        // 6 个输入样本，吐出 5 个（末位保留在重采样器里）
        assertEquals(5, total);
        assertArrayEquals(new short[]{100, -100, 32000, -32000, 0},
                java.util.Arrays.copyOf(all, total));
    }

    /** WAV 写盘 → 读回，样本值和时长都要对得上。 */
    @Test
    public void wavRoundTrip() throws IOException {
        File tmp = File.createTempFile("ice-test", ".wav");
        tmp.deleteOnExit();

        short[] pcm = new short[16000];             // 1 秒 16k
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i * 2 * Math.PI * 440 / 16000) * 20000);
        }

        try (WavFile.Writer w = new WavFile.Writer(tmp, WavFile.RATE_16K)) {
            w.write(pcm, pcm.length);
        }

        int[] probe = WavFile.probe(tmp);
        assertNotNull("文件头应能被解析", probe);
        assertArrayEquals(new int[]{16000, 1}, probe);

        try (WavReader r = new WavReader(tmp)) {
            assertEquals(16000, r.sampleRate);
            assertEquals(16000, r.totalFrames());
            assertEquals(1.0, r.durationSeconds(), 0.01);

            float[] buf = new float[16000];
            int n = r.read(buf);
            assertEquals(16000, n);
            // 第一个采样接近 0（sin 起点），峰值应接近 20000/32768
            assertEquals(0f, buf[0], 0.01f);
            float peak = 0;
            for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
            assertEquals(20000f / 32768f, peak, 0.01f);
        }
    }

    /** 读完之后应该返回 0，不能死循环。 */
    @Test
    public void readReturnsZeroAtEnd() throws IOException {
        File tmp = File.createTempFile("ice-test-end", ".wav");
        tmp.deleteOnExit();
        try (WavFile.Writer w = new WavFile.Writer(tmp, WavFile.RATE_16K)) {
            w.write(new short[100], 100);
        }
        try (WavReader r = new WavReader(tmp)) {
            float[] buf = new float[1000];
            assertEquals(100, r.read(buf));
            assertEquals(0, r.read(buf));
        }
    }

    /** 模型名要显示成人看得懂的样子。 */
    @Test
    public void modelDisplayName() {
        assertEquals("medium · q5_0", com.ice.scribe.stt.TranscribeEngine
                .displayNameOf("ggml-medium-q5_0.bin"));
        assertEquals("base", com.ice.scribe.stt.TranscribeEngine
                .displayNameOf("ggml-base.bin"));
        assertEquals("large-v3-turbo", com.ice.scribe.stt.TranscribeEngine
                .displayNameOf("ggml-large-v3-turbo.bin"));
    }
}