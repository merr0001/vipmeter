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
 * 탭: 측정 · 타이머 · 기록 · 기준
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
    private final Screen[] screens = new Screen[4];
    private final TextView[] tabLabels = new TextView[4];
    private final View[] tabMarks = new View[4];
    private TextView badge;
    private int current = -1;
    private View page;      // 탭 위에 올린 상세 화면
    private TextView banner;
    private final Handler h = new Handler(Looper.getMainLooper());

    MeasureScreen measure;
    TimerScreen timer;
    RecordScreen records;
    SpecScreen specs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(U.ENAMEL);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(U.PLATE);
        Store.seedIfFirstRun(this);
        TimerCenter.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(U.ENAMEL);
        LinearLayout main = U.col(this);
        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        tabBar = buildTabBar();
        main.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 64)));
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
        ol.bottomMargin = U.dp(this, 64);
        root.addView(overlay, ol);

        setContentView(root);

        measure = new MeasureScreen(this);
        timer = new TimerScreen(this);
        records = new RecordScreen(this);
        specs = new SpecScreen(this);
        screens[0] = measure; screens[1] = timer; screens[2] = records; screens[3] = specs;
        showTab(getIntent() != null && getIntent().getBooleanExtra("openTimer", false) ? 1 : Store.getInt(this, "tab", 0));
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
        String[] names = {"측정", "타이머", "기록", "기준"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout c = U.col(this);
            c.setGravity(Gravity.CENTER);
            TabIcon ic = new TabIcon(this, i);
            c.addView(ic, new LinearLayout.LayoutParams(U.dp(this, 24), U.dp(this, 24)));
            TextView t = U.tv(this, names[i], 12, U.SUB, null);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, U.dp(this, 4), 0, 0);
            c.addView(t);
            tabLabels[i] = t;
            cell.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View mark = new View(this);
            mark.setBackgroundColor(U.SIGNAL);
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(U.dp(this, 36), U.dp(this, 3), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cell.addView(mark, ml);
            tabMarks[i] = mark;
            if (i == 1) {
                badge = U.tv(this, "0", 10, Color.WHITE, U.MONO_B);
                badge.setBackground(U.box(this, U.SIGNAL, 8, U.SIGNAL, 0));
                badge.setPadding(U.dp(this, 5), U.dp(this, 1), U.dp(this, 5), U.dp(this, 1));
                FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                b.topMargin = U.dp(this, 6);
                b.leftMargin = U.dp(this, 22);
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
        for (int k = 0; k < 4; k++) {
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

    // ---------------- 탭 아이콘 ----------------
    static class TabIcon extends View {
        final int kind;
        boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        TabIcon(android.content.Context c, int k) { super(c); kind = k; }
        void setOn(boolean b) { on = b; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), s = w / 24f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * s);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(on ? U.INK : U.SUB);
            Path path = new Path();
            if (kind == 0) {
                cv.drawArc(4 * s, 9 * s, 20 * s, 25 * s, 180, 180, false, p);
                cv.drawLine(12 * s, 17 * s, 16 * s, 11 * s, p);
            } else if (kind == 1) {
                cv.drawCircle(12 * s, 13 * s, 8 * s, p);
                cv.drawLine(12 * s, 9 * s, 12 * s, 13 * s, p);
                cv.drawLine(12 * s, 13 * s, 15 * s, 15 * s, p);
                cv.drawLine(9 * s, 2 * s, 15 * s, 2 * s, p);
            } else if (kind == 2) {
                cv.drawRect(5 * s, 4 * s, 19 * s, 20 * s, p);
                cv.drawLine(8 * s, 9 * s, 16 * s, 9 * s, p);
                cv.drawLine(8 * s, 13 * s, 16 * s, 13 * s, p);
                cv.drawLine(8 * s, 17 * s, 13 * s, 17 * s, p);
            } else {
                cv.drawLine(4 * s, 20 * s, 4 * s, 10 * s, p);
                cv.drawLine(10 * s, 20 * s, 10 * s, 4 * s, p);
                cv.drawLine(16 * s, 20 * s, 16 * s, 13 * s, p);
                cv.drawLine(2 * s, 21 * s, 22 * s, 21 * s, p);
            }
        }
    }
}
