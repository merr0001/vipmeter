package com.hg.vibmeter;

/**
 * 가속도 샘플 묶음에서 진동 속도를 계산한다. (안드로이드 의존성 없음 → PC에서 바로 시험 가능)
 *
 * 진동 속도 RMS(mm/s) = 1000 / (2π) × Z축 RMS 가속도(m/s²) ÷ 주파수(Hz) = 159.15 × Zrms ÷ f
 * 참고 피크(cm/s)     = 100 × √2 / (2π) × Zrms ÷ f                      = 22.5 × Zrms ÷ f
 * Z축 RMS 는 평균(중력)을 뺀 표준편차.
 * 주파수는 Z축 FFT 봉우리(20~250Hz)를 포물선 보간해서 찾는다.
 */
public final class Analyzer {

    public static final double K_RMS_MM = 1000.0 / (2.0 * Math.PI);          // 159.15
    public static final double K_PEAK_CM = 100.0 * Math.sqrt(2.0) / (2.0 * Math.PI); // 22.51

    public static final class Result {
        public int n;
        public double fs;       // 실제 샘플 속도 (Hz)
        public double freq;     // 실측 진동 주파수 (Hz)
        public double rmsX, rmsY, rmsZ;
        public double vRms;     // mm/s
        public double vPeak;    // cm/s
        public double pctX, pctY;
        public boolean valid;
    }

    private Analyzer() {}

    /** t: 나노초 타임스탬프(오름차순), x/y/z: m/s², 앞에서부터 n개 사용 */
    public static Result analyze(long[] t, float[] x, float[] y, float[] z, int n) {
        Result r = new Result();
        r.n = n;
        if (n < 64) return r;
        double dur = (t[n - 1] - t[0]) / 1e9;
        if (dur <= 0) return r;
        r.fs = (n - 1) / dur;

        r.rmsX = std(x, n);
        r.rmsY = std(y, n);
        r.rmsZ = std(z, n);

        r.freq = peakFrequency(t, z, n, r.fs);
        if (r.freq > 0 && r.rmsZ > 0) {
            r.vRms = K_RMS_MM * r.rmsZ / r.freq;
            r.vPeak = K_PEAK_CM * r.rmsZ / r.freq;
            r.pctX = 100.0 * r.rmsX / r.rmsZ;
            r.pctY = 100.0 * r.rmsY / r.rmsZ;
            r.valid = true;
        }
        return r;
    }

    /** 주파수를 직접 지정해 계산 (설정 주파수로 계산하고 싶을 때) */
    public static double velocityRms(double rmsZ, double freq) {
        return freq > 0 ? K_RMS_MM * rmsZ / freq : 0;
    }

    static double std(float[] a, int n) {
        double m = 0;
        for (int i = 0; i < n; i++) m += a[i];
        m /= n;
        double s = 0;
        for (int i = 0; i < n; i++) { double d = a[i] - m; s += d * d; }
        return Math.sqrt(s / n);
    }

    /** 고르지 않은 샘플 간격을 일정 간격으로 다시 맞춘 뒤 FFT 봉우리 주파수 */
    static double peakFrequency(long[] t, float[] z, int n, double fs) {
        // 1) 일정 간격 재샘플 (선형 보간)
        int m = n;
        double dt = 1.0 / fs;
        double[] u = new double[m];
        double t0 = t[0] / 1e9;
        int j = 0;
        for (int i = 0; i < m; i++) {
            double ti = t0 + i * dt;
            while (j < n - 2 && t[j + 1] / 1e9 < ti) j++;
            double ta = t[j] / 1e9, tb = t[j + 1] / 1e9;
            double w = tb > ta ? (ti - ta) / (tb - ta) : 0;
            if (w < 0) w = 0; if (w > 1) w = 1;
            u[i] = z[j] + w * (z[j + 1] - z[j]);
        }
        // 2) 평균 제거 + Hann 창
        double mean = 0;
        for (double v : u) mean += v;
        mean /= m;
        int N = 1;
        while (N < 4 * m) N <<= 1;
        if (N > 32768) N = 32768;
        double[] re = new double[N], im = new double[N];
        int len = Math.min(m, N);
        for (int i = 0; i < len; i++) {
            double h = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (len - 1));
            re[i] = (u[i] - mean) * h;
        }
        fft(re, im);
        // 3) 20~250Hz 사이 최대
        double df = fs / N;
        int k0 = Math.max(1, (int) Math.ceil(20.0 / df));
        int k1 = Math.min(N / 2 - 2, (int) Math.floor(Math.min(250.0, fs / 2 - 1) / df));
        int kb = -1;
        double best = -1;
        double[] mag = new double[N / 2];
        for (int k = 1; k < N / 2; k++) mag[k] = Math.hypot(re[k], im[k]);
        for (int k = k0; k <= k1; k++) if (mag[k] > best) { best = mag[k]; kb = k; }
        if (kb < 1) return 0;
        // 4) 포물선 보간
        double a = mag[kb - 1], b = mag[kb], c = mag[kb + 1];
        double den = a - 2 * b + c;
        double p = den != 0 ? 0.5 * (a - c) / den : 0;
        if (p > 0.5) p = 0.5; if (p < -0.5) p = -0.5;
        return (kb + p) * df;
    }

    /** 제자리 radix-2 FFT (길이는 2의 거듭제곱) */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
