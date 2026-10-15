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
            } else if (m.paused()) {
                setBackground(U.box(c, U.SCREEN, 7, U.WARN, 3));
                fg = U.PLATE; sub = Color.rgb(0xe8, 0xc0, 0x6a);
                st.setText("일시정지");
                time.setText(U.mmss(m.pausedLeft));
                time.setTextSize(25);
                time.setTextColor((now / 600) % 2 == 0 ? Color.rgb(0xe8, 0xc0, 0x6a) : Color.argb(110, 232, 192, 106));
                double tot = m.durMin * 60_000.0;
                prog.frac = (float) Math.max(0, Math.min(1, 1 - m.pausedLeft / tot));
                prog.color = Color.rgb(0xe8, 0xc0, 0x6a); prog.track = Color.argb(46, 232, 192, 106);
            } else {
                setBackground(U.dashed(c, U.PLATE, 7, U.DASH));
                fg = U.INK; sub = Color.rgb(0x8b, 0x96, 0x8c);
                if (m.planActive && !m.planDone()) st.setText((m.doneRounds) + "/" + m.planTotal() + "회");
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
                check.setBackground(selected ? U.box(c, U.OK, 11, Color.WHITE, 2) : U.box(c, Color.TRANSPARENT, 11, run ? Color.rgb(0x5b, 0x6e, 0x64) : U.DASH, 2));
                if (selected) setForeground(U.box(c, Color.TRANSPARENT, 7, U.OK, 3));
                else setForeground(null);
            } else {
                st.setVisibility(VISIBLE);
                check.setVisibility(GONE);
                setForeground(null);
            }
        }
    }

    static String roundLabel(Store.Machine m) {
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
            for (Store.Machine m : Store.machines(a)) if (m.endAt > 0 || m.paused()) TimerCenter.stop(a, m.unit);
            Toast.makeText(a, "진행 중인 타이머를 모두 정지했습니다", Toast.LENGTH_SHORT).show();
            tick();
            return true;
        });
        g.addView(allTile, cell());
        col.addView(g, U.match());

        hint = U.tv(c, "탭: 개별 타이머 · 길게 누르기: 여러 호기 선택 → 아래 버튼", 12, U.SUB, null);
        hint.setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), U.dp(c, 20));
        col.addView(hint);
        multiPad = new View(c);
        col.addView(multiPad, new LinearLayout.LayoutParams(1, U.dp(c, 120)));

        FrameLayout root = new FrameLayout(c);
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        multiBar = U.col(c);
        multiBar.setBackground(U.box(c, U.INK, 12, U.INK, 0));
        multiBar.setElevation(U.dp(c, 8));
        multiBar.setPadding(U.dp(c, 10), U.dp(c, 10), U.dp(c, 10), U.dp(c, 10));
        multiInfo = U.tv(c, "", 13, U.PHOS, U.MONO_B);
        multiInfo.setPadding(U.dp(c, 4), 0, 0, U.dp(c, 8));
        multiBar.addView(multiInfo);
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
        return root;
    }

    private GridLayout.LayoutParams cell() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        p.width = 0;
        p.height = U.dp(a, 88);
        p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
        return p;
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
        int run = 0, done = 0, paused = 0;
        for (Store.Machine m : Store.machines(a)) {
            tiles[m.unit].render(m, now);
            if (m.finished) done++;
            else if (m.endAt > now || m.paused()) run++;
            if (m.paused()) paused++;
        }
        sumRun.setText(paused > 0 ? "진행 " + run + " · 멈춤 " + paused : "진행 " + run);
        sumDone.setText("완료 " + done);
        sumIdle.setText("대기 " + (Store.UNITS - run - done));
        sumDone.setVisibility(done > 0 ? View.VISIBLE : View.GONE);
        sumRow.setVisibility(multi ? View.GONE : View.VISIBLE);
        selBar.setVisibility(multi ? View.VISIBLE : View.GONE);
        selText.setText(sel.size() + "대 선택됨");
        allTile.setText(multi ? "전체\n선택" : "진행 중\n모두 정지");
        multiBar.setVisibility(multi ? View.VISIBLE : View.GONE);
        multiPad.setVisibility(multi ? View.VISIBLE : View.GONE);
        multiInfo.setText(sel.isEmpty() ? "호기를 눌러 고르세요" : selLabel() + " 선택");
        if (sheetRefresh != null && a.sheetOpen()) sheetRefresh.run();
    }

    // ---------------- 조작 ----------------
    private void onTap(int unit) {
        if (multi) {
            if (sel.contains(unit)) sel.remove(unit); else sel.add(unit);
            if (sel.isEmpty()) exitMulti(); else tick();
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
        LinearLayout s = a.sheetBox(unit + "호기 타이머", m0.spec.isEmpty() ? null : m0.spec);
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
            TextView b = U.button(a, done ? "가공 결과 입력" : "다음 회차 시작", 0, x -> {
                TimerCenter.acknowledge(a, unit);
                if (done) { a.closeSheet(); ResultSheet.show(a, unit); }
                else { TimerCenter.startRound(a, unit); a.closeSheet(); }
                tick();
            });
            plan.addView(b, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 50)), a, 0, 4, 0, 0));
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
