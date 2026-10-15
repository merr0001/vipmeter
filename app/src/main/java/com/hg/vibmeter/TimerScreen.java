package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.view.Gravity;
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

/** 타이머 탭: 1~17호기 한 화면, 탭=개별, 길게 누르기=다중 선택 */
class TimerScreen extends MainActivity.Screen {

    static final int[] PRESETS = {1, 3, 5, 10, 20, 30, 60};

    boolean multi;
    private final Set<Integer> sel = new LinkedHashSet<>();
    private final Tile[] tiles = new Tile[Store.UNITS + 1];
    private TextView sumRun, sumDone, sumIdle, allTile, hint;
    private LinearLayout sumRow, selBar;
    private TextView selText;

    TimerScreen(MainActivity a) { super(a); }

    // ---------------- 타일 ----------------
    class Tile extends FrameLayout {
        final int unit;
        final TextView num, st, time, spec;
        final Prog prog;
        final View check;
        Tile(Context c, int unit) {
            super(c);
            this.unit = unit;
            setPadding(U.dp(c, 9), U.dp(c, 7), U.dp(c, 9), U.dp(c, 7));
            LinearLayout top = U.row(c);
            num = U.tv(c, unit + "", 20, U.INK, U.BOLD);
            top.addView(num);
            TextView ho = U.tv(c, "호기", 10, U.SUB, null);
            ho.setPadding(U.dp(c, 1), U.dp(c, 6), 0, 0);
            top.addView(ho);
            addView(top, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            st = U.tv(c, "", 10.5f, U.SUB, U.MONO_B);
            addView(st, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            spec = U.tv(c, "", 9.5f, U.SUB, U.MONO);
            LayoutParams spl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            spl.topMargin = U.dp(c, 24);
            addView(spec, spl);
            LinearLayout bot = U.col(c);
            time = U.tv(c, "", 25, U.INK, U.MONO_B);
            bot.addView(time);
            prog = new Prog(c);
            bot.addView(prog, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 4)), c, 0, 5, 0, 0));
            addView(bot, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            check = new View(c);
            LayoutParams cl = new LayoutParams(U.dp(c, 22), U.dp(c, 22), Gravity.TOP | Gravity.END);
            addView(check, cl);
            setOnClickListener(v -> onTap(unit));
            setOnLongClickListener(v -> { onLong(unit); return true; });
        }

        void render(Store.Machine m, long now) {
            Context c = getContext();
            boolean run = m.endAt > now;
            boolean selected = multi && sel.contains(unit);
            int bg, fg, sub;
            if (m.finished) {
                setBackground(U.box(c, U.SIGNAL, 7, Color.argb(90, 226, 87, 27), 3));
                fg = Color.WHITE; sub = Color.rgb(0xff, 0xe3, 0xd4);
                st.setText(m.round ? "회차 끝" : "완료");
                time.setText("00:00");
                time.setTextSize(25);
                prog.frac = 1; prog.color = Color.WHITE; prog.track = Color.argb(80, 255, 255, 255);
            } else if (run) {
                setBackground(U.box(c, U.SCREEN, 7, U.SCREEN, 0));
                fg = U.PLATE; sub = U.PHOS_DIM;
                st.setText(m.round ? roundLabel(m) : m.durMin + "분");
                time.setText(U.mmss(m.endAt - now));
                time.setTextSize(25);
                time.setTextColor(U.PHOS);
                double tot = m.durMin * 60_000.0;
                prog.frac = (float) Math.max(0, Math.min(1, 1 - (m.endAt - now) / tot));
                prog.color = U.PHOS; prog.track = Color.argb(46, 159, 224, 184);
            } else {
                setBackground(U.dashed(c, U.PLATE, 7, U.DASH));
                fg = U.INK; sub = Color.rgb(0x8b, 0x96, 0x8c);
                if (m.planActive && m.swapDue()) st.setText("좌우 대기");
                else if (m.planActive && !m.planDone()) st.setText((m.doneRounds) + "/" + m.planTotal() + "회");
                else st.setText("대기");
                time.setText("— : —");
                time.setTextSize(15);
                prog.frac = 0; prog.color = U.RULE; prog.track = Color.TRANSPARENT;
            }
            num.setTextColor(fg);
            ((TextView) ((LinearLayout) num.getParent()).getChildAt(1)).setTextColor(sub);
            st.setTextColor(sub);
            spec.setText(m.spec);
            spec.setTextColor(sub);
            if (!run) time.setTextColor(m.finished ? Color.WHITE : Color.rgb(0x9a, 0xa5, 0x9b));
            prog.invalidate();
            if (multi) {
                st.setVisibility(INVISIBLE);
                check.setVisibility(VISIBLE);
                check.setBackground(selected ? U.box(c, U.SIGNAL, 11, Color.WHITE, 2) : U.box(c, Color.TRANSPARENT, 11, run ? Color.rgb(0x5b, 0x6e, 0x64) : U.DASH, 2));
                if (selected) setForeground(U.box(c, Color.TRANSPARENT, 7, U.SIGNAL, 3));
                else setForeground(null);
            } else {
                st.setVisibility(VISIBLE);
                check.setVisibility(GONE);
                setForeground(null);
            }
        }
    }

