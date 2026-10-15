package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 수정 창 공용 입력 칸 (바꾼 칸은 주황 테두리) */
class Form {
    final Context c;
    final LinearLayout group;

    Form(Context c) {
        this.c = c;
        group = U.col(c);
        group.setBackground(U.box(c, U.PLATE, 7, U.RULE, 1));
        group.setPadding(U.dp(c, 12), U.dp(c, 2), U.dp(c, 12), U.dp(c, 2));
    }

    /** 왼쪽 이름 + 오른쪽 입력 */
    void row(String label, View field) {
        if (group.getChildCount() > 0) {
            View ln = new View(c);
            ln.setBackgroundColor(Color.rgb(0xdd, 0xe3, 0xdb));
            group.addView(ln, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout r = U.row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(U.dp(c, 50));
        r.setPadding(0, U.dp(c, 6), 0, U.dp(c, 6));
        r.addView(U.tv(c, label, 13, U.SUB, null), new LinearLayout.LayoutParams(U.dp(c, 84), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        group.addView(r, U.match());
    }

    /** 두 칸 나란히 */
    LinearLayout pair(View a, View b) {
        LinearLayout r = U.row(c);
        r.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = U.dp(c, 6);
        r.addView(b, p);
        return r;
    }

    static void mark(Context c, View v, boolean changed) {
        v.setBackground(U.box(c, Color.WHITE, 6, changed ? U.SIGNAL : U.DASH, changed ? 2 : 1));
    }

    /** 입력 칸. kind 0=글자, 1=숫자(소수) */
    EditText input(String text, int kind, String hint) {
        EditText e = new EditText(c);
        e.setText(text);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(U.INK);
        e.setTypeface(kind == 1 ? U.MONO_B : null);
        if (kind == 1) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (hint != null) e.setHint(hint);
        e.setPadding(U.dp(c, 10), U.dp(c, 7), U.dp(c, 10), U.dp(c, 7));
        final String orig = text;
        mark(c, e, false);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) { mark(c, e, !s.toString().equals(orig)); }
        });
        return e;
    }

    static String num(double v, int dec) {
        if (Double.isNaN(v)) return "";
        String s = String.format(Locale.US, "%." + dec + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static double parse(EditText e) {
        String s = e.getText().toString().trim().replace(",", ".");
        if (s.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s); } catch (Exception ex) { return Double.NaN; }
    }

    /** − 값 + */
    static final class Stepper {
        int value;
        TextView label;
        Runnable onChange;
    }

    Stepper stepper(int value, int min, int max, int step, String unit) {
        Stepper st = new Stepper();
        st.value = value;
        final int orig = value;
        LinearLayout r = U.row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = small("−");
        TextView plus = small("+");
        st.label = U.tv(c, "", 17, U.INK, U.MONO_B);
        st.label.setGravity(Gravity.CENTER);
        Runnable upd = () -> {
            st.label.setText(st.value + unit);
            st.label.setTextColor(st.value != orig ? U.SIGNAL : U.INK);
            if (st.onChange != null) st.onChange.run();
        };
        minus.setOnClickListener(v -> { st.value = Math.max(min, st.value - step); upd.run(); });
        plus.setOnClickListener(v -> { st.value = Math.min(max, st.value + step); upd.run(); });
        r.addView(minus, new LinearLayout.LayoutParams(U.dp(c, 38), U.dp(c, 38)));
        r.addView(st.label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(plus, new LinearLayout.LayoutParams(U.dp(c, 38), U.dp(c, 38)));
        st.label.setTag(r);
        upd.run();
        return st;
    }

    TextView small(String t) {
        TextView b = U.tv(c, t, 18, U.INK, U.MONO_B);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(c, U.ENAMEL, 6, U.DASH, 1));
        b.setClickable(true);
        return b;
    }

    /** 고르기 칸 (누르면 목록) */
    static final class Picker {
        String value;
        TextView view;
    }

    Picker picker(String value, String title, List<String> options) {
        Picker pk = new Picker();
        pk.value = value;
        final String orig = value;
        TextView v = U.tv(c, "", 15, U.INK, U.MONO_B);
        v.setPadding(U.dp(c, 10), U.dp(c, 9), U.dp(c, 10), U.dp(c, 9));
        pk.view = v;
        Runnable upd = () -> {
            v.setText((pk.value == null || pk.value.isEmpty() ? "없음" : pk.value) + "  ▾");
            mark(c, v, !String.valueOf(pk.value).equals(String.valueOf(orig)));
        };
        upd.run();
        v.setOnClickListener(x -> {
            List<String> opts = new ArrayList<>(options);
            if (pk.value != null && !pk.value.isEmpty() && !opts.contains(pk.value)) opts.add(0, pk.value);
            String[] arr = opts.toArray(new String[0]);
            int sel = opts.indexOf(pk.value);
            new android.app.AlertDialog.Builder(c).setTitle(title)
                    .setSingleChoiceItems(arr, sel, (d, w) -> { pk.value = arr[w]; upd.run(); d.dismiss(); })
                    .setNegativeButton("취소", null).show();
        });
        return pk;
    }

    /** 날짜·시간 칸 */
    static final class When { long time; TextView view; }

    When when(long time) {
        When w = new When();
        w.time = time;
        final long orig = time;
        TextView v = U.tv(c, "", 15, U.INK, U.MONO_B);
        v.setPadding(U.dp(c, 10), U.dp(c, 9), U.dp(c, 10), U.dp(c, 9));
        w.view = v;
        Runnable upd = () -> {
            v.setText(U.time(w.time, "yyyy-MM-dd HH:mm"));
            mark(c, v, w.time / 60000 != orig / 60000);
        };
        upd.run();
        v.setOnClickListener(x -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(w.time);
            new android.app.DatePickerDialog(c, (dp, y, m, d) -> {
                cal.set(y, m, d);
                new android.app.TimePickerDialog(c, (tp, h, mi) -> {
                    cal.set(Calendar.HOUR_OF_DAY, h);
                    cal.set(Calendar.MINUTE, mi);
                    w.time = cal.getTimeInMillis();
                    upd.run();
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });
        return w;
    }

    /** 여러 개 중 하나 (다시 누르면 해제 allowNone) */
    static final class Seg { int sel; TextView[] items; Runnable onChange; }

    Seg seg(String[] opts, int sel, boolean allowNone) {
        Seg s = new Seg();
        s.sel = sel;
        final int orig = sel;
        LinearLayout r = U.row(c);
        s.items = new TextView[opts.length];
        Runnable[] upd = new Runnable[1];
        upd[0] = () -> {
            for (int j = 0; j < opts.length; j++) {
                boolean on = j == s.sel;
                s.items[j].setBackground(U.box(c, on ? U.INK : U.ENAMEL, 6, on ? U.INK : U.DASH, 1));
                s.items[j].setTextColor(on ? U.PLATE : U.INK);
            }
            r.setBackground(s.sel != orig ? U.box(c, Color.TRANSPARENT, 8, U.SIGNAL, 2) : null);
        };
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            TextView t = U.tv(c, opts[i], 13.5f, U.INK, U.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> { s.sel = (allowNone && s.sel == k) ? -1 : k; upd[0].run(); if (s.onChange != null) s.onChange.run(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.dp(c, 36), 1);
            if (i > 0) p.leftMargin = U.dp(c, 4);
            r.addView(t, p);
            s.items[i] = t;
        }
        r.setPadding(U.dp(c, 2), U.dp(c, 2), U.dp(c, 2), U.dp(c, 2));
        s.items[0].setTag(r);
        upd[0].run();
        return s;
    }

    static View viewOf(Seg s) { return (View) s.items[0].getTag(); }
    static View viewOf(Stepper s) { return (View) s.label.getTag(); }

    static List<String> specNames(Context c) {
        List<String> l = new ArrayList<>();
        for (Store.Spec s : Store.specs(c)) l.add(s.name);
        return l;
    }
}
