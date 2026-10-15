package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** 판이 끝났을 때 가공 결과(각도 · R 형상 · 전장) 입력 */
final class ResultSheet {
    private ResultSheet() {}

    static final double LEN_TOL = 0.010;

    static class LenBar extends View {
        double nominal = Double.NaN, value = Double.NaN;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LenBar(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            if (Double.isNaN(nominal)) return;
            float W = getWidth(), d = U.dp(getContext(), 1);
            float y0 = 4 * d, y1 = 24 * d;
            double lo = nominal - LEN_TOL * 1.2, hi = nominal + LEN_TOL * 1.2;
            java.util.function.DoubleUnaryOperator X = v -> (v - lo) / (hi - lo) * W;
            int[][] zones = {{-12, -10, U.BAD}, {-10, -7, U.WARN}, {-7, 7, U.OK}, {7, 10, U.WARN}, {10, 12, U.BAD}};
            for (int[] z : zones) {
                p.setColor(Color.argb(55, Color.red(z[2]), Color.green(z[2]), Color.blue(z[2])));
                cv.drawRect((float) X.applyAsDouble(nominal + z[0] / 1000.0), y0, (float) X.applyAsDouble(nominal + z[1] / 1000.0), y1, p);
            }
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(U.SUB); tp.setTypeface(U.MONO); tp.setTextSize(10.5f * d); tp.setTextAlign(Paint.Align.CENTER);
            for (int k = -1; k <= 1; k++) {
                double v = nominal + k * LEN_TOL;
                cv.drawText(String.format(Locale.US, "%.3f", v), (float) X.applyAsDouble(v), y1 + 14 * d, tp);
            }
            if (!Double.isNaN(value)) {
                p.setColor(U.SIGNAL);
                float x = (float) Math.max(0, Math.min(W, X.applyAsDouble(value)));
                cv.drawRect(x - 1.5f * d, 0, x + 1.5f * d, y1 + 4 * d, p);
            }
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), U.dp(getContext(), 44)); }
    }

    private static double parse(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception ex) { return Double.NaN; }
    }

    static void show(MainActivity a, int unit) {
        Store.Machine m = Store.machine(a, unit);
        Store.Record last = Store.lastRecord(Store.records(a), unit, m.spec.isEmpty() ? null : m.spec);
        int rounds = m.planActive ? m.doneRounds : 0;
        int minutes = m.planActive ? m.doneRounds * m.roundMin : 0;

        LinearLayout s = a.sheetBox(unit + "호기 " + (m.spec.isEmpty() ? "" : m.spec) + " 결과", "판 완료 · 반출");
        ScrollView sv = new ScrollView(a);
        LinearLayout body = U.col(a);
        sv.addView(body);

        LinearLayout auto = U.row(a);
        cell(a, auto, "설정", Double.isNaN(m.setHz) ? "—" : U.f1(m.setHz) + " Hz");
        cell(a, auto, "진동 속도", last == null ? "—" : U.f1(last.vRms));
        cell(a, auto, "가공", rounds > 0 ? rounds + "회 " + minutes + "분" : "—");
        body.addView(auto, U.match());

        // 각도
        LinearLayout r1 = U.row(a);
        r1.addView(U.tv(a, "각도 (°)", 15, U.INK, U.BOLD), U.lpW(1));
        EditText angle = num(a, "", false);
        r1.addView(step(a, "−", v -> bump(angle, -1, 0)));
        r1.addView(angle, new LinearLayout.LayoutParams(U.dp(a, 78), ViewGroup.LayoutParams.WRAP_CONTENT));
        r1.addView(step(a, "+", v -> bump(angle, 1, 0)));
        body.addView(r1, U.margins(U.match(), a, 0, 12, 0, 4));

        // R 형상
        LinearLayout r2 = U.row(a);
        r2.addView(U.tv(a, "R 형상", 15, U.INK, U.BOLD), U.lpW(1));
        final String[] shape = {""};
        String[] opts = {"부족", "양호", "과다"};
        TextView[] seg = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            seg[i] = U.tv(a, opts[i], 14, U.INK, U.BOLD);
            seg[i].setGravity(Gravity.CENTER);
            seg[i].setBackground(U.box(a, U.ENAMEL, 5, U.INK, 1.5f));
            seg[i].setOnClickListener(v -> {
                shape[0] = opts[k];
                for (int j = 0; j < 3; j++) {
                    boolean on = j == k;
                    seg[j].setBackground(U.box(a, on ? (k == 1 ? U.OK : U.WARN) : U.ENAMEL, 5, U.INK, 1.5f));
                    seg[j].setTextColor(on ? Color.WHITE : U.INK);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(U.dp(a, 56), U.dp(a, 38));
            p.leftMargin = U.dp(a, 4);
            r2.addView(seg[i], p);
        }
        body.addView(r2, U.margins(U.match(), a, 0, 8, 0, 4));

        // 전장
        Store.Spec sp = Store.spec(a, m.spec);
        double nominal = sp != null ? sp.nominalLength() : Double.NaN;
        LinearLayout r3 = U.row(a);
        r3.addView(U.tv(a, "전장", 15, U.INK, U.BOLD), U.lpW(1));
        EditText len = num(a, Double.isNaN(nominal) ? "" : String.format(Locale.US, "%.3f", nominal), true);
        r3.addView(step(a, "−", v -> bump(len, -0.001, 3)));
        r3.addView(len, new LinearLayout.LayoutParams(U.dp(a, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
        r3.addView(step(a, "+", v -> bump(len, 0.001, 3)));
        body.addView(r3, U.margins(U.match(), a, 0, 8, 0, 4));
        LenBar bar = new LenBar(a);
        bar.nominal = nominal;
        body.addView(bar, U.match());
        TextView warn = U.tv(a, "", 12.5f, U.INK, null);
        warn.setPadding(U.dp(a, 9), U.dp(a, 6), U.dp(a, 9), U.dp(a, 6));
        warn.setVisibility(View.GONE);
        body.addView(warn, U.margins(U.match(), a, 0, 4, 0, 0));
        Runnable upd = () -> {
            double v = parse(len);
            bar.value = v;
            bar.invalidate();
            if (Double.isNaN(v) || Double.isNaN(nominal)) { warn.setVisibility(View.GONE); return; }
            double dev = v - nominal;
            String ds = String.format(Locale.US, "%+.3f", dev);
            if (Math.abs(dev) > LEN_TOL + 1e-9) {
                warn.setText("공차 밖 (" + ds + ")");
                warn.setBackground(U.box(a, Color.rgb(0xf3, 0xd2, 0xcc), 4, 0, 0));
                warn.setTextColor(U.BAD);
                warn.setVisibility(View.VISIBLE);
            } else if (Math.abs(dev) > LEN_TOL * 0.7) {
                warn.setText("공차 끝에 가까움 (" + ds + ") · 더 가공하면 짧아질 수 있음");
                warn.setBackground(U.box(a, Color.rgb(0xf6, 0xe6, 0xc4), 4, 0, 0));
                warn.setTextColor(Color.rgb(0x8a, 0x61, 0x10));
                warn.setVisibility(View.VISIBLE);
            } else warn.setVisibility(View.GONE);
        };
        len.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence q, int x, int y, int z) {}
            public void onTextChanged(CharSequence q, int x, int y, int z) {}
            public void afterTextChanged(Editable e) { upd.run(); }
        });
        upd.run();

        EditText memo = new EditText(a);
        memo.setHint("메모");
        memo.setSingleLine(true);
        body.addView(memo, U.margins(U.match(), a, 0, 6, 0, 0));

        s.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView save = U.button(a, "결과 저장", 0, v -> {
            Store.Result r = new Store.Result();
            r.time = System.currentTimeMillis();
            r.unit = unit; r.spec = m.spec; r.setHz = m.setHz;
            r.vRms = last == null ? Double.NaN : last.vRms;
            r.rounds = rounds; r.minutes = minutes;
            r.angle = parse(angle); r.length = parse(len);
            r.rShape = shape[0];
            r.memo = memo.getText().toString().trim();
            Store.addResult(a, r);
            if (m.planActive) TimerCenter.endPlan(a, unit);
            TimerCenter.acknowledge(a, unit);
            a.closeSheet();
            Toast.makeText(a, unit + "호기 가공 결과 저장", Toast.LENGTH_SHORT).show();
        });
        s.addView(save, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 54)), a, 0, 10, 0, 0));
        a.showSheet(s, null);
    }

    private static void cell(Context c, LinearLayout row, String label, String value) {
        LinearLayout b = U.col(c);
        b.setBackground(U.box(c, U.ENAMEL, 5, U.ENAMEL, 0));
        b.setPadding(U.dp(c, 8), U.dp(c, 6), U.dp(c, 8), U.dp(c, 6));
        b.addView(U.tv(c, label, 10.5f, U.SUB, null));
        b.addView(U.tv(c, value, 15, U.INK, U.MONO_B));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(U.dp(c, 3), 0, U.dp(c, 3), 0);
        row.addView(b, p);
    }

    private static EditText num(Context c, String init, boolean decimal) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(init);
        e.setGravity(Gravity.CENTER);
        e.setTypeface(U.MONO_B);
        e.setTextSize(18);
        return e;
    }

    private static TextView step(Context c, String t, View.OnClickListener l) {
        TextView b = U.tv(c, t, 18, U.INK, U.MONO_B);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(c, U.ENAMEL, 6, U.INK, 2));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(U.dp(c, 38), U.dp(c, 38)));
        return b;
    }

    private static void bump(EditText e, double d, int dec) {
        double v = parse(e);
        if (Double.isNaN(v)) v = dec == 0 ? 44 : 0;
        v += d;
        e.setText(String.format(Locale.US, "%." + dec + "f", v));
    }
}