    static String roundLabel(Store.Machine m) {
        if (m.swapped) return "좌우 " + (m.doneRounds - m.planA + 1) + "회";
        return (m.doneRounds + 1) + "회";
    }

    static class Prog extends View {
        float frac; int color = U.PHOS, track = Color.TRANSPARENT;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Prog(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(track);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(color);
            cv.drawRoundRect(new RectF(0, 0, w * frac, h), h / 2, h / 2, p);
        }
    }

    @Override
    View build() {
        Context c = a;
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        sv.addView(col);
        col.addView(a.header("타이머", "1~" + Store.UNITS + "호기"));

        sumRow = U.row(c);
        sumRow.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 10));
        sumRun = chip(sumRow, U.SCREEN, U.PHOS);
        sumDone = chip(sumRow, U.SIGNAL, Color.WHITE);
        sumIdle = chip(sumRow, U.PLATE, U.INK);
        col.addView(sumRow);

        selBar = U.row(c);
        selBar.setBackground(U.box(c, U.INK, 7, U.INK, 0));
        selBar.setPadding(U.dp(c, 14), U.dp(c, 12), U.dp(c, 14), U.dp(c, 12));
        selText = U.tv(c, "", 18, U.PLATE, U.BOLD);
        selBar.addView(selText, U.lpW(1));
        TextView cancel = U.tv(c, "취소", 14, U.PLATE, null);
        cancel.setOnClickListener(v -> exitMulti());
        selBar.addView(cancel);
        selBar.setVisibility(View.GONE);
        col.addView(selBar, U.margins(U.match(), c, 14, 0, 14, 10));

        GridLayout g = new GridLayout(c);
        g.setColumnCount(3);
        g.setPadding(U.dp(c, 11), 0, U.dp(c, 11), 0);
        for (int u = 1; u <= Store.UNITS; u++) {
            Tile t = new Tile(c, u);
            tiles[u] = t;
            g.addView(t, cell());
        }
        allTile = U.tv(c, "진행 중\n모두 정지", 13, U.INK, U.BOLD);
        allTile.setGravity(Gravity.CENTER);
        allTile.setBackground(U.box(c, Color.TRANSPARENT, 7, U.INK, 2));
        allTile.setOnClickListener(v -> {
            if (multi) { selectAll(); return; }
            Toast.makeText(a, "모두 정지하려면 길게 누르세요", Toast.LENGTH_SHORT).show();
        });
        allTile.setOnLongClickListener(v -> {
            if (multi) return false;
            for (Store.Machine m : Store.machines(a)) if (m.endAt > 0) TimerCenter.stop(a, m.unit);
            Toast.makeText(a, "진행 중인 타이머를 모두 정지했습니다", Toast.LENGTH_SHORT).show();
            tick();
            return true;
        });
        g.addView(allTile, cell());
        col.addView(g, U.match());

        hint = U.tv(c, "탭: 개별 타이머 · 길게 누르기: 여러 호기 선택", 12, U.SUB, null);
        hint.setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), U.dp(c, 20));
        col.addView(hint);
        return sv;
    }

    private GridLayout.LayoutParams cell() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        p.width = 0;
        p.height = U.dp(a, 88);
        p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
        return p;
    }

    private TextView chip(LinearLayout row, int bg, int fg) {
        TextView t = U.tv(a, "", 13, fg, U.BOLD);
        t.setBackground(U.box(a, bg, 4, bg == U.PLATE ? U.RULE : bg, 1));
        t.setPadding(U.dp(a, 10), U.dp(a, 5), U.dp(a, 10), U.dp(a, 5));
        row.addView(t, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 0, 0, 6, 0));
        return t;
    }

    @Override void onShow() { tick(); }

    @Override void tick() {
        if (root == null) return;
        long now = System.currentTimeMillis();
        int run = 0, done = 0;
        for (Store.Machine m : Store.machines(a)) {
            tiles[m.unit].render(m, now);
            if (m.finished) done++;
            else if (m.endAt > now) run++;
        }
        sumRun.setText("진행 " + run);
        sumDone.setText("완료 " + done);
        sumIdle.setText("대기 " + (Store.UNITS - run - done));
        sumDone.setVisibility(done > 0 ? View.VISIBLE : View.GONE);
        sumRow.setVisibility(multi ? View.GONE : View.VISIBLE);
        selBar.setVisibility(multi ? View.VISIBLE : View.GONE);
        selText.setText(sel.size() + "대 선택됨");
        allTile.setText(multi ? "전체\n선택" : "진행 중\n모두 정지");
        if (sheetRefresh != null && a.sheetOpen()) sheetRefresh.run();
    }

    // ---------------- 조작 ----------------
    private void onTap(int unit) {
        if (multi) {
            if (sel.contains(unit)) sel.remove(unit); else sel.add(unit);
            if (sel.isEmpty()) exitMulti(); else { tick(); showMultiSheet(); }
            return;
        }
        TimerCenter.acknowledge(a, unit);
        showUnitSheet(unit);
        tick();
    }

    private void onLong(int unit) {
        if (!multi) { multi = true; sel.clear(); a.closeSheet(); }
        sel.add(unit);
        tick();
        showMultiSheet();
    }

    private void selectAll() {
        for (int u = 1; u <= Store.UNITS; u++) sel.add(u);
        tick();
        showMultiSheet();
    }

    void exitMulti() {
        multi = false;
        sel.clear();
        a.closeSheet();
        tick();
    }

    private Runnable sheetRefresh;

    private View presetGrid(java.util.function.IntConsumer onPick) {
        GridLayout g = new GridLayout(a);
        g.setColumnCount(4);
        int last = Store.getInt(a, "lastMin", 20);
        for (int m : PRESETS) {
            final int mm = m;
            TextView b = U.tv(a, m + "분", 20, m == last ? Color.WHITE : U.INK, U.MONO_B);
            b.setGravity(Gravity.CENTER);
            b.setBackground(m == last ? U.box(a, U.SIGNAL, 7, U.SIGNAL, 0) : U.box(a, U.ENAMEL, 7, U.INK, 2));
            b.setOnClickListener(v -> { Store.putInt(a, "lastMin", mm); onPick.accept(mm); });
            g.addView(b, gp());
        }
        TextView custom = U.tv(a, "직접", 15, U.SUB, U.BOLD);
        custom.setGravity(Gravity.CENTER);
        custom.setBackground(U.dashed(a, U.ENAMEL, 7, Color.rgb(0x99, 0xa3, 0x97)));
        custom.setOnClickListener(v -> askMinutes(onPick));
        g.addView(custom, gp());
        return g;
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
                .setTitle("타이머 시간 (분)")
                .setView(e)
                .setPositiveButton("시작", (d, w) -> {
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

    // ---------------- 개별 시트 ----------------
    void showUnitSheet(int unit) {
        planSig = -1;
        Store.Machine m0 = Store.machine(a, unit);
        LinearLayout s = a.sheetBox(unit + "호기 타이머", m0.spec.isEmpty() ? null : m0.spec);
        TextView state = U.tv(a, "", 14, U.SUB, U.MONO_B);
        s.addView(state, U.margins(U.match(), a, 0, 0, 0, 8));
        s.addView(presetGrid(min -> { TimerCenter.startSingle(a, unit, min); a.closeSheet(); tick(); }), U.match());
        LinearLayout acts = U.row(a);
        action(acts, "+1분", U.INK, v -> { TimerCenter.addMinute(a, unit); tick(); });
        action(acts, "다시 시작", U.INK, v -> {
            Store.Machine m = Store.machine(a, unit);
            if (m.round && m.planActive) TimerCenter.startRound(a, unit);
            else TimerCenter.startSingle(a, unit, m.durMin > 0 ? m.durMin : Store.getInt(a, "lastMin", 20));
            tick();
        });
        action(acts, "정지", U.BAD, v -> { TimerCenter.stop(a, unit); tick(); });
        s.addView(acts, U.match());

        // 가공 계획
        LinearLayout plan = U.panel(a);
        plan.setBackground(U.box(a, U.ENAMEL, 7, U.RULE, 1));
        s.addView(plan, U.margins(U.match(), a, 3, 10, 3, 0));
        sheetRefresh = () -> {
            Store.Machine m = Store.machine(a, unit);
            long now = System.currentTimeMillis();
            if (m.endAt > now) state.setText((m.round ? roundLabel(m) + " 진행 " : m.durMin + "분 진행 ") + U.mmss(m.endAt - now) + " 남음");
            else if (m.finished) state.setText(m.finishMsg);
            else state.setText("대기 중");
            renderPlan(plan, m);
        };
        sheetRefresh.run();
        a.showSheet(s, () -> sheetRefresh = null);
    }

    private int planSig = -1;

    private void renderPlan(LinearLayout plan, Store.Machine m) {
        int sig = (m.planActive ? 1 : 0) * 100000 + m.doneRounds * 1000 + (m.swapped ? 100 : 0) + (m.endAt > 0 ? 10 : 0) + m.planA * 3 + m.planB;
        if (sig == planSig && plan.getChildCount() > 0) return;
        planSig = sig;
        plan.removeAllViews();
        int unit = m.unit;
        if (!m.planActive) {
            plan.addView(U.sectionTitle(a, "가공 계획 (회차 타이머)"));
            int[] v = {Store.getInt(a, "planA", 3), Store.getInt(a, "planB", 2), Store.getInt(a, "roundMin", 20)};
            String[] names = {"교체 전", "좌우 교체 후", "1회 시간"};
            String[] units = {"회", "회", "분"};
            for (int i = 0; i < 3; i++) {
                final int k = i;
                LinearLayout r = U.row(a);
                r.addView(U.tv(a, names[i], 14, U.INK, U.BOLD), U.lpW(1));
                TextView val = U.tv(a, v[i] + units[i], 17, U.INK, U.MONO_B);
                val.setGravity(Gravity.CENTER);
                TextView minus = stepBtn("−"), plus = stepBtn("+");
                minus.setOnClickListener(x -> { v[k] = Math.max(k == 2 ? 1 : 0, v[k] - (k == 2 ? 5 : 1)); val.setText(v[k] + units[k]); });
                plus.setOnClickListener(x -> { v[k] = Math.min(k == 2 ? 120 : 20, v[k] + (k == 2 ? 5 : 1)); val.setText(v[k] + units[k]); });
                r.addView(minus, new LinearLayout.LayoutParams(U.dp(a, 38), U.dp(a, 34)));
                r.addView(val, new LinearLayout.LayoutParams(U.dp(a, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
                r.addView(plus, new LinearLayout.LayoutParams(U.dp(a, 38), U.dp(a, 34)));
                plan.addView(r, U.margins(U.match(), a, 0, 3, 0, 3));
            }
            TextView go = U.button(a, "판 시작 (1회차 바로 시작)", 2, x -> {
                if (v[0] + v[1] < 1) return;
                Store.putInt(a, "planA", v[0]); Store.putInt(a, "planB", v[1]); Store.putInt(a, "roundMin", v[2]);
                TimerCenter.startPlan(a, unit, v[0], v[1], v[2]);
                a.closeSheet();
                tick();
            });
            plan.addView(go, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 48)), a, 0, 8, 0, 0));
            return;
        }
        plan.addView(U.sectionTitle(a, "가공 계획 · " + m.planA + "회 + 좌우 " + m.planB + "회 · " + m.roundMin + "분"));
        plan.addView(roundsRow(m), U.match());
        int total = m.planTotal() * m.roundMin, doneMin = m.doneRounds * m.roundMin;
        plan.addView(U.tv(a, "누적 " + doneMin + " / " + total + "분", 13, U.SUB, U.MONO), U.margins(U.match(), a, 0, 8, 0, 4));
        if (!m.log.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, m.log.size() - 6); i < m.log.size(); i++) sb.append(m.log.get(i)).append('\n');
            plan.addView(U.tv(a, sb.toString().trim(), 12, U.INK, U.MONO), U.margins(U.match(), a, 0, 0, 0, 6));
        }
        boolean run = m.endAt > System.currentTimeMillis();
        if (!run) {
            String label = null;
            if (m.planDone()) label = "가공 결과 입력";
            else if (m.swapDue()) label = "좌우 바꿨음 · 다음 회차 시작";
            else label = "다음 회차 시작";
            final boolean done = m.planDone();
            TextView b = U.button(a, label, 0, x -> {
                TimerCenter.acknowledge(a, unit);
                if (done) { a.closeSheet(); ResultSheet.show(a, unit); }
                else { TimerCenter.startRound(a, unit); a.closeSheet(); }
                tick();
            });
            plan.addView(b, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 50)), a, 0, 4, 0, 0));
        }
        LinearLayout r = U.row(a);
        action(r, "결과 입력", U.INK, x -> { a.closeSheet(); ResultSheet.show(a, unit); });
        action(r, "판 끝내기", U.BAD, x -> { TimerCenter.endPlan(a, unit); planSig = -1; tick(); });
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
        boolean run = m.endAt > System.currentTimeMillis();
        for (int i = 1; i <= m.planTotal(); i++) {
            if (i == m.planA + 1 && m.planB > 0) {
                TextView sw = U.tv(a, m.swapped ? "교체✓" : "교체", 10.5f, m.swapped ? U.OK : U.SIGNAL, U.BOLD);
                sw.setGravity(Gravity.CENTER);
                sw.setPadding(U.dp(a, 5), 0, U.dp(a, 5), 0);
                r.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, U.dp(a, 40)));
            }
            String label = i <= m.planA ? String.valueOf(i) : "좌" + (i - m.planA);
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

    // ---------------- 다중 시트 ----------------
    private void showMultiSheet() {
        List<Integer> l = new ArrayList<>(sel);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(" · "); sb.append(l.get(i)); }
        String title = l.size() > 6 ? l.size() + "대 동시 시작" : sb + "호기 동시 시작";
        LinearLayout s = a.sheetBox(title, null);
        s.addView(presetGrid(min -> {
            for (int u : sel) TimerCenter.startSingle(a, u, min);
            Toast.makeText(a, sel.size() + "대 " + min + "분 시작", Toast.LENGTH_SHORT).show();
            exitMulti();
        }), U.match());
        LinearLayout acts = U.row(a);
        action(acts, "판 시작", U.INK, v -> {
            int pa = Store.getInt(a, "planA", 3), pb = Store.getInt(a, "planB", 2), rm = Store.getInt(a, "roundMin", 20);
            for (int u : sel) TimerCenter.startPlan(a, u, pa, pb, rm);
            Toast.makeText(a, sel.size() + "대 판 시작 (" + pa + "회 + 좌우 " + pb + "회 · " + rm + "분)", Toast.LENGTH_LONG).show();
            exitMulti();
        });
        action(acts, "선택 해제", U.INK, v -> exitMulti());
        action(acts, "선택 정지", U.BAD, v -> { for (int u : sel) TimerCenter.stop(a, u); exitMulti(); });
        s.addView(acts, U.match());
        TextView tip = U.tv(a, "다른 호기를 탭하면 추가·해제 · 판 시작은 마지막에 쓴 계획 사용", 11.5f, U.SUB, null);
        tip.setPadding(U.dp(a, 4), U.dp(a, 8), 0, 0);
        s.addView(tip);
        sheetRefresh = null;
        a.showSheet(s, null);
    }
}
