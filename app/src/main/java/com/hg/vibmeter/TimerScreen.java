package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 상황판: 가공 중인 설비를 한눈에 (가공 · 대기 · 멈춤 · 완료), 탭=상세, 꾹=다음 회차 바로 시작 */
class TimerScreen extends MainActivity.Screen {

    static final int[] PRESETS = {1, 3, 5, 10, 20, 30, 60};
    static final int IDLE = 0, RUN = 1, WAIT = 2, PAUSE = 3, DONE = 4;

    boolean multi;
    private final Set<Integer> sel = new LinkedHashSet<>();
    private final Card[] cards = new Card[Store.UNITS + 1];
    private final TextView[] idleChips = new TextView[Store.UNITS + 1];
    private TextView clock, sRun, sWait, sPause, sDone, selBtn, hint, idleTitle;
    private GridLayout board, idleGrid;
    private LinearLayout queue, queueRow, idleBox;
    private String order = "", idleOrder = "";
    private TextView undo;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

    TimerScreen(MainActivity a) { super(a); }

    static int state(Store.Machine m, long now) {
        if (m.paused()) return PAUSE;
        if (m.endAt > now) return RUN;
        if (m.planActive && !m.planDone()) return WAIT;
        if (m.planActive && m.planDone()) return DONE;
        if (m.finished) return DONE;
        return IDLE;
    }

