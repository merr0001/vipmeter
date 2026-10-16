package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 정보 탭: 앱 명판 · 목표 · 기능 · 왜 RMS 진동 속도인가 */
class InfoScreen extends MainActivity.Screen {

    InfoScreen(MainActivity a) { super(a); }

    private String version() {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; }
        catch (Exception e) { return "—"; }
    }

    @Override
    View build() {
        Context c = a;
        String ver = version();
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        sv.addView(col);
        col.addView(a.header("앱 정보", null));

        // ---- 명판 ----
        FrameLayout plate = new FrameLayout(c);
        plate.setBackground(U.box(c, U.PLATE, 9, U.RULE, 1.5f));
        LinearLayout pc = U.col(c);
        pc.setPadding(U.dp(c, 24), U.dp(c, 20), U.dp(c, 24), U.dp(c, 18));
        TextView name = U.tv(c, "진동 속도 계기", 24, U.INK, U.BOLD);
        pc.addView(name);
        TextView model = U.tv(c, "VIBMETER · v" + ver, 12, U.SIGNAL_DK, U.MONO_B);
        model.setLetterSpacing(0.12f);
        model.setPadding(0, U.dp(c, 2), 0, U.dp(c, 12));
        pc.addView(model);
        View rule = new View(c);
        rule.setBackgroundColor(U.RULE);
        pc.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 1)));
        pc.addView(U.gap(c, 8));
        pc.addView(kv(c, "소속", "새한마이크로텍 · W.P 부서 · HG"));
        pc.addView(kv(c, "만든 사람", "박동원"));
        pc.addView(kv(c, "용도", "프로브핀 진동연마 설비 관리"));
        pc.addView(kv(c, "기준 단위", "진동 속도 RMS · mm/s"));
        plate.addView(pc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int[] gv = {Gravity.TOP | Gravity.LEFT, Gravity.TOP | Gravity.RIGHT, Gravity.BOTTOM | Gravity.LEFT, Gravity.BOTTOM | Gravity.RIGHT};
        for (int g : gv) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(U.dp(c, 10), U.dp(c, 10), g);
            lp.setMargins(U.dp(c, 8), U.dp(c, 8), U.dp(c, 8), U.dp(c, 8));
            plate.addView(new Screw(c), lp);
        }
        col.addView(plate, U.margins(U.match(), c, 14, 0, 14, 0));

        // ---- 앱의 목표 ----
        col.addView(section(c, "앱의 목표"));
        LinearLayout goal = U.window(c);
        goal.setPadding(U.dp(c, 16), U.dp(c, 14), U.dp(c, 16), U.dp(c, 14));
        TextView g1 = U.tv(c, "설정 주파수가 아니라 진동 속도로\n모든 호기가 같은 스펙을 같은 결과로", 17, U.PHOS, U.BOLD);
        g1.setLineSpacing(U.dp(c, 3), 1f);
        goal.addView(g1);
        TextView g2 = U.tv(c, "같은 주파수를 넣어도 호기마다 진동 세기가 2배 넘게 다릅니다. 폰으로 진동 속도를 재서 맞추면 설비가 달라도 가공 결과가 같아집니다.", 13, U.PHOS_DIM, null);
        g2.setLineSpacing(U.dp(c, 2), 1f);
        g2.setPadding(0, U.dp(c, 10), 0, 0);
        goal.addView(g2);
        col.addView(goal, U.margins(U.match(), c, 14, 0, 14, 0));

        // ---- 기능 ----
        col.addView(section(c, "기능"));
        String[][] f = {
                {"측정", "폰 가속도 센서로 진동 속도 · 주파수 · 고정 상태"},
                {"상황판", "가공 중 설비 · 남은 시간 · 대기 알림"},
                {"기록", "측정 · 가공 결과 · 날짜별 인수인계"},
                {"가이드", "설비별 스펙 주파수 쉽게 찾기"}};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = U.row(c);
            for (int k = 0; k < 2; k++) {
                String[] it = f[r * 2 + k];
                LinearLayout card = U.panel(c);
                card.setPadding(U.dp(c, 12), U.dp(c, 11), U.dp(c, 12), U.dp(c, 12));
                LinearLayout top = U.row(c);
                top.setGravity(Gravity.CENTER_VERTICAL);
                MainActivity.TabIcon ic = new MainActivity.TabIcon(c, r * 2 + k);
                top.addView(ic, new LinearLayout.LayoutParams(U.dp(c, 46), U.dp(c, 39)));
                TextView t = U.tv(c, it[0], 16, U.INK, U.BOLD);
                t.setPadding(U.dp(c, 8), 0, 0, 0);
                top.addView(t);
                card.addView(top);
                TextView d = U.tv(c, it[1], 12.5f, U.SUB, null);
                d.setLineSpacing(U.dp(c, 1.5f), 1f);
                d.setPadding(0, U.dp(c, 8), 0, 0);
                card.addView(d);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(k == 0 ? 0 : U.dp(c, 5), 0, k == 0 ? U.dp(c, 5) : 0, 0);
                row.addView(card, lp);
            }
            col.addView(row, U.margins(U.match(), c, 14, r == 0 ? 0 : 10, 14, 0));
        }

        // ---- 왜 RMS ----
        col.addView(section(c, "왜 RMS 진동 속도인가"));
        LinearLayout why = U.panel(c);
        why.setPadding(U.dp(c, 14), U.dp(c, 6), U.dp(c, 14), U.dp(c, 14));
        String[][] w = {
                {"주파수가 달라도 비교됩니다", "설비의 주파수가 달라도 RMS를 맞추면 가공 결과는 거의 같습니다."},
                {"가공 에너지를 그대로 나타냅니다", "핀과 연마재가 부딪히는 세기는 속도에 따라 정해집니다. 가속도나 변위보다 가공량과 잘 맞습니다."},
                {"설비 진동의 국제 표준 단위", "ISO 10816 · 20816이 설비 진동을 RMS 속도(mm/s)로 평가합니다. 시중 진동계로도 같은 값을 읽을 수 있습니다."}};
        for (int i = 0; i < 3; i++) {
            LinearLayout row = U.row(c);
            row.setPadding(0, U.dp(c, 10), 0, U.dp(c, 10));
            TextView n = U.tv(c, String.valueOf(i + 1), 14, Color.WHITE, U.MONO_B);
            n.setGravity(Gravity.CENTER);
            n.setBackground(U.box(c, U.SIGNAL, 13, U.SIGNAL, 0));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(U.dp(c, 26), U.dp(c, 26));
            nl.rightMargin = U.dp(c, 12);
            row.addView(n, nl);
            LinearLayout tx = U.col(c);
            tx.addView(U.tv(c, w[i][0], 15, U.INK, U.BOLD));
            TextView d = U.tv(c, w[i][1], 13, U.SUB, null);
            d.setLineSpacing(U.dp(c, 2), 1f);
            d.setPadding(0, U.dp(c, 4), 0, 0);
            tx.addView(d);
            row.addView(tx, U.lpW(1));
            why.addView(row);
            if (i < 2) {
                View ln = new View(c);
                ln.setBackgroundColor(U.RULE);
                why.addView(ln, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 1)));
            }
        }
        LinearLayout fw = U.window(c);
        fw.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView fm = U.tv(c, "v_RMS = 159 × Z_RMS ÷ f", 18, U.PHOS, U.MONO_B);
        fm.setGravity(Gravity.CENTER);
        fw.addView(fm);
        TextView fn = U.tv(c, "Z축 RMS 가속도(m/s²) · 주파수(Hz) · 1000 ÷ 2π = 159", 11.5f, U.PHOS_DIM, null);
        fn.setGravity(Gravity.CENTER);
        fn.setPadding(0, U.dp(c, 6), 0, 0);
        fw.addView(fn);
        why.addView(fw, U.margins(U.match(), c, 0, 6, 0, 0));
        col.addView(why, U.margins(U.match(), c, 14, 0, 14, 0));

        // ---- 바닥 ----
        TextView foot = U.tv(c, "v" + ver + " · 기록은 이 폰에만 저장", 12, U.SUB, U.MONO);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fl = U.margins(U.match(), c, 14, 18, 14, 24);
        col.addView(foot, fl);
        return sv;
    }

    private TextView section(Context c, String t) {
        TextView v = U.sectionTitle(c, t);
        v.setPadding(U.dp(c, 18), U.dp(c, 20), U.dp(c, 18), U.dp(c, 8));
        return v;
    }

    private LinearLayout kv(Context c, String k, String v) {
        LinearLayout r = U.row(c);
        r.setPadding(0, U.dp(c, 5), 0, U.dp(c, 5));
        TextView kt = U.tv(c, k, 13, U.SUB, null);
        r.addView(kt, new LinearLayout.LayoutParams(U.dp(c, 78), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vt = U.tv(c, v, 14.5f, U.INK, U.BOLD);
        r.addView(vt, U.lpW(1));
        return r;
    }

    /** 명판 나사 */
    static class Screw extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Screw(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), r = w / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(0xc4, 0xcb, 0xc2));
            cv.drawCircle(r, r, r - 0.5f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.1f);
            p.setColor(Color.rgb(0x8e, 0x99, 0x8f));
            cv.drawCircle(r, r, r - w * 0.06f, p);
            p.setStrokeWidth(w * 0.14f);
            p.setColor(Color.rgb(0x6c, 0x77, 0x6f));
            cv.drawLine(r * 0.45f, r * 1.55f, r * 1.55f, r * 0.45f, p);
        }
    }
}
