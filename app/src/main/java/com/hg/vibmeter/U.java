package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 색 · 글꼴 · 작은 화면 조각 */
public final class U {
    private U() {}

    public static final int ENAMEL = Color.rgb(0xd6, 0xdc, 0xd3);
    public static final int PLATE = Color.rgb(0xe9, 0xed, 0xe6);
    public static final int INK = Color.rgb(0x1d, 0x24, 0x21);
    public static final int SUB = Color.rgb(0x5d, 0x6a, 0x63);
    public static final int RULE = Color.rgb(0xb9, 0xc2, 0xb8);
    public static final int SIGNAL = Color.rgb(0xe2, 0x57, 0x1b);
    public static final int SIGNAL_DK = Color.rgb(0xa6, 0x3c, 0x10);
    public static final int OK = Color.rgb(0x2f, 0x8a, 0x5b);
    public static final int WARN = Color.rgb(0xc8, 0x90, 0x1b);
    public static final int BAD = Color.rgb(0xc2, 0x3b, 0x2b);
    public static final int SCREEN = Color.rgb(0x1f, 0x26, 0x23);
    public static final int PHOS = Color.rgb(0x9f, 0xe0, 0xb8);
    public static final int PHOS_DIM = Color.rgb(0x7f, 0xa8, 0x92);
    public static final int DASH = Color.rgb(0xa9, 0xb3, 0xa8);

    public static final Typeface MONO = Typeface.MONOSPACE;
    public static final Typeface MONO_B = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    public static final Typeface BOLD = Typeface.DEFAULT_BOLD;

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static TextView tv(Context c, CharSequence t, float sp, int color, Typeface tf) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (tf != null) v.setTypeface(tf);
        v.setIncludeFontPadding(false);
        // 기본은 글자 크기만큼만 차지 → 부모의 가운데/오른쪽 정렬이 그대로 적용됨
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    public static GradientDrawable box(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    public static GradientDrawable dashed(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1.2f), stroke, dp(c, 4), dp(c, 3));
        return g;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    public static LinearLayout.LayoutParams lpW(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }
    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, Context c, float l, float t, float r, float b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** 판 모양 카드 */
    public static LinearLayout panel(Context c) {
        LinearLayout l = col(c);
        l.setBackground(box(c, PLATE, 7, RULE, 1));
        l.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        return l;
    }

    /** 어두운 계기 창 */
    public static LinearLayout window(Context c) {
        LinearLayout l = col(c);
        l.setBackground(box(c, SCREEN, 8, SCREEN, 0));
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return l;
    }

    public static TextView sectionTitle(Context c, String t) {
        TextView v = tv(c, t, 12, SUB, BOLD);
        v.setLetterSpacing(0.04f);
        v.setPadding(0, 0, 0, dp(c, 8));
        return v;
    }

    /** 큰 버튼. kind: 0=주황(주요), 1=테두리, 2=검정 */
    public static TextView button(Context c, String text, int kind, View.OnClickListener l) {
        TextView b = tv(c, text, 17, kind == 1 ? INK : (kind == 2 ? PLATE : Color.WHITE), BOLD);
        b.setGravity(Gravity.CENTER);
        if (kind == 0) b.setBackground(box(c, SIGNAL, 8, SIGNAL_DK, 0));
        else if (kind == 1) b.setBackground(box(c, PLATE, 8, INK, 2));
        else b.setBackground(box(c, INK, 8, INK, 0));
        b.setMinHeight(dp(c, 54));
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        if (l != null) b.setOnClickListener(l);
        b.setClickable(true);
        return b;
    }

    /** 작은 태그 (범위 안/낮음/높음) */
    public static TextView tag(Context c, String t, int kind) {
        int bg, fg;
        if (kind == 0) { bg = Color.rgb(0xd3, 0xec, 0xdc); fg = OK; }
        else if (kind == 1) { bg = Color.rgb(0xf6, 0xe6, 0xc4); fg = Color.rgb(0x8a, 0x61, 0x10); }
        else if (kind == 2) { bg = Color.rgb(0xf3, 0xd2, 0xcc); fg = BAD; }
        else { bg = ENAMEL; fg = SUB; }
        TextView v = tv(c, t, 10.5f, fg, BOLD);
        v.setBackground(box(c, bg, 3, bg, 0));
        v.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        return v;
    }

    /** 목표 대비 판정: 0 범위 안, 1 낮음, 2 높음, -1 목표 없음 */
    public static int judge(Store.Spec s, double v) {
        if (s == null || Double.isNaN(v) || Double.isNaN(s.target)) return -1;
        if (Math.abs(v - s.target) <= s.tol) return 0;
        return v < s.target ? 1 : 2;
    }

    public static String judgeText(int j) {
        return j == 0 ? "범위 안" : j == 1 ? "낮음" : j == 2 ? "높음" : "목표 없음";
    }

    public static String f1(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f", v); }
    public static String f2(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.2f", v); }
    public static String f3(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.3f", v); }

    public static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public static String time(long t, String fmt) { return new SimpleDateFormat(fmt, Locale.KOREA).format(new Date(t)); }

    public static View gap(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }
}
