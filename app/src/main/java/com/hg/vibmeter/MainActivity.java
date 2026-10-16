package com.hg.vibmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 진동 속도 계기 v2.0 (작성 H.G 박동원)
 * 탭: 측정 · 타이머 · 기록 · 가이드 · 정보
 */
public class MainActivity extends Activity {

    public abstract static class Screen {
        protected final MainActivity a;
        protected View root;
        Screen(MainActivity a) { this.a = a; }
        abstract View build();
        View view() { if (root == null) root = build(); return root; }
        void onShow() {}
        void onHide() {}
        void tick() {}
    }

    private FrameLayout content, overlay;
    private LinearLayout tabBar;
    private final Screen[] screens = new Screen[5];
    private final TextView[] tabLabels = new TextView[5];
    private final View[] tabMarks = new View[5];
    private TextView badge;
    private int current = -1;
    private View page;      // 탭 위에 올린 상세 화면
    private TextView banner;
    private final Handler h = new Handler(Looper.getMainLooper());

    MeasureScreen measure;
    TimerScreen timer;
    RecordScreen records;
    SpecScreen specs;
    InfoScreen info;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(U.ENAMEL);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(U.PLATE);
        Store.seedIfFirstRun(this);
        Store.cleanOutOfRangeOnce(this);
        TimerCenter.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(U.ENAMEL);
        LinearLayout main = U.col(this);
        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        tabBar = buildTabBar();
        main.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 74)));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        banner = U.tv(this, "", 15, Color.WHITE, U.BOLD);
        banner.setBackground(U.box(this, U.SIGNAL, 8, U.SIGNAL, 0));
        banner.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 12));
        banner.setVisibility(View.GONE);
        banner.setElevation(U.dp(this, 8));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        bl.setMargins(U.dp(this, 12), U.dp(this, 8), U.dp(this, 12), 0);
        banner.setOnClickListener(v -> { banner.setVisibility(View.GONE); showTab(1); });
        root.addView(banner, bl);

        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setBackgroundColor(Color.argb(110, 20, 30, 25));
        overlay.setOnClickListener(v -> closeSheet());
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ol.bottomMargin = U.dp(this, 74);
        root.addView(overlay, ol);

        setContentView(root);

        measure = new MeasureScreen(this);
        timer = new TimerScreen(this);
        records = new RecordScreen(this);
        specs = new SpecScreen(this);
        info = new InfoScreen(this);
        screens[0] = measure; screens[1] = timer; screens[2] = records; screens[3] = specs; screens[4] = info;
        showTab(getIntent() != null && getIntent().getBooleanExtra("openTimer", false) ? 1 : Math.min(4, Math.max(0, Store.getInt(this, "tab", 0))));
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("openTimer", false)) showTab(1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
        if (current >= 0 && page == null) screens[current].onShow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        measure.stopMeasuring();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            checkTimers();
            if (current >= 0 && page == null) screens[current].tick();
            if (pageTick != null && page != null) pageTick.run();
            h.postDelayed(this, 500);
        }
    };
    Runnable pageTick;

    /** 알람이 늦게 와도 화면에서 바로 완료 처리 */
    private void checkTimers() {
        long now = System.currentTimeMillis();
        int running = 0;
        for (Store.Machine m : Store.machines(this)) {
            if (m.endAt > 0 && now >= m.endAt) {
                String msg = TimerCenter.finish(this, m.unit, m.endAt);
                if (msg != null) showBanner(msg);
            } else if (m.endAt > now) running++;
        }
        badge.setText(String.valueOf(running));
        badge.setVisibility(running > 0 ? View.VISIBLE : View.GONE);
    }

    void showBanner(String msg) {
        banner.setText(msg + "   ›");
        banner.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideBanner);
        h.postDelayed(hideBanner, 8000);
    }
    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);

    // ---------------- 탭 ----------------
    private LinearLayout buildTabBar() {
        LinearLayout bar = U.row(this);
        bar.setBackgroundColor(U.PLATE);
        String[] names = {"측정", "상황판", "기록", "가이드", "정보"};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout c = U.col(this);
            c.setGravity(Gravity.CENTER);
            TabIcon ic = new TabIcon(this, i);
            c.addView(ic, new LinearLayout.LayoutParams(U.dp(this, 46), U.dp(this, 39)));
            TextView t = U.tv(this, names[i], 12, U.SUB, null);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, U.dp(this, 3), 0, 0);
            c.addView(t);
            tabLabels[i] = t;
            cell.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View mark = new View(this);
            mark.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(U.dp(this, 36), U.dp(this, 3), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cell.addView(mark, ml);
            tabMarks[i] = mark;
            if (i == 1) {
                badge = U.tv(this, "0", 10, Color.WHITE, U.MONO_B);
                badge.setBackground(U.box(this, U.SIGNAL, 8, Color.WHITE, 1.5f));
                badge.setElevation(U.dp(this, 2));
                badge.setPadding(U.dp(this, 5), U.dp(this, 1), U.dp(this, 5), U.dp(this, 1));
                FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                b.topMargin = U.dp(this, 3);
                b.leftMargin = U.dp(this, 30);
                cell.addView(badge, b);
            }
            cell.setOnClickListener(v -> showTab(idx));
            bar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return bar;
    }

    void showTab(int i) {
        closeSheet();
        if (page != null) { content.removeView(page); page = null; pageTick = null; }
        if (current >= 0 && current != i) screens[current].onHide();
        current = i;
        Store.putInt(this, "tab", i);
        content.removeAllViews();
        View v = screens[i].view();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int k = 0; k < 5; k++) {
            tabLabels[k].setTextColor(k == i ? U.INK : U.SUB);
            tabLabels[k].setTypeface(k == i ? U.BOLD : null);
            tabMarks[k].setVisibility(k == i ? View.VISIBLE : View.INVISIBLE);
            ((TabIcon) ((LinearLayout) ((FrameLayout) tabBar.getChildAt(k)).getChildAt(0)).getChildAt(0)).setOn(k == i);
        }
        screens[i].onShow();
    }

    /** 탭 위에 상세 화면 올리기 (뒤로 가기로 닫힘) */
    void pushPage(View v, Runnable onTick) {
        closeSheet();
        if (page != null) content.removeView(page);
        page = v;
        pageTick = onTick;
        v.setBackgroundColor(U.ENAMEL);
        v.setClickable(true);
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void popPage() {
        if (page == null) return;
        content.removeView(page);
        page = null;
        pageTick = null;
        if (current >= 0) screens[current].onShow();
    }

    // ---------------- 아래에서 올라오는 시트 ----------------
    private Runnable sheetClosed;

    void showSheet(View sheet, Runnable onClose) {
        overlay.removeAllViews();
        sheet.setClickable(true);
        sheet.setBackground(U.box(this, U.PLATE, 14, U.INK, 0));
        sheet.setElevation(U.dp(this, 10));
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        overlay.addView(sheet, l);
        overlay.setVisibility(View.VISIBLE);
        sheetClosed = onClose;
    }

    boolean sheetOpen() { return overlay.getVisibility() == View.VISIBLE; }

    void closeSheet() {
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) return;
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
        Runnable r = sheetClosed;
        sheetClosed = null;
        if (r != null) r.run();
    }

    /** 시트 위쪽 손잡이 + 제목 */
    LinearLayout sheetBox(String title, String sub) {
        LinearLayout s = U.col(this);
        s.setPadding(U.dp(this, 14), U.dp(this, 10), U.dp(this, 14), U.dp(this, 16));
        View grab = new View(this);
        grab.setBackground(U.box(this, U.RULE, 2, U.RULE, 0));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(U.dp(this, 40), U.dp(this, 4));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.bottomMargin = U.dp(this, 10);
        s.addView(grab, gl);
        LinearLayout hd = U.row(this);
        TextView t = U.tv(this, title, 20, U.INK, U.BOLD);
        hd.addView(t, U.lpW(1));
        if (sub != null) hd.addView(U.tv(this, sub, 12, U.SUB, null));
        s.addView(hd, U.margins(U.match(), this, 0, 0, 0, 10));
        return s;
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen()) { closeSheet(); return; }
        if (page != null) { popPage(); return; }
        if (current == 1 && timer.multi) { timer.exitMulti(); return; }
        super.onBackPressed();
    }

    /** 화면 위쪽 머리말 */
    LinearLayout header(String title, String sub) {
        LinearLayout hd = U.row(this);
        hd.setGravity(Gravity.BOTTOM);
        hd.setPadding(U.dp(this, 18), U.dp(this, 14), U.dp(this, 18), U.dp(this, 10));
        TextView t = U.tv(this, title, 26, U.INK, U.BOLD);
        hd.addView(t, U.lpW(1));
        if (sub != null) hd.addView(U.tv(this, sub, 13, U.SUB, null));
        return hd;
    }

    /** 상세 화면 뒤로 가기 줄 */
    TextView backRow(String text) {
        TextView b = U.tv(this, "‹ " + text, 14, U.SUB, null);
        b.setPadding(U.dp(this, 16), U.dp(this, 12), U.dp(this, 16), U.dp(this, 4));
        b.setOnClickListener(v -> popPage());
        return b;
    }

    List<Store.Spec> specList() { return Store.specs(this); }

    // ---------------- 탭 아이콘 (버튼 캡형) ----------------
    static class TabIcon extends View {
        final int kind;
        boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        TabIcon(android.content.Context c, int k) { super(c); kind = k; }
        void setOn(boolean b) { on = b; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float W = getWidth(), cw = 44 * d, ch = 36 * d, r = 9 * d;
            float L = (W - cw) / 2f, T = on ? 1.5f * d : 0;
            android.graphics.RectF cap = new android.graphics.RectF(L, T, L + cw, T + ch);
            // 아래 그림자 (볼록함)
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.rgb(0x7a, 0x2c, 0x0b) : Color.rgb(0x9a, 0xa4, 0x9a));
            cv.drawRoundRect(new android.graphics.RectF(L, T + (on ? 1 : 2) * d, L + cw, T + ch + (on ? 1 : 2) * d), r, r, p);
            // 캡 면
            p.setShader(new android.graphics.LinearGradient(0, T, 0, T + ch,
                    on ? Color.rgb(0xef, 0x6a, 0x2e) : Color.rgb(0xf3, 0xf5, 0xf1),
                    on ? Color.rgb(0xd8, 0x4f, 0x14) : Color.rgb(0xd9, 0xdf, 0xd6),
                    android.graphics.Shader.TileMode.CLAMP));
            cv.drawRoundRect(cap, r, r, p);
            p.setShader(null);
            if (on) { // 눌린 안쪽 그림자
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(Color.argb(55, 0, 0, 0));
                cv.drawRoundRect(new android.graphics.RectF(L + d, T + d, L + cw - d, T + ch - d), r - d, r - d, p);
            } else { // 위 하이라이트
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(d);
                p.setColor(Color.WHITE);
                cv.drawLine(L + r, T + 1.5f * d, L + cw - r, T + 1.5f * d, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(on ? U.SIGNAL_DK : Color.rgb(0xa9, 0xb3, 0xa8));
            cv.drawRoundRect(new android.graphics.RectF(L + d / 2, T + d / 2, L + cw - d / 2, T + ch - d / 2), r, r, p);

            // 아이콘 24dp (viewBox 32)
            float s = 24 * d / 32f;
            cv.save();
            cv.translate(L + (cw - 24 * d) / 2f, T + (ch - 24 * d) / 2f);
            cv.scale(s, s);
            int ink = on ? Color.WHITE : U.INK;
            int sig = on ? Color.rgb(0xff, 0xe3, 0xd4) : U.SIGNAL;
            int ok = on ? Color.rgb(0xff, 0xe3, 0xd4) : U.OK;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            if (kind == 0) {
                stroke(ink, 2.4f);
                cv.drawArc(5, 11, 27, 33, 180, 180, false, p);
                p.setStrokeCap(Paint.Cap.BUTT);
                stroke(ok, 3.2f);
                cv.drawArc(5, 11, 27, 33, -66, 40.5f, false, p);
                p.setStrokeCap(Paint.Cap.ROUND);
                stroke(ink, 1.6f);
                cv.drawLine(8, 21.5f, 9.6f, 21.5f, p);
                cv.drawLine(16, 11.6f, 16, 13.2f, p);
                cv.drawLine(23.4f, 21.5f, 25, 21.5f, p);
                cv.drawLine(10.3f, 14.6f, 11.4f, 15.7f, p);
                stroke(sig, 2.6f);
                cv.drawLine(16, 22, 21, 15, p);
                fill(ink); cv.drawCircle(16, 22, 2.2f, p);
                stroke(ink, 1.4f); p.setAlpha(180);
                Path w = new Path(); w.moveTo(5, 27.5f);
                for (int i = 0; i < 6; i++) {
                    float x = 5 + i * 3.6f, dy = (i % 2 == 0) ? -1.6f : 1.6f;
                    w.cubicTo(x + 1.2f, 27.5f + dy, x + 2.4f, 27.5f + dy, x + 3.6f, 27.5f);
                }
                cv.drawPath(w, p);
            } else if (kind == 1) {
                stroke(ink, 2.4f); cv.drawCircle(16, 18, 10, p);
                stroke(sig, 3.2f); cv.drawArc(6, 8, 26, 28, -90, 110, false, p);
                stroke(ink, 2.2f);
                cv.drawLine(13, 3.5f, 19, 3.5f, p);
                cv.drawLine(16, 3.5f, 16, 8, p);
                cv.drawLine(24.5f, 8.5f, 26.3f, 6.7f, p);
                cv.drawLine(16, 18, 16, 12.5f, p);
                fill(ink); cv.drawCircle(16, 18, 1.8f, p);
            } else if (kind == 2) {
                stroke(ink, 2.4f); cv.drawRoundRect(6, 5.5f, 26, 28.5f, 2.5f, 2.5f, p);
                fill(ink); cv.drawRoundRect(11.5f, 3, 20.5f, 8, 1.4f, 1.4f, p);
                stroke(ink, 2);
                cv.drawLine(10.5f, 14, 17.5f, 14, p);
                cv.drawLine(10.5f, 19, 17.5f, 19, p);
                cv.drawLine(10.5f, 24, 15.5f, 24, p);
                stroke(ok, 2);
                for (float y : new float[]{0, 5}) {
                    Path c = new Path();
                    c.moveTo(19.5f, 13.8f + y); c.lineTo(21, 15.3f + y); c.lineTo(23.8f, 12.3f + y);
                    cv.drawPath(c, p);
                }
            } else if (kind == 3) {
                fill(ok); p.setAlpha(on ? 90 : 72);
                cv.drawRoundRect(13, 6, 21, 26, 1.5f, 1.5f, p);
                p.setStrokeCap(Paint.Cap.BUTT);
                stroke(ok, 1.6f);
                cv.drawLine(13, 6, 13, 26, p); cv.drawLine(21, 6, 21, 26, p);
                p.setStrokeCap(Paint.Cap.ROUND);
                stroke(ink, 2.4f); cv.drawLine(4, 26, 28, 26, p);
                stroke(ink, 1.8f);
                float[] xs = {6, 11, 16, 21, 26}; float[] hs = {3, 5, 3, 5, 3};
                for (int i = 0; i < 5; i++) cv.drawLine(xs[i], 26, xs[i], 26 - hs[i], p);
                fill(ink); cv.drawCircle(15.5f, 13, 3, p);
                fill(sig); cv.drawCircle(24.5f, 10, 3, p);
            } else {
                stroke(ink, 2.4f); cv.drawRoundRect(3.5f, 6, 28.5f, 26, 2.5f, 2.5f, p);
                fill(ink);
                cv.drawCircle(7.3f, 9.8f, 1.2f, p); cv.drawCircle(24.7f, 9.8f, 1.2f, p);
                cv.drawCircle(7.3f, 22.2f, 1.2f, p); cv.drawCircle(24.7f, 22.2f, 1.2f, p);
                fill(sig); cv.drawCircle(16, 11, 1.7f, p);
                stroke(ink, 2.6f); cv.drawLine(16, 15, 16, 22, p);
            }
            cv.restore();
        }
        private void stroke(int col, float w) { p.setStyle(Paint.Style.STROKE); p.setColor(col); p.setStrokeWidth(w); }
        private void fill(int col) { p.setStyle(Paint.Style.FILL); p.setColor(col); }
    }
}
