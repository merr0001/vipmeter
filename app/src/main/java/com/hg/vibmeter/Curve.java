package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 설비 반응 곡선: 설비를 켤 때 주파수가 올라가는 동안 진동 세기를 기록 (캡슐) */
final class Curve {
    long time;
    int unit;
    double setHz = Double.NaN;
    /** {초, 주파수 Hz, 속도 RMS mm/s} */
    final List<double[]> pts = new ArrayList<>();

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("time", time); o.put("unit", unit); o.put("setHz", Double.isNaN(setHz) ? null : setHz);
            JSONArray a = new JSONArray();
            for (double[] p : pts) a.put(new JSONArray().put(p[0]).put(p[1]).put(p[2]));
            o.put("pts", a);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static Curve fromJson(String s) {
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            Curve c = new Curve();
            c.time = o.optLong("time"); c.unit = o.optInt("unit"); c.setHz = o.optDouble("setHz", Double.NaN);
            JSONArray a = o.getJSONArray("pts");
            for (int i = 0; i < a.length(); i++) { JSONArray p = a.getJSONArray(i); c.pts.add(new double[]{p.getDouble(0), p.getDouble(1), p.getDouble(2)}); }
            return c.pts.size() >= 3 ? c : null;
        } catch (Exception e) { return null; }
    }

    static void save(Context c, Curve k) { Store.putStr(c, "curve_" + k.unit, k.toJson()); }
    static Curve load(Context c, int unit) { return fromJson(Store.getStr(c, "curve_" + unit, null)); }

    /** 주파수 순으로 정렬하고 같은 칸(0.5Hz)끼리 평균 */
    List<double[]> sorted() {
        List<double[]> s = new ArrayList<>();
        for (double[] p : pts) if (p[1] > 10 && p[2] > 0) s.add(new double[]{p[1], p[2]});
        Collections.sort(s, (x, y) -> Double.compare(x[0], y[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] p : s) {
            if (!out.isEmpty() && Math.abs(out.get(out.size() - 1)[0] - p[0]) < 0.5) {
                double[] q = out.get(out.size() - 1);
                q[0] = (q[0] + p[0]) / 2; q[1] = (q[1] + p[1]) / 2;
            } else out.add(p.clone());
        }
        return out;
    }

    /** 가장 센 곳 {Hz, mm/s} */
    double[] peak() {
        double[] b = null;
        for (double[] p : sorted()) if (b == null || p[1] > b[1]) b = p;
        return b;
    }

    /** 이 주파수 근처(±3Hz) 기울기: 1Hz 바꾸면 RMS가 몇 % 변하는지 */
    double slopePct(double hz) {
        List<double[]> s = sorted();
        double sx = 0, sy = 0, sxx = 0, sxy = 0; int n = 0;
        for (double[] p : s) if (Math.abs(p[0] - hz) <= 3) { sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1]; n++; }
        if (n < 3) return Double.NaN;
        double d = n * sxx - sx * sx;
        if (Math.abs(d) < 1e-9) return Double.NaN;
        double k = (n * sxy - sx * sy) / d, mean = sy / n;
        return mean > 0 ? k / mean * 100 : Double.NaN;
    }

    String summary() {
        StringBuilder b = new StringBuilder();
        double[] pk = peak();
        if (pk != null) b.append(String.format(Locale.US, "가장 센 곳: %.1f Hz · %.1f mm/s\n", pk[0], pk[1]));
        double hz = !Double.isNaN(setHz) ? setHz : (pts.isEmpty() ? Double.NaN : pts.get(pts.size() - 1)[1]);
        if (!Double.isNaN(hz)) {
            double sl = slopePct(hz);
            if (!Double.isNaN(sl)) {
                b.append(String.format(Locale.US, "설정 %.1f Hz에서: 주파수를 1 Hz 내리면 RMS 약 %s%.0f%% (%s)\n", hz, -sl >= 0 ? "+" : "−", Math.abs(sl), -sl >= 0 ? "세짐" : "약해짐"));
                double a = Math.abs(sl);
                b.append(a >= 8 ? "⚠ 기울기가 가파른 곳 — 주파수가 조금만 흔들려도 RMS가 크게 바뀝니다"
                        : a >= 3 ? "보통 — 주파수 변화에 RMS가 적당히 반응합니다"
                        : "✓ 완만한 곳 — 주파수가 조금 흔들려도 RMS가 안정적입니다");
            }
        }
        return b.toString().trim();
    }

    /** 곡선 그림 */
    static final class CurveView extends View {
        Curve c;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CurveView(Context ctx) { super(ctx); }

        @Override protected void onMeasure(int w, int h) {
            int W = MeasureSpec.getSize(w);
            setMeasuredDimension(W, (int) (W * 0.62f));
        }

        @Override protected void onDraw(Canvas cv) {
            if (c == null) return;
            List<double[]> s = c.sorted();
            if (s.size() < 2) return;
            float d = getResources().getDisplayMetrics().density;
            float W = getWidth(), H = getHeight(), L = 40 * d, R = W - 10 * d, T = 12 * d, B = H - 26 * d;
            double f0 = s.get(0)[0], f1 = s.get(s.size() - 1)[0], v1 = 0;
            for (double[] q : s) v1 = Math.max(v1, q[1]);
            f0 = Math.floor(f0 / 10) * 10; f1 = Math.ceil(Math.max(f1, Double.isNaN(c.setHz) ? f1 : c.setHz + 2) / 10) * 10;
            v1 = Math.ceil(v1 * 1.1 / 10) * 10;
            final double F0 = f0, F1 = f1, V1 = v1;
            java.util.function.DoubleUnaryOperator X = f -> L + (f - F0) / (F1 - F0) * (R - L);
            java.util.function.DoubleUnaryOperator Y = v -> B - v / V1 * (B - T);
            p.setTextSize(10 * d);
            // 눈금
            p.setColor(Color.rgb(0xdd, 0xe3, 0xea)); p.setStrokeWidth(d);
            for (double v = 0; v <= V1; v += V1 / 4) {
                float y = (float) Y.applyAsDouble(v);
                cv.drawLine(L, y, R, y, p);
                p.setColor(U.SUB); cv.drawText(String.format(Locale.US, "%.0f", v), 4 * d, y + 4 * d, p); p.setColor(Color.rgb(0xdd, 0xe3, 0xea));
            }
            p.setColor(U.SUB);
            for (double f = F0; f <= F1 + 0.1; f += 10) cv.drawText(String.format(Locale.US, "%.0f", f), (float) X.applyAsDouble(f) - 8 * d, H - 8 * d, p);
            cv.drawText("Hz", R - 14 * d, H - 8 * d, p);
            cv.drawText("mm/s", 4 * d, T - 2 * d, p);
            // 설정 주파수
            if (!Double.isNaN(c.setHz)) {
                p.setColor(Color.rgb(0x2f, 0x6f, 0xe4)); p.setStrokeWidth(1.5f * d);
                p.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
                float x = (float) X.applyAsDouble(c.setHz);
                cv.drawLine(x, T, x, B, p);
                p.setPathEffect(null);
                cv.drawText(String.format(Locale.US, "설정 %.1f", c.setHz), x + 4 * d, T + 10 * d, p);
            }
            // 곡선
            Path path = new Path();
            for (int i = 0; i < s.size(); i++) {
                float x = (float) X.applyAsDouble(s.get(i)[0]), y = (float) Y.applyAsDouble(s.get(i)[1]);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * d); p.setColor(U.INK);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            double[] pk = c.peak();
            if (pk != null) {
                p.setColor(U.BAD);
                float x = (float) X.applyAsDouble(pk[0]), y = (float) Y.applyAsDouble(pk[1]);
                cv.drawCircle(x, y, 4.5f * d, p);
                cv.drawText(String.format(Locale.US, "%.1f Hz", pk[0]), x + 6 * d, y - 4 * d, p);
            }
        }
    }
}
