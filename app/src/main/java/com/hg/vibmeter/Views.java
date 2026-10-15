package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 직접 그리는 계기 부품들 */
public final class Views {
    private Views() {}

    static float dpf(Context c, float v) { return U.dp(c, 1) * v; }

    /** 반원 게이지: 0~max mm/s, 목표 띠, 바늘, 가운데 큰 숫자 */
    public static class GaugeView extends View {
        public double value = Double.NaN, target = Double.NaN, tol = 3, max = 80;
        public final List<Double> hist = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public GaugeView(Context c) { super(c); }

        @Override protected void onMeasure(int w, int h) {
            int W = MeasureSpec.getSize(w);
            setMeasuredDimension(W, (int) (W * 0.66f));
        }

        private float ang(double v) { // 195° → -15°
            double t = Math.max(0, Math.min(1, v / max));
            return (float) (195 - 210 * t);
        }

        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), d = dpf(getContext(), 1);
            float cx = W / 2, R = W * 0.33f, cy = R + 44 * d;
            RectF oval = new RectF(cx - R, cy - R, cx + R, cy + R);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14 * d);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(U.RULE);
            // 캔버스 각도: 시계 방향, 0=오른쪽. 수학 각 a → 캔버스 -a
            cv.drawArc(oval, -195, 210, false, p);
            if (!Double.isNaN(target)) {
                p.setColor(U.OK);
                float a0 = ang(target - tol), a1 = ang(target + tol);
                cv.drawArc(oval, -a0, a0 - a1, false, p);
            }
            // 눈금
            p.setStrokeWidth(1 * d);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(U.SUB);
            tp.setTextSize(11.5f * d);
            tp.setTypeface(U.MONO);
            tp.setTextAlign(Paint.Align.CENTER);
            int step = max > 100 ? 5 : 2;
            int big = max > 100 ? 20 : 10;
            for (int v = 0; v <= max; v += step) {
                boolean b = v % big == 0;
                double a = Math.toRadians(ang(v));
                float r0 = R + 11 * d, r1 = R + (b ? 24 : 17) * d;
                p.setColor(U.INK);
                p.setStrokeWidth((b ? 2f : 1f) * d);
                cv.drawLine(cx + r0 * (float) Math.cos(a), cy - r0 * (float) Math.sin(a),
                        cx + r1 * (float) Math.cos(a), cy - r1 * (float) Math.sin(a), p);
                if (b) {
                    float rt = R + 36 * d;
                    cv.drawText(String.valueOf(v), cx + rt * (float) Math.cos(a), cy - rt * (float) Math.sin(a) + 4 * d, tp);
                }
            }
            // 최근 값 점
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < hist.size(); i++) {
                double a = Math.toRadians(ang(hist.get(i)));
                float r = R - 40 * d;
                p.setColor(U.SUB);
                p.setAlpha(60 + 150 * (i + 1) / Math.max(1, hist.size()));
                cv.drawCircle(cx + r * (float) Math.cos(a), cy - r * (float) Math.sin(a), 2.4f * d, p);
            }
            p.setAlpha(255);
            // 바늘
            if (!Double.isNaN(value)) {
                double a = Math.toRadians(ang(value));
                p.setStyle(Paint.Style.STROKE);
                p.setColor(U.SIGNAL);
                p.setStrokeWidth(4 * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                float r0 = R - 28 * d, r1 = R - 2 * d;
                cv.drawLine(cx + r0 * (float) Math.cos(a), cy - r0 * (float) Math.sin(a),
                        cx + r1 * (float) Math.cos(a), cy - r1 * (float) Math.sin(a), p);
            }
            // 숫자
            Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
            np.setColor(U.INK);
            np.setTypeface(U.MONO_B);
            np.setTextAlign(Paint.Align.CENTER);
            np.setTextSize(64 * d);
            String s = Double.isNaN(value) ? "--" : String.format(Locale.US, "%.1f", value);
            cv.drawText(s, cx, cy + 24 * d, np);
            np.setTypeface(U.MONO);
            np.setTextSize(13 * d);
            np.setColor(U.SUB);
            cv.drawText("mm/s RMS", cx, cy + 46 * d, np);
        }
    }

    /** 계기 창 안의 파형 */
    public static class WaveView extends View {
        public float[] data;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public WaveView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight();
            p.setColor(Color.argb(40, 159, 224, 184));
            p.setStrokeWidth(1);
            cv.drawLine(0, H / 2, W, H / 2, p);
            if (data == null || data.length < 2) return;
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE, mean = 0;
            for (float v : data) { mn = Math.min(mn, v); mx = Math.max(mx, v); mean += v; }
            mean /= data.length;
            float amp = Math.max(Math.abs(mx - mean), Math.abs(mn - mean));
            if (amp < 1e-3f) amp = 1;
            Path path = new Path();
            for (int i = 0; i < data.length; i++) {
                float x = W * i / (data.length - 1);
                float y = H / 2 - (data[i] - mean) / amp * (H / 2 - 3);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setColor(U.PHOS);
            p.setStrokeWidth(dpf(getContext(), 1.4f));
            cv.drawPath(path, p);
        }
    }

    /** 고정 상태 막대 (0~30%, 20% 한계선) */
    public static class FixBar extends View {
        public double pct = Double.NaN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public FixBar(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight(), d = dpf(getContext(), 1);
            float y0 = H / 2 - 4 * d, y1 = H / 2 + 4 * d;
            p.setColor(Color.rgb(0x34, 0x40, 0x3b));
            cv.drawRoundRect(new RectF(0, y0, W, y1), 2 * d, 2 * d, p);
            if (!Double.isNaN(pct)) {
                float w = (float) Math.min(1, pct / 30.0) * W;
                p.setColor(pct <= 20 ? U.PHOS : U.SIGNAL);
                cv.drawRoundRect(new RectF(0, y0, w, y1), 2 * d, 2 * d, p);
            }
            p.setColor(U.SIGNAL);
            float x = W * 20f / 30f;
            cv.drawRect(x - d, y0 - 3 * d, x + d, y1 + 3 * d, p);
        }
    }

    /** 스펙 줄의 눈금: 목표 띠 + 호기별 점 */
    public static class ScaleView extends View {
        public double lo = 20, hi = 60, target, tol = 3;
        public double[] marks = new double[0];
        public boolean hot;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public ScaleView(Context c) { super(c); }
        private float x(double v) { return (float) ((v - lo) / (hi - lo)) * getWidth(); }
        @Override protected void onDraw(Canvas cv) {
            float H = getHeight(), d = dpf(getContext(), 1);
            float cy = H / 2 + 5 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(0xcf, 0xd6, 0xcd));
            cv.drawRect(0, cy - d, getWidth(), cy + d, p);
            RectF band = new RectF(x(target - tol), cy - 8 * d, x(target + tol), cy + 8 * d);
            p.setColor(Color.argb(56, 47, 138, 91));
            cv.drawRoundRect(band, 3 * d, 3 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(Color.argb(150, 47, 138, 91));
            cv.drawRoundRect(band, 3 * d, 3 * d, p);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(U.OK);
            tp.setTypeface(U.MONO_B);
            tp.setTextSize(10.5f * d);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText(U.f1(target).replace(".0", ""), x(target), cy - 11 * d, tp);
            p.setStyle(Paint.Style.FILL);
            for (double m : marks) {
                p.setColor(U.PLATE);
                cv.drawCircle(x(m), cy, 7 * d, p);
                p.setColor(hot ? U.SIGNAL : U.INK);
                cv.drawCircle(x(m), cy, 5 * d, p);
            }
        }
    }

    /** 설정 주파수 → 진동 속도 곡선 + 목표 띠 + 추정점 */
    public static class CurveView extends View {
        public double[] fx = new double[0], vy = new double[0];
        public double target, tol = 3, estHz = Double.NaN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public CurveView(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), U.dp(getContext(), 170));
        }
        @Override protected void onDraw(Canvas cv) {
            if (fx.length == 0) return;
            float d = dpf(getContext(), 1), W = getWidth(), H = getHeight();
            float L = 30 * d, Rm = 12 * d, T = 10 * d, B = 22 * d;
            double f0 = Double.MAX_VALUE, f1 = -Double.MAX_VALUE, v0 = target - tol - 4, v1 = target + tol + 4;
            for (int i = 0; i < fx.length; i++) {
                f0 = Math.min(f0, fx[i]); f1 = Math.max(f1, fx[i]);
                v0 = Math.min(v0, vy[i] - 3); v1 = Math.max(v1, vy[i] + 3);
            }
            if (!Double.isNaN(estHz)) { f0 = Math.min(f0, estHz); f1 = Math.max(f1, estHz); }
            double pad = Math.max(0.15, (f1 - f0) * 0.15);
            f0 -= pad; f1 += pad;
            final double F0 = f0, F1 = f1, V0 = v0, V1 = v1;
            java.util.function.DoubleUnaryOperator X = f -> L + (f - F0) / (F1 - F0) * (W - L - Rm);
            java.util.function.DoubleUnaryOperator Y = v -> H - B - (v - V0) / (V1 - V0) * (H - B - T);
            // 목표 띠
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(46, 47, 138, 91));
            cv.drawRect(L, (float) Y.applyAsDouble(target + tol), W - Rm, (float) Y.applyAsDouble(target - tol), p);
            // 격자 + 글자
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(U.SUB);
            tp.setTypeface(U.MONO);
            tp.setTextSize(10 * d);
            p.setColor(Color.rgb(0xd3, 0xda, 0xd1));
            p.setStrokeWidth(d);
            int vs = (int) Math.ceil(V0 / 5) * 5;
            tp.setTextAlign(Paint.Align.RIGHT);
            for (int v = vs; v <= V1; v += 5) {
                float y = (float) Y.applyAsDouble(v);
                cv.drawLine(L, y, W - Rm, y, p);
                cv.drawText(String.valueOf(v), L - 4 * d, y + 3.5f * d, tp);
            }
            tp.setTextAlign(Paint.Align.CENTER);
            double fs = (F1 - F0) > 2 ? 1 : ((F1 - F0) > 0.8 ? 0.5 : 0.2);
            for (double f = Math.ceil(F0 / fs) * fs; f <= F1; f += fs)
                cv.drawText(String.format(Locale.US, "%.1f", f), (float) X.applyAsDouble(f), H - 6 * d, tp);
            // 선
            Integer[] idx = new Integer[fx.length];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(fx[a], fx[b]));
            Path path = new Path();
            for (int k = 0; k < idx.length; k++) {
                float x = (float) X.applyAsDouble(fx[idx[k]]), y = (float) Y.applyAsDouble(vy[idx[k]]);
                if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setColor(U.INK);
            p.setStrokeWidth(2 * d);
            cv.drawPath(path, p);
            // 추정
            if (!Double.isNaN(estHz)) {
                int near = idx[0];
                double best = Double.MAX_VALUE;
                for (int i = 0; i < fx.length; i++) if (Math.abs(fx[i] - estHz) < best) { best = Math.abs(fx[i] - estHz); near = i; }
                p.setColor(U.SIGNAL);
                p.setPathEffect(new DashPathEffect(new float[]{4 * d, 3 * d}, 0));
                cv.drawLine((float) X.applyAsDouble(fx[near]), (float) Y.applyAsDouble(vy[near]),
                        (float) X.applyAsDouble(estHz), (float) Y.applyAsDouble(target), p);
                p.setPathEffect(null);
            }
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < fx.length; i++) {
                float x = (float) X.applyAsDouble(fx[i]), y = (float) Y.applyAsDouble(vy[i]);
                p.setColor(U.PLATE); cv.drawCircle(x, y, 6.5f * d, p);
                p.setColor(U.INK); cv.drawCircle(x, y, 4.5f * d, p);
            }
            if (!Double.isNaN(estHz)) {
                float x = (float) X.applyAsDouble(estHz), y = (float) Y.applyAsDouble(target);
                p.setColor(Color.WHITE); cv.drawCircle(x, y, 8 * d, p);
                p.setColor(U.SIGNAL); cv.drawCircle(x, y, 6 * d, p);
                tp.setColor(U.SIGNAL);
                tp.setTypeface(U.MONO_B);
                tp.setTextSize(11 * d);
                cv.drawText(String.format(Locale.US, "%.1f?", estHz), x, y - 12 * d, tp);
            }
        }
    }

    /**
     * 목표 진동 속도에 맞는 설정 주파수 추정.
     * 목표를 사이에 둔 두 점이 있으면 직선 보간, 없으면 목표에 가장 가까운 두 점으로 연장 (가까운 점에서 최대 1Hz).
     */
    public static double estimate(double[] f, double[] v, double target) {
        int n = f.length;
        if (n < 2) return Double.NaN;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(f[a], f[b]));
        double bestGap = Double.MAX_VALUE, est = Double.NaN;
        for (int k = 0; k + 1 < n; k++) {
            int a = idx[k], b = idx[k + 1];
            if ((v[a] - target) * (v[b] - target) <= 0 && f[a] != f[b] && v[a] != v[b]) {
                double e = f[a] + (target - v[a]) * (f[b] - f[a]) / (v[b] - v[a]);
                double gap = Math.abs(f[b] - f[a]);
                if (gap < bestGap) { bestGap = gap; est = e; }
            }
        }
        if (!Double.isNaN(est)) return est;
        Integer[] byV = idx.clone();
        java.util.Arrays.sort(byV, (a, b) -> Double.compare(Math.abs(v[a] - target), Math.abs(v[b] - target)));
        int a = byV[0], b = -1;
        for (int k = 1; k < n; k++) if (f[byV[k]] != f[a] && v[byV[k]] != v[a]) { b = byV[k]; break; }
        if (b < 0) return Double.NaN;
        double e = f[a] + (target - v[a]) * (f[b] - f[a]) / (v[b] - v[a]);
        if (Math.abs(e - f[a]) > 1.0) return Double.NaN;
        return e;
    }
}