    // ---------------- 카드 ----------------
    class Card extends LinearLayout {
        final int unit;
        final TextView num, spec, kind, badge, lab, time, rms, cnt;
        final Bar bar;
        final View check;
        Card(Context c, int unit) {
            super(c);
            this.unit = unit;
            setOrientation(VERTICAL);
            setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 9));
            LinearLayout t1 = U.row(c);
            t1.setGravity(Gravity.CENTER_VERTICAL);
            num = U.tv(c, unit + "", 20, Color.WHITE, U.BOLD);
            t1.addView(num);
            TextView ho = U.tv(c, "호기", 10, U.PHOS_DIM, null);
            ho.setPadding(U.dp(c, 1), U.dp(c, 5), 0, 0);
            t1.addView(ho);
            t1.addView(new View(c), U.lpW(1));
            LinearLayout sp = U.row(c);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            sp.setBackground(U.box(c, U.PLATE, 4, U.PLATE, 0));
            sp.setPadding(U.dp(c, 5), U.dp(c, 1), U.dp(c, 2), U.dp(c, 1));
            spec = U.tv(c, "", 11.5f, U.INK, U.MONO_B);
            sp.addView(spec);
            kind = U.tv(c, "", 10.5f, Color.WHITE, U.MONO_B);
            kind.setPadding(U.dp(c, 4), 0, U.dp(c, 4), 0);
            sp.addView(kind, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 4, 0, 0, 0));
            t1.addView(sp);
            check = new View(c);
            t1.addView(check, U.margins(new LinearLayout.LayoutParams(U.dp(c, 20), U.dp(c, 20)), c, 5, 0, 0, 0));
            addView(t1, U.match());
            LinearLayout t2 = U.row(c);
            t2.setGravity(Gravity.CENTER_VERTICAL);
            t2.setPadding(0, U.dp(c, 6), 0, 0);
            badge = U.tv(c, "", 10.5f, U.SCREEN, U.BOLD);
            badge.setPadding(U.dp(c, 6), U.dp(c, 1), U.dp(c, 6), U.dp(c, 1));
            t2.addView(badge);
            lab = U.tv(c, "", 10.5f, U.PHOS_DIM, U.BOLD);
            lab.setPadding(U.dp(c, 5), 0, 0, 0);
            lab.setSingleLine(true);
            t2.addView(lab, U.lpW(1));
            addView(t2, U.match());
            LinearLayout t3 = U.row(c);
            t3.setGravity(Gravity.BOTTOM);
            time = U.tv(c, "", 29, U.PHOS, U.MONO_B);
            time.setSingleLine(true);
            t3.addView(time, U.lpW(1));
            rms = U.tv(c, "", 10.5f, U.PHOS_DIM, U.MONO);
            rms.setPadding(0, 0, 0, U.dp(c, 5));
            t3.addView(rms);
            addView(t3, U.match());
            LinearLayout t4 = U.row(c);
            t4.setGravity(Gravity.CENTER_VERTICAL);
            bar = new Bar(c);
            t4.addView(bar, new LinearLayout.LayoutParams(0, U.dp(c, 7), 1));
            cnt = U.tv(c, "", 10.5f, U.PHOS_DIM, U.MONO_B);
            cnt.setPadding(U.dp(c, 6), 0, 0, 0);
            t4.addView(cnt);
            addView(t4, U.margins(U.match(), c, 0, 4, 0, 0));
            setOnClickListener(v -> onTap(unit));
            setOnLongClickListener(v -> { onLong(unit); return true; });
        }

        void render(Store.Machine m, long now, int st) {
            Context c = getContext();
            String[] sk = Kind.split(m.spec);
            spec.setText(sk[0].isEmpty() ? "스펙 없음" : sk[0]);
            if (sk[1].isEmpty()) kind.setVisibility(GONE);
            else {
                kind.setVisibility(VISIBLE);
                kind.setText(sk[1]);
                kind.setBackground(U.box(c, Kind.colors(sk[1])[0], 3, Kind.colors(sk[1])[0], 0));
            }
            int tot = Math.max(1, Math.min(10, m.planActive ? m.planTotal() : 1));
            bar.total = tot;
            bar.done = m.planActive ? Math.min(m.doneRounds, tot) : 0;
            bar.current = st == RUN || st == PAUSE;
            int shown = m.planActive ? Math.min(tot, m.doneRounds + (st == RUN || st == PAUSE ? 1 : 0)) : 0;
            cnt.setText(m.planActive ? Math.max(shown, m.doneRounds) + "/" + m.planTotal() + "회" : "");
            Store.Record rec = Store.lastRecord(recs, m.unit, m.spec.isEmpty() ? null : m.spec);
            if (rec != null) {
                int j = U.judge(Store.spec(a, m.spec), rec.vRms);
                rms.setText(U.f1(rec.vRms) + (j == 0 ? " ✓" : j > 0 ? " !" : ""));
            } else rms.setText("");
            int fg = Color.WHITE, sub = U.PHOS_DIM;
            if (st == WAIT) {
                setBackground(U.box(c, U.SIGNAL, 9, U.SIGNAL_DK, 1));
                badge.setText("대기");
                badge.setBackground(U.box(c, Color.WHITE, 3, Color.WHITE, 0));
                badge.setTextColor(U.SIGNAL);
                long since = m.lastEnd > 0 ? now - m.lastEnd : 0;
                lab.setText(m.doneRounds + "회 끝 · 꾹 = 다음 회차");
                time.setText(m.lastEnd > 0 ? U.mmss(since) + " 지남" : "다음 회차");
                time.setTextSize(22);
                time.setTextColor(Color.WHITE);
                sub = Color.rgb(0xff, 0xe3, 0xd4);
                bar.on = Color.WHITE; bar.cur = Color.WHITE; bar.off = Color.argb(80, 255, 255, 255);
            } else if (st == RUN) {
                setBackground(U.box(c, U.SCREEN, 9, U.SCREEN, 0));
                badge.setText("가공");
                badge.setBackground(U.box(c, U.PHOS, 3, U.PHOS, 0));
                badge.setTextColor(U.SCREEN);
                boolean last = m.planActive && m.doneRounds + 1 >= m.planTotal();
                lab.setText(last ? "남은 시간 · 마지막" : "남은 시간");
                time.setText(U.mmss(m.endAt - now));
                time.setTextSize(29);
                time.setTextColor(U.PHOS);
                bar.on = U.PHOS_DIM; bar.cur = U.PHOS; bar.off = Color.rgb(0x3a, 0x47, 0x41);
            } else if (st == PAUSE) {
                setBackground(U.box(c, U.SCREEN, 9, Color.rgb(0xe8, 0xc0, 0x6a), 3));
                badge.setText("멈춤");
                badge.setBackground(U.box(c, Color.rgb(0xe8, 0xc0, 0x6a), 3, Color.rgb(0xe8, 0xc0, 0x6a), 0));
                badge.setTextColor(U.SCREEN);
                lab.setText("남은 시간");
                time.setText(U.mmss(m.pausedLeft));
                time.setTextSize(29);
                time.setTextColor(Color.rgb(0xe8, 0xc0, 0x6a));
                bar.on = U.PHOS_DIM; bar.cur = Color.rgb(0xe8, 0xc0, 0x6a); bar.off = Color.rgb(0x3a, 0x47, 0x41);
            } else {
                setBackground(U.box(c, U.PLATE, 9, U.OK, 2));
                fg = U.INK; sub = U.SUB;
                badge.setText("완료");
                badge.setBackground(U.box(c, U.OK, 3, U.OK, 0));
                badge.setTextColor(Color.WHITE);
                lab.setText(m.planActive ? (m.doneRounds * m.roundMin) + "분 · 눌러서 결과 입력" : m.durMin + "분 타이머 완료");
                time.setText("작업 완료");
                time.setTextSize(21);
                time.setTextColor(U.OK);
                bar.on = U.OK; bar.cur = U.OK; bar.off = U.RULE;
            }
            num.setTextColor(fg);
            ((TextView) ((LinearLayout) num.getParent()).getChildAt(1)).setTextColor(sub);
            lab.setTextColor(sub);
            rms.setTextColor(sub);
            cnt.setTextColor(sub);
            bar.invalidate();
            boolean s = multi && sel.contains(unit);
            check.setVisibility(multi ? VISIBLE : GONE);
            check.setBackground(s ? U.box(c, U.OK, 10, Color.WHITE, 2) : U.box(c, Color.TRANSPARENT, 10, sub, 2));
            setForeground(s ? U.box(c, Color.TRANSPARENT, 9, U.OK, 4) : null);
        }
    }

    /** 회차 막대 (최대 10칸) */
    static class Bar extends View {
        int total = 5, done; boolean current;
        int on = U.PHOS_DIM, cur = U.PHOS, off = Color.rgb(0x3a, 0x47, 0x41);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bar(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight(), d = getResources().getDisplayMetrics().density;
            float g = 2.5f * d, w = (W - g * 9) / 10f;
            for (int i = 0; i < 10; i++) {
                float x = i * (w + g);
                RectF r = new RectF(x, 0, x + w, H);
                if (i >= total) {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d); p.setColor(off);
                    r.inset(d / 2, H * 0.25f);
                    cv.drawRoundRect(r, d, d, p);
                    continue;
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(i < done ? on : (i == done && current ? cur : off));
                cv.drawRoundRect(r, 1.5f * d, 1.5f * d, p);
            }
        }
    }

    private List<Store.Record> recs = new ArrayList<>();

    @Override
    View build() {
        Context c = a;
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        sv.addView(col);
        LinearLayout hd = U.row(c);
        hd.setGravity(Gravity.BOTTOM);
        hd.setPadding(U.dp(c, 18), U.dp(c, 14), U.dp(c, 18), U.dp(c, 8));
        hd.addView(U.tv(c, "상황판", 26, U.INK, U.BOLD), U.lpW(1));
        clock = U.tv(c, "", 13, U.SUB, U.MONO_B);
        hd.addView(clock);
        col.addView(hd, U.match());

        LinearLayout sum = U.row(c);
        sum.setGravity(Gravity.CENTER_VERTICAL);
        sum.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 8));
        sRun = chip(sum, U.SCREEN, U.PHOS);
        sWait = chip(sum, U.SIGNAL, Color.WHITE);
        sPause = chip(sum, Color.rgb(0xe8, 0xc0, 0x6a), U.SCREEN);
        sDone = chip(sum, U.OK, Color.WHITE);
        sum.addView(new View(c), U.lpW(1));
        selBtn = U.tv(c, "☐ 선택", 13, U.INK, U.BOLD);
        selBtn.setBackground(U.box(c, U.PLATE, 6, U.INK, 1.5f));
        selBtn.setPadding(U.dp(c, 10), U.dp(c, 5), U.dp(c, 10), U.dp(c, 5));
        selBtn.setOnClickListener(v -> { if (multi) exitMulti(); else { multi = true; sel.clear(); a.closeSheet(); tick(); } });
        sum.addView(selBtn);
        col.addView(sum, U.match());

        queue = U.window(c);
        queue.setPadding(U.dp(c, 12), U.dp(c, 9), U.dp(c, 12), U.dp(c, 10));
        queue.addView(U.tv(c, "곧 대기 (가공 끝나는 순)", 11, U.PHOS_DIM, U.BOLD));
        queueRow = U.row(c);
        queue.addView(queueRow, U.margins(U.match(), c, 0, 6, 0, 0));
        col.addView(queue, U.margins(U.match(), c, 14, 0, 14, 8));

        board = new GridLayout(c);
        board.setColumnCount(2);
        board.setPadding(U.dp(c, 11), 0, U.dp(c, 11), 0);
        for (int u = 1; u <= Store.UNITS; u++) cards[u] = new Card(c, u);
        col.addView(board, U.match());

        idleBox = U.col(c);
        idleBox.setBackground(U.dashed(c, U.PLATE, 9, U.DASH));
        idleBox.setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 10));
        idleTitle = U.tv(c, "쉬는 설비 · 눌러서 작업 시작", 11.5f, U.SUB, U.BOLD);
        idleBox.addView(idleTitle);
        idleGrid = new GridLayout(c);
        idleGrid.setColumnCount(6);
        for (int u = 1; u <= Store.UNITS; u++) {
            final int uu = u;
            TextView t = U.tv(c, String.valueOf(u), 15, U.INK, U.MONO_B);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> onTap(uu));
            t.setOnLongClickListener(v -> { onLong(uu); return true; });
            idleChips[u] = t;
        }
        idleBox.addView(idleGrid, U.margins(U.match(), c, 0, 6, 0, 0));
        col.addView(idleBox, U.margins(U.match(), c, 14, 6, 14, 0));

        hint = U.tv(c, "카드 누르기: 상세 · 대기 카드 꾹: 다음 회차 바로 시작 · ☐ 선택: 여러 대", 11.5f, U.SUB, null);
        hint.setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), U.dp(c, 16));
        col.addView(hint);
        multiPad = new View(c);
        col.addView(multiPad, new LinearLayout.LayoutParams(1, U.dp(c, 120)));

        FrameLayout root = new FrameLayout(c);
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        multiBar = U.col(c);
        multiBar.setBackground(U.box(c, U.INK, 12, U.INK, 0));
        multiBar.setElevation(U.dp(c, 8));
        multiBar.setPadding(U.dp(c, 10), U.dp(c, 10), U.dp(c, 10), U.dp(c, 10));
        LinearLayout ir = U.row(c);
        multiInfo = U.tv(c, "", 13, U.PHOS, U.MONO_B);
        ir.addView(multiInfo, U.lpW(1));
        TextView all = U.tv(c, "전체", 13, U.PLATE, U.BOLD);
        all.setPadding(U.dp(c, 10), 0, U.dp(c, 10), 0);
        all.setOnClickListener(v -> selectAll());
        ir.addView(all);
        TextView cancel = U.tv(c, "취소", 13, U.PLATE, U.BOLD);
        cancel.setPadding(U.dp(c, 10), 0, U.dp(c, 4), 0);
        cancel.setOnClickListener(v -> exitMulti());
        ir.addView(cancel);
        multiBar.addView(ir, U.margins(U.match(), a, 4, 0, 0, 8));
        LinearLayout br = U.row(c);
        br.addView(barBtn("멈춤/계속", U.ENAMEL, U.INK, v -> multiPause()), new LinearLayout.LayoutParams(0, U.dp(c, 50), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, U.dp(c, 50), 0.8f);
        sp.leftMargin = U.dp(c, 6);
        br.addView(barBtn("정지", U.ENAMEL, U.BAD, v -> multiStop()), sp);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, U.dp(c, 50), 1.6f);
        gp.leftMargin = U.dp(c, 6);
        br.addView(barBtn("작업 시작", U.SIGNAL, Color.WHITE, v -> multiStart()), gp);
        multiBar.addView(br, U.match());
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ml.setMargins(U.dp(c, 10), 0, U.dp(c, 10), U.dp(c, 10));
        root.addView(multiBar, ml);
        multiBar.setVisibility(View.GONE);

        undo = U.tv(c, "", 14, Color.WHITE, U.BOLD);
        undo.setBackground(U.box(c, U.OK, 9, U.OK, 0));
        undo.setPadding(U.dp(c, 14), U.dp(c, 12), U.dp(c, 14), U.dp(c, 12));
        undo.setElevation(U.dp(c, 8));
        undo.setVisibility(View.GONE);
        FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        ul.setMargins(U.dp(c, 12), U.dp(c, 8), U.dp(c, 12), 0);
        root.addView(undo, ul);
        return root;
    }

    private LinearLayout multiBar;
    private TextView multiInfo;
    private View multiPad;

    private TextView barBtn(String t, int bg, int fg, View.OnClickListener l) {
        TextView b = U.tv(a, t, 16, fg, U.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(a, bg, 8, bg, 0));
        b.setOnClickListener(l);
        return b;
    }

    private TextView chip(LinearLayout row, int bg, int fg) {
        TextView t = U.tv(a, "", 12.5f, fg, U.BOLD);
        t.setBackground(U.box(a, bg, 5, bg, 0));
        t.setPadding(U.dp(a, 9), U.dp(a, 5), U.dp(a, 9), U.dp(a, 5));
        row.addView(t, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 0, 0, 5, 0));
        return t;
    }

    @Override void onShow() { tick(); }

    static String roundLabel(Store.Machine m) { return (m.doneRounds + 1) + "회"; }

    private int rank(int st) { return st == WAIT ? 0 : st == RUN ? 1 : st == PAUSE ? 2 : 3; }

    private long key(Store.Machine m, int st, long now) {
        if (st == WAIT) return m.lastEnd > 0 ? m.lastEnd : 0;
        if (st == RUN) return m.endAt - now;
        if (st == PAUSE) return m.pausedLeft;
        return m.unit;
    }

    @Override void tick() {
        if (root == null) return;
        long now = System.currentTimeMillis();
        recs = Store.records(a);
        List<Store.Machine> ms = Store.machines(a);
        int[] stOf = new int[Store.UNITS + 1];
        List<Store.Machine> act = new ArrayList<>();
        StringBuilder idleSig = new StringBuilder();
        int run = 0, wait = 0, pause = 0, done = 0;
        for (Store.Machine m : ms) {
            int st = state(m, now);
            stOf[m.unit] = st;
            if (st == IDLE) { idleSig.append(m.unit).append(','); continue; }
            act.add(m);
            if (st == RUN) run++; else if (st == WAIT) wait++; else if (st == PAUSE) pause++; else done++;
        }
        act.sort((x, y) -> {
            int sx = stOf[x.unit], sy = stOf[y.unit];
            if (rank(sx) != rank(sy)) return Integer.compare(rank(sx), rank(sy));
            return Long.compare(key(x, sx, now), key(y, sy, now));
        });
        StringBuilder sig = new StringBuilder();
        for (Store.Machine m : act) sig.append(m.unit).append(',');
        if (!sig.toString().equals(order)) {
            order = sig.toString();
            board.removeAllViews();
            for (Store.Machine m : act) {
                GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                p.width = 0;
                p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
                Card cd = cards[m.unit];
                if (cd.getParent() != null) ((ViewGroup) cd.getParent()).removeView(cd);
                board.addView(cd, p);
            }
        }
        for (Store.Machine m : act) cards[m.unit].render(m, now, stOf[m.unit]);

        if (!idleSig.toString().equals(idleOrder)) {
            idleOrder = idleSig.toString();
            idleGrid.removeAllViews();
            for (Store.Machine m : ms) if (stOf[m.unit] == IDLE) {
                GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                p.width = 0; p.height = U.dp(a, 38);
                p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
                idleGrid.addView(idleChips[m.unit], p);
            }
        }
        for (int u = 1; u <= Store.UNITS; u++) {
            boolean s = multi && sel.contains(u);
            idleChips[u].setBackground(s ? U.box(a, U.OK, 6, U.OK, 0) : U.box(a, Color.rgb(0xf3, 0xf5, 0xf1), 6, Color.rgb(0xa9, 0xb3, 0xa8), 1));
            idleChips[u].setTextColor(s ? Color.WHITE : U.INK);
        }
        idleBox.setVisibility(idleOrder.isEmpty() ? View.GONE : View.VISIBLE);
        idleTitle.setText(multi ? "쉬는 설비 · 눌러서 고르기" : "쉬는 설비 · 눌러서 작업 시작");

        // 곧 대기
        queueRow.removeAllViews();
        int qn = 0;
        for (Store.Machine m : act) {
            int st = stOf[m.unit];
            if (st != WAIT && st != RUN) continue;
            if (qn++ >= 4) break;
            LinearLayout q = U.col(a);
            q.setBackground(U.box(a, st == WAIT ? Color.rgb(0x3a, 0x2a, 0x20) : Color.rgb(0x2b, 0x34, 0x30), 6, Color.TRANSPARENT, 0));
            q.setPadding(U.dp(a, 8), U.dp(a, 5), U.dp(a, 6), U.dp(a, 6));
            q.addView(U.tv(a, m.unit + "호기", 14, U.PLATE, U.BOLD));
            q.addView(U.tv(a, st == WAIT ? "대기" : U.mmss(m.endAt - now), 16, st == WAIT ? Color.rgb(0xff, 0xb4, 0x8f) : U.PHOS, U.MONO_B));
            final int uu = m.unit;
            q.setOnClickListener(v -> onTap(uu));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = U.dp(a, 5);
            queueRow.addView(q, lp);
        }
        for (int i = qn; i < 4; i++) queueRow.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        queue.setVisibility(qn == 0 ? View.GONE : View.VISIBLE);

        clock.setText(HandoverPages.label(HandoverPages.currentShift()[0]) + " " + HandoverPages.currentShift()[1] + " · " + U.time(now, "HH:mm"));
        sRun.setText("가공 " + run);
        sWait.setText("대기 " + wait);
        sPause.setText("멈춤 " + pause);
        sDone.setText("완료 " + done);
        sWait.setVisibility(wait > 0 ? View.VISIBLE : View.GONE);
        sPause.setVisibility(pause > 0 ? View.VISIBLE : View.GONE);
        sDone.setVisibility(done > 0 ? View.VISIBLE : View.GONE);
        selBtn.setText(multi ? "✕ 선택 끝" : "☐ 선택");
        multiBar.setVisibility(multi ? View.VISIBLE : View.GONE);
        multiPad.setVisibility(multi ? View.VISIBLE : View.GONE);
        multiInfo.setText(sel.isEmpty() ? "호기를 눌러 고르세요" : selLabel() + " 선택");
        if (act.isEmpty() && !multi) hint.setText("가공 중인 설비가 없습니다 · 아래 번호를 눌러 작업 시작");
        else hint.setText("카드 누르기: 상세 · 대기 카드 꾹: 다음 회차 바로 시작 · ☐ 선택: 여러 대");
        if (sheetRefresh != null && a.sheetOpen()) sheetRefresh.run();
    }

    // ---------------- 조작 ----------------
    private void onTap(int unit) {
        if (multi) {
            if (sel.contains(unit)) sel.remove(unit); else sel.add(unit);
            tick();
            return;
        }
        TimerCenter.acknowledge(a, unit);
        showUnitSheet(unit);
        tick();
    }

    /** 꾹: 대기 카드는 다음 회차 바로 시작 */
    private void onLong(int unit) {
        if (multi) { onTap(unit); return; }
        Store.Machine m = Store.machine(a, unit);
        int st = state(m, System.currentTimeMillis());
        if (st == WAIT) {
            TimerCenter.acknowledge(a, unit);
            TimerCenter.startRound(a, unit);
            Store.Machine n = Store.machine(a, unit);
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showUndo(unit, n.unit + "호기 " + (n.doneRounds + 1) + "회 가공 시작 · " + n.roundMin + "분");
        } else if (st == RUN || st == PAUSE) {
            Toast.makeText(a, unit + "호기 가공 중입니다 · 누르면 상세", Toast.LENGTH_SHORT).show();
        } else if (st == DONE) {
            Toast.makeText(a, unit + "호기 작업 완료 · 눌러서 결과 입력", Toast.LENGTH_SHORT).show();
        } else {
            showUnitSheet(unit);
        }
        tick();
    }

    private final Runnable hideUndo = () -> { if (undo != null) undo.setVisibility(View.GONE); };

    private void showUndo(int unit, String msg) {
        undo.setText(msg + "      되돌리기 ›");
        undo.setVisibility(View.VISIBLE);
        undo.setOnClickListener(v -> {
            TimerCenter.stop(a, unit);
            undo.setVisibility(View.GONE);
            Toast.makeText(a, unit + "호기 대기로 되돌렸습니다", Toast.LENGTH_SHORT).show();
            tick();
        });
        h.removeCallbacks(hideUndo);
        h.postDelayed(hideUndo, 6000);
    }

    private void selectAll() {
        for (int u = 1; u <= Store.UNITS; u++) sel.add(u);
        tick();
    }

    void exitMulti() {
        multi = false;
        sel.clear();
        a.closeSheet();
        tick();
    }

    private Runnable sheetRefresh;

    /** 1회 시간 고르기 (누르면 선택만, 시작하지 않음) */
    private View presetSelect(int[] v, Runnable onChange) {
        GridLayout g = new GridLayout(a);
        g.setColumnCount(4);
        List<TextView> btns = new ArrayList<>();
        TextView custom = U.tv(a, "직접", 15, U.SUB, U.BOLD);
        Runnable render = () -> {
            boolean preset = false;
            for (int k = 0; k < PRESETS.length; k++) {
                boolean on = PRESETS[k] == v[1];
                if (on) preset = true;
                TextView b = btns.get(k);
                b.setTextColor(on ? Color.WHITE : U.INK);
                b.setBackground(on ? U.box(a, U.SIGNAL, 7, U.SIGNAL_DK, 0) : U.box(a, U.ENAMEL, 7, U.INK, 2));
            }
            custom.setText(preset ? "직접" : v[1] + "분");
            custom.setTextColor(preset ? U.SUB : Color.WHITE);
            custom.setBackground(preset ? U.dashed(a, U.ENAMEL, 7, Color.rgb(0x99, 0xa3, 0x97)) : U.box(a, U.SIGNAL, 7, U.SIGNAL_DK, 0));
            if (onChange != null) onChange.run();
        };
        for (int m : PRESETS) {
            final int mm = m;
            TextView b = U.tv(a, m + "분", 20, U.INK, U.MONO_B);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(x -> { v[1] = mm; render.run(); });
            btns.add(b);
            g.addView(b, gp());
        }
        custom.setGravity(Gravity.CENTER);
        custom.setOnClickListener(x -> askMinutes(mm -> { v[1] = mm; render.run(); }));
        g.addView(custom, gp());
        render.run();
        return g;
    }

    /** 작업 설정: 1회 시간 + 가공 횟수 → 작업 시작 */
    private View setupPanel(String btnLabel, java.util.function.BiConsumer<Integer, Integer> onStart) {
        LinearLayout p = U.col(a);
        int[] v = {Store.getInt(a, "planN", 5), Store.getInt(a, "roundMin", 20)};
        TextView sum = U.tv(a, "", 13, U.SUB, U.MONO_B);
        Runnable upd = () -> sum.setText(v[0] + "회 × " + v[1] + "분 = 총 " + (v[0] * v[1]) + "분");
        TextView t1 = U.sectionTitle(a, "1회 시간 · 눌러서 고르기");
        p.addView(t1);
        p.addView(presetSelect(v, upd), U.match());
        LinearLayout r = U.row(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(U.tv(a, "가공 횟수", 15, U.INK, U.BOLD), U.lpW(1));
        TextView val = U.tv(a, v[0] + "회", 18, U.INK, U.MONO_B);
        val.setGravity(Gravity.CENTER);
        TextView minus = stepBtn("−"), plus = stepBtn("+");
        minus.setOnClickListener(x -> { v[0] = Math.max(1, v[0] - 1); val.setText(v[0] + "회"); upd.run(); });
        plus.setOnClickListener(x -> { v[0] = Math.min(30, v[0] + 1); val.setText(v[0] + "회"); upd.run(); });
        r.addView(minus, new LinearLayout.LayoutParams(U.dp(a, 42), U.dp(a, 38)));
        r.addView(val, new LinearLayout.LayoutParams(U.dp(a, 70), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(plus, new LinearLayout.LayoutParams(U.dp(a, 42), U.dp(a, 38)));
        p.addView(r, U.margins(U.match(), a, 3, 10, 3, 4));
        sum.setPadding(U.dp(a, 4), U.dp(a, 4), 0, 0);
        p.addView(sum);
        upd.run();
        TextView go = U.button(a, btnLabel, 0, x -> {
            Store.putInt(a, "planN", v[0]);
            Store.putInt(a, "roundMin", v[1]);
            onStart.accept(v[0], v[1]);
        });
        p.addView(go, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 56)), a, 0, 12, 0, 0));
        return p;
    }

    private GridLayout.LayoutParams gp() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        p.width = 0; p.height = U.dp(a, 54);
        p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
        return p;
    }

    private void askMinutes(java.util.function.IntConsumer onPick) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("분");
        new android.app.AlertDialog.Builder(a)
                .setTitle("1회 시간 (분)")
                .setView(e)
                .setPositiveButton("확인", (d, w) -> {
                    try {
                        int m = Integer.parseInt(e.getText().toString().trim());
                        if (m > 0 && m <= 600) onPick.accept(m);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private TextView action(LinearLayout row, String t, int color, View.OnClickListener l) {
        TextView b = U.tv(a, t, 14, color, U.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(a, U.ENAMEL, 7, U.RULE, 1));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.dp(a, 44), 1);
        p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
        row.addView(b, p);
        return b;
    }

    private static boolean active(Store.Machine m, long now) {
        return m.planActive || m.endAt > now || m.paused();
    }

    // ---------------- 개별 시트 ----------------
    void showUnitSheet(int unit) {
        planSig = -1;
        Store.Machine m0 = Store.machine(a, unit);
        LinearLayout s = a.sheetBox(unit + "호기" + (m0.spec.isEmpty() ? "" : " · " + m0.spec), tgtLabel(m0.spec));
        TextView state = U.tv(a, "", 14, U.SUB, U.MONO_B);
        s.addView(state, U.margins(U.match(), a, 0, 0, 0, 8));
        LinearLayout dyn = U.col(a);
        s.addView(dyn, U.match());
        final int[] mode = {-1};
        final TextView[] pauseBtn = {null};
        final LinearLayout[] plan = {null};
        sheetRefresh = () -> {
            Store.Machine m = Store.machine(a, unit);
            long now = System.currentTimeMillis();
            int md = active(m, now) ? 1 : 0;
            if (md != mode[0]) {
                mode[0] = md;
                planSig = -1;
                dyn.removeAllViews();
                if (md == 0) {
                    dyn.addView(setupPanel("작업 시작", (n, min) -> {
                        TimerCenter.startPlan(a, unit, n, min);
                        a.closeSheet();
                        Toast.makeText(a, unit + "호기 작업 시작 (" + n + "회 × " + min + "분)", Toast.LENGTH_SHORT).show();
                        tick();
                    }), U.match());
                    pauseBtn[0] = null;
                    plan[0] = null;
                } else {
                    LinearLayout pr = U.row(a);
                    pauseBtn[0] = action(pr, "일시정지", U.INK, v -> {
                        Store.Machine x = Store.machine(a, unit);
                        if (x.paused()) TimerCenter.resume(a, unit);
                        else if (!TimerCenter.pause(a, unit)) Toast.makeText(a, "진행 중인 타이머가 없습니다", Toast.LENGTH_SHORT).show();
                        tick();
                    });
                    pauseBtn[0].setTextSize(16);
                    ((LinearLayout.LayoutParams) pauseBtn[0].getLayoutParams()).height = U.dp(a, 50);
                    dyn.addView(pr, U.match());
                    LinearLayout acts = U.row(a);
                    action(acts, "+1분", U.INK, v -> { TimerCenter.addMinute(a, unit); tick(); });
                    action(acts, "이 회차 다시", U.INK, v -> {
                        Store.Machine x = Store.machine(a, unit);
                        if (x.planActive) TimerCenter.startRound(a, unit);
                        else TimerCenter.startSingle(a, unit, x.durMin > 0 ? x.durMin : 20);
                        tick();
                    });
                    action(acts, "정지", U.BAD, v -> { TimerCenter.stop(a, unit); tick(); });
                    dyn.addView(acts, U.match());
                    LinearLayout pl = U.panel(a);
                    pl.setBackground(U.box(a, U.ENAMEL, 7, U.RULE, 1));
                    dyn.addView(pl, U.margins(U.match(), a, 3, 10, 3, 0));
                    plan[0] = pl;
                }
            }
            if (m.endAt > now) state.setText((m.round ? roundLabel(m) + " 진행 " : m.durMin + "분 진행 ") + U.mmss(m.endAt - now) + " 남음");
            else if (m.paused()) state.setText((m.round ? roundLabel(m) + " " : m.durMin + "분 ") + "일시정지 · " + U.mmss(m.pausedLeft) + " 남음");
            else if (m.finished) state.setText(m.finishMsg);
            else if (m.planActive) state.setText(m.planDone() ? "작업 완료" : m.doneRounds + "/" + m.planTotal() + "회 · 다음 회차 대기");
            else state.setText("대기 중 · 시간 고르고 작업 시작");
            state.setTextColor(m.paused() ? U.WARN : U.SUB);
            if (pauseBtn[0] != null) {
                boolean p = m.paused();
                pauseBtn[0].setText(p ? "▶ 계속" : "❚❚ 일시정지");
                pauseBtn[0].setTextColor(p ? Color.WHITE : U.INK);
                pauseBtn[0].setBackground(p ? U.box(a, U.OK, 7, U.OK, 0) : U.box(a, U.ENAMEL, 7, U.RULE, 1));
                pauseBtn[0].setAlpha(p || m.endAt > now ? 1f : 0.45f);
            }
            if (plan[0] != null) renderPlan(plan[0], m);
        };
        sheetRefresh.run();
        a.showSheet(s, () -> sheetRefresh = null);
    }

    private int planSig = -1;

    private String tgtLabel(String spec) {
        Store.Spec sp = Store.spec(a, spec);
        if (sp == null || Double.isNaN(sp.target)) return null;
        return "목표 " + Form.num(sp.target, 1) + " ±" + Form.num(sp.tol, 1);
    }

    private void renderPlan(LinearLayout plan, Store.Machine m) {
        int sig = (m.planActive ? 1 : 0) * 100000 + m.doneRounds * 1000 + (m.planA * 7) + (m.endAt > 0 ? 10 : 0) + (m.paused() ? 3 : 0);
        if (sig == planSig && plan.getChildCount() > 0) return;
        planSig = sig;
        plan.removeAllViews();
        int unit = m.unit;
        if (!m.planActive) {
            plan.addView(U.tv(a, "단일 타이머 진행 중", 13, U.SUB, null));
            return;
        }
        plan.addView(U.sectionTitle(a, "작업 · " + m.planA + "회 × " + m.roundMin + "분"));
        LinearLayout info = U.row(a);
        Store.Record lr = Store.lastRecord(Store.records(a), unit, m.spec.isEmpty() ? null : m.spec);
        String[][] cells = {{"진행", m.doneRounds + " / " + m.planTotal() + "회"}, {"누적 가공", (m.doneRounds * m.roundMin) + "분"},
                {"최근 RMS", lr == null ? "—" : U.f1(lr.vRms) + (U.judge(Store.spec(a, m.spec), lr.vRms) == 0 ? " ✓" : "")}};
        for (int i = 0; i < 3; i++) {
            LinearLayout cl = U.col(a);
            cl.setBackground(U.box(a, U.PLATE, 7, U.RULE, 1));
            cl.setPadding(U.dp(a, 8), U.dp(a, 6), U.dp(a, 8), U.dp(a, 6));
            cl.addView(U.tv(a, cells[i][0], 11, U.SUB, null));
            cl.addView(U.tv(a, cells[i][1], 16, U.INK, U.MONO_B));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = U.dp(a, 6);
            info.addView(cl, lp);
        }
        plan.addView(info, U.margins(U.match(), a, 0, 0, 0, 8));
        plan.addView(roundsRow(m), U.match());
        int total = m.planTotal() * m.roundMin, doneMin = m.doneRounds * m.roundMin;
        plan.addView(U.tv(a, "누적 " + doneMin + " / " + total + "분", 13, U.SUB, U.MONO), U.margins(U.match(), a, 0, 8, 0, 4));
        if (!m.log.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, m.log.size() - 6); i < m.log.size(); i++) sb.append(m.log.get(i)).append('\n');
            plan.addView(U.tv(a, sb.toString().trim(), 12, U.INK, U.MONO), U.margins(U.match(), a, 0, 0, 0, 6));
        }
        boolean run = m.endAt > System.currentTimeMillis() || m.paused();
        if (!run) {
            final boolean done = m.planDone();
            TextView b = U.button(a, done ? "가공 결과 입력" : "다음 회차 시작 → " + (m.doneRounds + 1) + "회 · " + m.roundMin + "분", 0, x -> {
                TimerCenter.acknowledge(a, unit);
                if (done) { a.closeSheet(); ResultSheet.show(a, unit); }
                else { TimerCenter.startRound(a, unit); a.closeSheet(); }
                tick();
            });
            b.setTextSize(18);
            plan.addView(b, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 60)), a, 0, 4, 0, 4));
            if (!done) {
                TextView tip = U.tv(a, "팁: 상황판에서 카드를 꾹 누르면 이 창 없이 바로 시작", 11, U.SUB, null);
                tip.setGravity(Gravity.CENTER);
                plan.addView(tip, U.margins(U.match(), a, 0, 0, 0, 4));
            }
        }
        LinearLayout r = U.row(a);
        action(r, "결과 입력", U.INK, x -> { a.closeSheet(); ResultSheet.show(a, unit); });
        action(r, "작업 끝내기", U.BAD, x -> { TimerCenter.endPlan(a, unit); planSig = -1; tick(); });
        plan.addView(r, U.margins(U.match(), a, -3, 4, -3, 0));
    }

    private TextView stepBtn(String t) {
        TextView b = U.tv(a, t, 18, U.INK, U.MONO_B);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(a, U.PLATE, 6, U.INK, 2));
        return b;
    }

    private View roundsRow(Store.Machine m) {
        LinearLayout r = U.row(a);
        boolean run = m.endAt > System.currentTimeMillis() || m.paused();
        for (int i = 1; i <= m.planTotal(); i++) {
            String label = String.valueOf(i);
            TextView b = U.tv(a, label, 13, U.INK, U.MONO_B);
            b.setGravity(Gravity.CENTER);
            if (i <= m.doneRounds) { b.setBackground(U.box(a, U.INK, 6, U.INK, 0)); b.setTextColor(U.PLATE); }
            else if (i == m.doneRounds + 1 && run) { b.setBackground(U.box(a, U.SCREEN, 6, U.SIGNAL, 2)); b.setTextColor(U.PHOS); }
            else { b.setBackground(U.dashed(a, Color.TRANSPARENT, 6, Color.rgb(0x99, 0xa3, 0x97))); b.setTextColor(Color.rgb(0x8b, 0x96, 0x8c)); }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(U.dp(a, 40), U.dp(a, 40));
            p.rightMargin = U.dp(a, 5);
            r.addView(b, p);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.addView(r);
        return hs;
    }

    // ---------------- 다중 선택: 다 고르고 버튼 → 시간 묻기 ----------------
    private String selLabel() {
        List<Integer> l = new ArrayList<>(sel);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(" · "); sb.append(l.get(i)); }
        return l.size() > 8 ? l.size() + "대" : sb + "호기";
    }

    private void multiStart() {
        if (sel.isEmpty()) return;
        LinearLayout s = a.sheetBox(sel.size() + "대 작업 시작", selLabel());
        s.addView(setupPanel(sel.size() + "대 작업 시작", (n, min) -> {
            for (int u : sel) TimerCenter.startPlan(a, u, n, min);
            Toast.makeText(a, sel.size() + "대 작업 시작 (" + n + "회 × " + min + "분)", Toast.LENGTH_LONG).show();
            exitMulti();
        }), U.match());
        sheetRefresh = null;
        a.showSheet(s, null);
    }

    private void multiPause() {
        boolean any = false;
        long now = System.currentTimeMillis();
        for (int u : sel) if (Store.machine(a, u).endAt > now) any = true;
        int n = 0;
        for (int u : sel) if (any ? TimerCenter.pause(a, u) : TimerCenter.resume(a, u)) n++;
        Toast.makeText(a, n + "대 " + (any ? "일시정지" : "계속"), Toast.LENGTH_SHORT).show();
        exitMulti();
    }

    private void multiStop() {
        new android.app.AlertDialog.Builder(a).setTitle(selLabel() + " 정지할까요?")
                .setPositiveButton("정지", (d, w) -> { for (int u : sel) TimerCenter.stop(a, u); exitMulti(); })
                .setNegativeButton("취소", null).show();
    }
}
