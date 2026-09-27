package com.ice.scribe.audio;

/**
 * 流式线性插值重采样。
 *
 * <p>录音设备通常只保证 44.1k / 48k，而 whisper 只吃 16k；这里按块转换，
 * 块之间保留最后一个样本，避免拼接处出现断点。
 */
public final class Resampler {

    private final double ratio;     // 输入采样率 / 输出采样率
    private double pos = 1.0;       // 游标；0 号位等于上一块的最后一个样本
    private short prev;

    public Resampler(int srcRate, int dstRate) {
        this.ratio = (double) srcRate / (double) dstRate;
    }

    /** 输出缓冲区需要的最大长度。 */
    public static int outCapacity(int inLen, int srcRate, int dstRate) {
        return (int) Math.ceil(inLen * (double) dstRate / srcRate) + 2;
    }

    /**
     * 处理一块输入，把结果写入 out。
     *
     * @return 实际写出的样本数
     */
    public int process(short[] in, int inLen, short[] out) {
        if (inLen <= 0) return 0;
        int k = 0;
        final int cap = out.length;
        while (k < cap) {
            int i = (int) Math.floor(pos);
            if (i + 1 > inLen) break;              // 还差一个样本才能插值，留到下一块
            double f = pos - i;
            short a = i == 0 ? prev : in[i - 1];
            short b = i == inLen ? in[inLen - 1] : in[i];
            out[k++] = (short) (a + (b - a) * f);
            pos += ratio;
        }
        pos -= inLen;
        prev = in[inLen - 1];
        return k;
    }

    /** 直接把 short 缓冲转成 whisper 要的 [-1,1] float。 */
    public static float[] toFloat(short[] pcm, int len) {
        float[] f = new float[len];
        for (int i = 0; i < len; i++) f[i] = pcm[i] / 32768f;
        return f;
    }
}