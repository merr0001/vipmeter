package com.hg.vibmeter;

import android.app.DatePickerDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/** 날짜별 인수인계: 캘린더 → 날짜 → 인수인계 보기/수정 */
final class HandoverPages {
    private HandoverPages() {}

    static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
    static final String[] WEEK = {"일", "월", "화", "수", "목", "금", "토"};

    static String day(long t) { return DAY.format(new Date(t)); }

    static Calendar parse(String d) {
        Calendar c = Calendar.getInstance();
        try { c.setTime(DAY.parse(d)); } catch (Exception ignored) {}
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static String label(String d) {
        Calendar c = parse(d);
        return (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH) + " (" + WEEK[c.get(Calendar.DAY_OF_WEEK) - 1] + ")";
    }

    /** 지금 쓰는 인수인계의 근무 날짜와 근무조 */
    static String[] currentShift() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 12) { c.add(Calendar.DAY_OF_MONTH, -1); return new String[]{day(c.getTimeInMillis()), "야간"}; }
        if (h >= 18) return new String[]{day(c.getTimeInMillis()), "야간"};
        return new String[]{day(c.getTimeInMillis()), "주간"};
    }

    /** 근무 시간대 [시작, 끝) */
    static long[] window(String date, String shift) {
        Calendar c = parse(date);
        if ("야간".equals(shift)) {
            c.set(Calendar.HOUR_OF_DAY, 12);
            long s = c.getTimeInMillis();
            return new long[]{s, s + 24L * 3600_000};
        }
        c.set(Calendar.HOUR_OF_DAY, 6);
        long s = c.getTimeInMillis();
        return new long[]{s, s + 16L * 3600_000};
    }

    /** 타이머 회차 기록으로 표 채우기: 가공한 호기 · 핀 스펙 · 횟수 */
    static List<Store.HandRow> autoRows(Context c, String date, String shift) {
        long[] w = window(date, shift);
        TreeMap<Integer, int[]> cnt = new TreeMap<>();
        TreeMap<Integer, String> spec = new TreeMap<>();
        TreeMap<Integer, Long> lastT = new TreeMap<>();
        for (Store.Round r : Store.rounds(c)) {
            if (r.end < w[0] || r.end >= w[1]) continue;
            cnt.computeIfAbsent(r.unit, k -> new int[1])[0]++;
            Long lt = lastT.get(r.unit);
            if (lt == null || r.end > lt) { lastT.put(r.unit, r.end); spec.put(r.unit, r.spec); }
        }
        long now = System.currentTimeMillis();
        Set<Integer> live = new HashSet<>();
        if (now >= w[0] && now < w[1]) {
            for (Store.Machine m : Store.machines(c)) {
                if (m.planActive && !m.planDone()) {
                    live.add(m.unit);
                    cnt.computeIfAbsent(m.unit, k -> new int[1]);
                    if (!spec.containsKey(m.unit) || spec.get(m.unit).isEmpty()) spec.put(m.unit, m.spec);
                }
            }
        }
        List<Store.HandRow> l = new ArrayList<>();
        for (int u : cnt.keySet()) {
            Store.Machine m = Store.machine(c, u);
            Store.HandRow r = new Store.HandRow();
            r.unit = String.valueOf(u);
            String full = m.planActive && !m.spec.isEmpty() ? m.spec : (spec.get(u) == null || spec.get(u).isEmpty() ? m.spec : spec.get(u));
            String[] sk = Kind.split(full);
            r.spec = sk[0];
            r.state = sk[1];
            // 다음 근무자가 할 일: 남은 횟수, 다 끝났으면 외관확인
            if (m.planActive && !m.planDone()) r.count = String.valueOf(Math.max(1, m.planTotal() - m.doneRounds));
            else r.count = Store.HandRow.VISUAL;
            l.add(r);
        }
        return l;
    }

    static int totalRounds(Store.Handover h) {
        int t = 0;
        for (Store.HandRow r : h.rows) t += r.rounds();
        return t;
    }

    // ================= 캘린더 =================
    static class DayCell extends View {
        int dayNum; boolean out, sun, sat, today, sel, rec, res, ho;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DayCell(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight(), d = U.dp(getContext(), 1);
            RectF r = new RectF(1.5f * d, 1.5f * d, W - 1.5f * d, H - 1.5f * d);
            if (sel) { p.setStyle(Paint.Style.FILL); p.setColor(U.INK); cv.drawRoundRect(r, 6 * d, 6 * d, p); }
            if (today) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d); p.setColor(U.SIGNAL); cv.drawRoundRect(r, 6 * d, 6 * d, p); }
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(U.MONO_B);
            p.setTextSize(14 * d);
            p.setTextAlign(Paint.Align.CENTER);
            int col = sel ? U.PLATE : out ? Color.rgb(0xb3, 0xbc, 0xb2) : sun ? U.BAD : U.INK;
            p.setColor(col);
            cv.drawText(String.valueOf(dayNum), W / 2, 20 * d, p);
            float y = 30 * d;
            int n = (rec ? 1 : 0) + (res ? 1 : 0);
            float x = W / 2 - (n - 1) * 4.5f * d;
            if (rec) { p.setColor(sel ? U.PLATE : U.INK); cv.drawCircle(x, y, 3 * d, p); x += 9 * d; }
            if (res) { p.setColor(sel ? U.PHOS : U.OK); cv.drawCircle(x, y, 3 * d, p); }
            if (ho) { p.setColor(U.SIGNAL); cv.drawRoundRect(new RectF(7 * d, H - 9 * d, W - 7 * d, H - 5 * d), 2 * d, 2 * d, p); }
        }
    }

    static void openCalendar(MainActivity a) { openCalendar(a, currentShift()[0]); }

    static void openCalendar(MainActivity a, String selDay) {
        Context c = a;
        LinearLayout col = U.col(c);
        col.addView(a.backRow("기록"));
        col.addView(a.header("인수인계", "날짜별"));
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        body.setPadding(U.dp(c, 12), 0, U.dp(c, 12), U.dp(c, 20));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final Calendar month = parse(selDay);
        month.set(Calendar.DAY_OF_MONTH, 1);
        final String[] sel = {selDay};
        Runnable[] render = new Runnable[1];
        render[0] = () -> renderCalendar(a, body, month, sel, render[0]);
        render[0].run();
        a.pushPage(col, null);
    }

    private static void renderCalendar(MainActivity a, LinearLayout body, Calendar month, String[] sel, Runnable again) {
        Context c = a;
        body.removeAllViews();
        // 표시할 날짜들
        Set<String> recDays = new HashSet<>(), resDays = new HashSet<>(), hoDays = new HashSet<>();
        List<Store.Record> recs = Store.records(a);
        List<Store.Result> results = Store.results(a);
        List<Store.Handover> hos = Store.handovers(a);
        for (Store.Record r : recs) recDays.add(day(r.time));
        for (Store.Result r : results) resDays.add(day(r.time));
        for (Store.Handover h : hos) hoDays.add(h.date);
        String today = day(System.currentTimeMillis());

        // 월 이동
        LinearLayout nav = U.row(c);
        TextView prev = navBtn(c, "‹");
        prev.setOnClickListener(v -> { month.add(Calendar.MONTH, -1); again.run(); });
        TextView next = navBtn(c, "›");
        next.setOnClickListener(v -> { month.add(Calendar.MONTH, 1); again.run(); });
        TextView title = U.tv(c, month.get(Calendar.YEAR) + "년 " + (month.get(Calendar.MONTH) + 1) + "월", 20, U.INK, U.BOLD);
        title.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(title, U.lpW(1));
        nav.addView(next);
        body.addView(nav, U.margins(U.match(), c, 4, 0, 4, 8));

        LinearLayout cal = U.col(c);
        cal.setBackground(U.box(c, U.PLATE, 8, U.RULE, 1));
        cal.setPadding(U.dp(c, 6), U.dp(c, 8), U.dp(c, 6), U.dp(c, 6));
        LinearLayout wk = U.row(c);
        for (int i = 0; i < 7; i++) {
            TextView t = U.tv(c, WEEK[i], 11, i == 0 ? U.BAD : U.SUB, null);
            t.setGravity(Gravity.CENTER);
            wk.addView(t, U.lpW(1));
        }
        cal.addView(wk, U.margins(U.match(), c, 0, 0, 0, 4));
        GridLayout g = new GridLayout(c);
        g.setColumnCount(7);
        Calendar d = (Calendar) month.clone();
        d.add(Calendar.DAY_OF_MONTH, -(d.get(Calendar.DAY_OF_WEEK) - 1));
        int curMonth = month.get(Calendar.MONTH);
        for (int i = 0; i < 42; i++) {
            DayCell cell = new DayCell(c);
            String key = day(d.getTimeInMillis());
            cell.dayNum = d.get(Calendar.DAY_OF_MONTH);
            cell.out = d.get(Calendar.MONTH) != curMonth;
            cell.sun = d.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY;
            cell.today = key.equals(today);
            cell.sel = key.equals(sel[0]);
            cell.rec = recDays.contains(key);
            cell.res = resDays.contains(key);
            cell.ho = hoDays.contains(key);
            cell.setOnClickListener(v -> { sel[0] = key; again.run(); });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            p.width = 0; p.height = U.dp(c, 48);
            g.addView(cell, p);
            d.add(Calendar.DAY_OF_MONTH, 1);
            if (i == 34 && d.get(Calendar.MONTH) != curMonth) break; // 5줄로 끝나는 달
        }
        cal.addView(g, U.match());
        body.addView(cal, U.match());
        TextView legend = U.tv(c, "● 측정 기록   ● 가공 결과   ━ 인수인계 저장됨", 11, U.SUB, null);
        legend.setPadding(U.dp(c, 4), U.dp(c, 8), 0, U.dp(c, 4));
        body.addView(legend);

        // 고른 날
        int nRec = 0, nRes = 0;
        for (Store.Record r : recs) if (day(r.time).equals(sel[0])) nRec++;
        for (Store.Result r : results) if (day(r.time).equals(sel[0])) nRes++;
        LinearLayout box = U.panel(c);
        LinearLayout bt = U.row(c);
        bt.addView(U.tv(c, label(sel[0]), 17, U.INK, U.BOLD), U.lpW(1));
        bt.addView(U.tv(c, "측정 " + nRec + "건 · 가공 결과 " + nRes + "건", 11.5f, U.SUB, null));
        box.addView(bt, U.margins(U.match(), c, 0, 0, 0, 8));
        boolean any = false;
        for (Store.Handover h : hos) {
            if (!h.date.equals(sel[0])) continue;
            any = true;
            box.addView(hoCard(a, h), U.margins(U.match(), c, 0, 0, 0, 8));
        }
        if (!any) box.addView(U.tv(c, "저장된 인수인계가 없습니다", 13, U.SUB, null), U.margins(U.match(), c, 0, 0, 0, 8));
        TextView write = U.button(c, "이 날짜로 인수인계 작성", 1, v -> {
            String[] cs = currentShift();
            String shift = sel[0].equals(cs[0]) ? cs[1] : "야간";
            openEditor(a, newHandover(a, sel[0], shift), true);
        });
        box.addView(write, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 46)));
        body.addView(box, U.margins(U.match(), c, 0, 6, 0, 0));

        TextView now = U.button(c, "오늘 인수인계 작성", 0, v -> {
            String[] cs = currentShift();
            for (Store.Handover h : Store.handovers(a))
                if (h.date.equals(cs[0]) && h.shift.equals(cs[1])) { openEditor(a, h, false); return; }
            openEditor(a, newHandover(a, cs[0], cs[1]), true);
        });
        body.addView(now, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 54)), c, 0, 12, 0, 0));
    }

    private static TextView navBtn(Context c, String t) {
        TextView b = U.tv(c, t, 18, U.SUB, U.MONO_B);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.box(c, U.PLATE, 6, U.RULE, 1));
        b.setLayoutParams(new LinearLayout.LayoutParams(U.dp(c, 44), U.dp(c, 36)));
        return b;
    }

    private static View hoCard(MainActivity a, Store.Handover h) {
        Context c = a;
        LinearLayout card = U.col(c);
        card.setBackground(U.box(c, U.ENAMEL, 6, U.RULE, 1));
        card.setPadding(U.dp(c, 11), U.dp(c, 9), U.dp(c, 11), U.dp(c, 9));
        LinearLayout t = U.row(c);
        TextView sh = U.tv(c, h.shift, 11, U.PHOS, U.BOLD);
        sh.setBackground(U.box(c, U.SCREEN, 3, U.SCREEN, 0));
        sh.setPadding(U.dp(c, 7), U.dp(c, 2), U.dp(c, 7), U.dp(c, 2));
        t.addView(sh);
        TextView sum = U.tv(c, "  " + h.rows.size() + "대 인수인계", 14, U.INK, U.BOLD);
        t.addView(sum, U.lpW(1));
        t.addView(U.tv(c, "저장 " + U.time(h.savedAt, "M/d HH:mm") + "  ›", 11, U.SUB, null));
        card.addView(t);
        StringBuilder sb = new StringBuilder();
        for (Store.HandRow r : h.rows) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(r.unit).append("호기 ").append(r.specLabel()).append(" ").append(r.todo());
        }
        if (sb.length() > 0) card.addView(U.tv(c, sb.toString(), 12, U.INK, U.MONO), U.margins(U.match(), c, 0, 6, 0, 0));
        if (!h.memo.isEmpty()) {
            TextView m = U.tv(c, h.memo, 12, U.PHOS, U.MONO);
            m.setBackground(U.box(c, U.SCREEN, 5, U.SCREEN, 0));
            m.setPadding(U.dp(c, 9), U.dp(c, 7), U.dp(c, 9), U.dp(c, 7));
            card.addView(m, U.margins(U.match(), c, 0, 6, 0, 0));
        }
        card.setOnClickListener(v -> openEditor(a, h, false));
        return card;
    }

    static Store.Handover newHandover(Context c, String date, String shift) {
        Store.Handover h = new Store.Handover();
        h.id = System.currentTimeMillis();
        h.date = date;
        h.shift = shift;
        h.author = Store.getStr(c, "author", "박동원");
        h.rows = autoRows(c, date, shift);
        return h;
    }

    // ================= 작성 · 수정 =================
    static void openEditor(MainActivity a, Store.Handover h, boolean isNew) {
        Context c = a;
        LinearLayout col = U.col(c);
        TextView back = U.tv(c, "‹ 캘린더", 14, U.SUB, null);
        back.setPadding(U.dp(c, 16), U.dp(c, 12), U.dp(c, 16), U.dp(c, 4));
        back.setOnClickListener(v -> openCalendar(a, h.date));
        col.addView(back);
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        body.setPadding(U.dp(c, 12), 0, U.dp(c, 12), U.dp(c, 24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 날짜 + 근무조
        LinearLayout top = U.row(c);
        top.setPadding(U.dp(c, 4), U.dp(c, 4), U.dp(c, 4), U.dp(c, 10));
        TextView date = U.tv(c, label(h.date) + " ▾", 22, U.INK, U.BOLD);
        date.setOnClickListener(v -> {
            Calendar cl = parse(h.date);
            new DatePickerDialog(a, (dp, y, mo, dd) -> {
                Calendar n = Calendar.getInstance();
                n.set(y, mo, dd);
                h.date = day(n.getTimeInMillis());
                date.setText(label(h.date) + " ▾");
            }, cl.get(Calendar.YEAR), cl.get(Calendar.MONTH), cl.get(Calendar.DAY_OF_MONTH)).show();
        });
        top.addView(date, U.lpW(1));
        TextView[] seg = new TextView[2];
        String[] shifts = {"주간", "야간"};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            seg[i] = U.tv(c, shifts[i], 14, U.INK, U.BOLD);
            seg[i].setGravity(Gravity.CENTER);
            seg[i].setOnClickListener(v -> { h.shift = shifts[k]; paintSeg(c, seg, h.shift); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(U.dp(c, 54), U.dp(c, 36));
            p.leftMargin = U.dp(c, 4);
            top.addView(seg[i], p);
        }
        paintSeg(c, seg, h.shift);
        body.addView(top, U.match());

        // 표 (수정 가능)
        LinearLayout paper = U.col(c);
        paper.setBackground(U.box(c, Color.rgb(0xfb, 0xfc, 0xfa), 6, U.RULE, 1));
        paper.setPadding(U.dp(c, 10), U.dp(c, 10), U.dp(c, 10), U.dp(c, 10));
        LinearLayout ph = U.row(c);
        ph.addView(U.tv(c, "H.G 인수인계 · 다음 근무자가 할 일", 15, U.INK, U.BOLD), U.lpW(1));
        ph.addView(U.tv(c, isNew ? "새로 작성" : "저장 " + U.time(h.savedAt, "M/d HH:mm"), 11, U.SUB, null));
        paper.addView(ph);
        View rule = new View(c);
        rule.setBackgroundColor(U.INK);
        paper.addView(rule, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 2)), c, 0, 6, 0, 4));
        LinearLayout rowsBox = U.col(c);
        paper.addView(rowsBox, U.match());
        List<EditText[]> fields = new ArrayList<>();
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            rowsBox.removeAllViews();
            fields.clear();
            for (Store.HandRow r : h.rows) addRowView(a, rowsBox, fields, h, r, rebuild[0]);
            if (h.rows.isEmpty()) rowsBox.addView(U.tv(c, "가공한 호기가 없습니다 · 아래에서 줄을 추가하세요", 12, U.SUB, null), U.margins(U.match(), c, 0, 8, 0, 8));
        };
        rebuild[0].run();
        LinearLayout rowActs = U.row(c);
        TextView add = smallBtn(c, "+ 줄 추가");
        add.setOnClickListener(v -> { pull(h, fields); h.rows.add(new Store.HandRow()); rebuild[0].run(); });
        add.setText("+ 호기 추가");
        rowActs.addView(add, new LinearLayout.LayoutParams(0, U.dp(c, 40), 1));
        TextView auto = smallBtn(c, "상황판 기록으로 다시 채우기");
        auto.setOnClickListener(v -> new android.app.AlertDialog.Builder(a)
                .setTitle("상황판 기록으로 다시 채울까요?")
                .setMessage("지금 표의 내용은 바뀝니다. 메모는 그대로 둡니다.")
                .setPositiveButton("다시 채우기", (dlg, w) -> { h.rows = autoRows(a, h.date, h.shift); rebuild[0].run(); })
                .setNegativeButton("취소", null).show());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, U.dp(c, 40), 1.6f);
        al.leftMargin = U.dp(c, 6);
        rowActs.addView(auto, al);
        paper.addView(rowActs, U.margins(U.match(), c, 0, 8, 0, 0));

        EditText memo = new EditText(c);
        memo.setHint("메모 (예: 14호기 전장 공차 끝, 재측정 필요)");
        memo.setText(h.memo);
        memo.setTextSize(13);
        memo.setMinLines(3);
        memo.setGravity(Gravity.TOP | Gravity.START);
        memo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        memo.setBackground(U.box(c, U.ENAMEL, 4, U.ENAMEL, 0));
        memo.setPadding(U.dp(c, 9), U.dp(c, 8), U.dp(c, 9), U.dp(c, 8));
        paper.addView(memo, U.margins(U.match(), c, 0, 10, 0, 0));
        body.addView(paper, U.match());

        // 저장
        Runnable collect = () -> { pull(h, fields); h.memo = memo.getText().toString().trim(); h.savedAt = System.currentTimeMillis(); };
        LinearLayout btns = U.row(c);
        TextView save = U.button(c, "저장", 1, v -> {
            collect.run();
            Store.putHandover(a, h);
            Toast.makeText(a, label(h.date) + " " + h.shift + " 인수인계 저장", Toast.LENGTH_SHORT).show();
            openCalendar(a, h.date);
        });
        btns.addView(save, new LinearLayout.LayoutParams(0, U.dp(c, 54), 1));
        TextView send = U.button(c, "저장 + 카톡", 0, v -> {
            collect.run();
            Store.putHandover(a, h);
            shareImage(a, h);
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, U.dp(c, 54), 1.4f);
        sl.leftMargin = U.dp(c, 8);
        btns.addView(send, sl);
        body.addView(btns, U.margins(U.match(), c, 0, 12, 0, 0));
        TextView txt = U.tv(c, "글로 보내기", 14, U.SUB, U.BOLD);
        txt.setGravity(Gravity.CENTER);
        txt.setPadding(0, U.dp(c, 14), 0, U.dp(c, 4));
        txt.setOnClickListener(v -> { collect.run(); Store.putHandover(a, h); shareText(a, h); });
        body.addView(txt, U.match());
        if (!isNew) {
            TextView del = U.tv(c, "이 인수인계 삭제 (길게 누르기)", 13, U.BAD, null);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, U.dp(c, 14), 0, 0);
            del.setOnClickListener(v -> Toast.makeText(a, "지우려면 길게 누르세요", Toast.LENGTH_SHORT).show());
            del.setOnLongClickListener(v -> { Store.deleteHandover(a, h.id); openCalendar(a, h.date); return true; });
            body.addView(del, U.match());
        }
        a.pushPage(col, null);
    }

    private static void paintSeg(Context c, TextView[] seg, String shift) {
        String[] shifts = {"주간", "야간"};
        for (int i = 0; i < 2; i++) {
            boolean on = shifts[i].equals(shift);
            seg[i].setBackground(U.box(c, on ? U.SCREEN : U.PLATE, 6, U.INK, on ? 0 : 1.5f));
            seg[i].setTextColor(on ? U.PHOS : U.INK);
        }
    }

    private static TextView smallBtn(Context c, String t) {
        TextView b = U.tv(c, t, 13, U.INK, U.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(U.dashed(c, Color.TRANSPARENT, 6, U.INK));
        return b;
    }

    /** 인수인계 카드 한 장: 호기 · 핀 스펙(가이드에서 고르기) · 상태 · 가공 횟수(최대 10) · 특이사항 */
    private static void addRowView(MainActivity a, LinearLayout box, List<EditText[]> fields, Store.Handover h, Store.HandRow r, Runnable rebuild) {
        Context c = a;
        LinearLayout card = U.col(c);
        card.setBackground(U.box(c, U.PLATE, 9, U.RULE, 1));
        card.setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 10));
        // 1줄: 호기 + 핀 스펙 + ✕
        LinearLayout r1 = U.row(c);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        EditText u = cellEdit(c, r.unit, InputType.TYPE_CLASS_NUMBER, 20, true);
        u.setHint("호기");
        u.setGravity(Gravity.CENTER);
        r1.addView(u, new LinearLayout.LayoutParams(U.dp(c, 46), ViewGroup.LayoutParams.WRAP_CONTENT));
        r1.addView(U.tv(c, "호기", 11, U.SUB, null));
        LinearLayout sp = U.row(c);
        sp.setGravity(Gravity.CENTER_VERTICAL);
        sp.setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 8));
        if (r.spec.isEmpty()) {
            sp.setBackground(U.dashed(c, Color.WHITE, 7, Color.rgb(0x8c, 0x96, 0x8b)));
            sp.addView(U.tv(c, "눌러서 핀 스펙 고르기", 14, Color.rgb(0x8c, 0x96, 0x8b), U.BOLD), U.lpW(1));
        } else {
            sp.setBackground(U.box(c, Color.WHITE, 7, U.INK, 2));
            sp.addView(U.tv(c, r.spec, 18, U.INK, U.MONO_B));
            if (!r.state.isEmpty()) sp.addView(Kind.tag(c, r.state, 11), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 6, 0, 0, 0));
            sp.addView(new View(c), U.lpW(1));
            sp.addView(U.tv(c, "▾ 바꾸기", 11, U.SUB, null));
        }
        sp.setOnClickListener(v -> { pull(h, fields); pickSpec(a, r, rebuild); });
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        spl.leftMargin = U.dp(c, 8);
        r1.addView(sp, spl);
        TextView x = U.tv(c, "✕", 17, Color.rgb(0x9a, 0xa4, 0x9a), U.BOLD);
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(v -> { pull(h, fields); int idx = h.rows.indexOf(r); if (idx >= 0) h.rows.remove(idx); rebuild.run(); });
        r1.addView(x, new LinearLayout.LayoutParams(U.dp(c, 34), U.dp(c, 40)));
        card.addView(r1, U.match());

        // 상태
        card.addView(label(c, "상태"));
        LinearLayout st = U.row(c);
        List<String> kinds = new ArrayList<>(java.util.Arrays.asList(Kind.LIST));
        if (!r.state.isEmpty() && Kind.order(r.state) == 50) kinds.add(r.state);
        for (String k : kinds) {
            boolean on = k.equalsIgnoreCase(r.state);
            TextView t = U.tv(c, k, 12.5f, on ? U.PLATE : U.INK, U.MONO_B);
            t.setGravity(Gravity.CENTER);
            t.setBackground(on ? U.box(c, U.INK, 6, U.INK, 0) : U.box(c, Color.rgb(0xf3, 0xf5, 0xf1), 6, Color.rgb(0xa9, 0xb3, 0xa8), 1));
            t.setOnClickListener(v -> { pull(h, fields); r.state = on ? "" : k; rebuild.run(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.dp(c, 34), 1);
            p.rightMargin = U.dp(c, 3);
            st.addView(t, p);
        }
        card.addView(st, U.match());

        // 가공할 횟수: 1~10회 중 하나 또는 외관확인
        card.addView(label(c, "가공할 횟수"));
        int n = r.visual() ? 0 : r.rounds();
        GridLayout cg = new GridLayout(c);
        cg.setColumnCount(5);
        for (int i = 1; i <= 10; i++) {
            final int k = i;
            boolean on = i == n;
            TextView t = U.tv(c, i + "회", 14, on ? Color.WHITE : U.SUB, U.MONO_B);
            t.setGravity(Gravity.CENTER);
            t.setBackground(on ? U.box(c, U.SIGNAL, 6, U.SIGNAL_DK, 1) : U.box(c, Color.rgb(0xf3, 0xf5, 0xf1), 6, Color.rgb(0xa9, 0xb3, 0xa8), 1));
            t.setOnClickListener(v -> { pull(h, fields); r.count = on ? "" : String.valueOf(k); rebuild.run(); });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            p.width = 0; p.height = U.dp(c, 36);
            p.setMargins(U.dp(c, 2), U.dp(c, 2), U.dp(c, 2), U.dp(c, 2));
            cg.addView(t, p);
        }
        card.addView(cg, U.match());
        boolean vis = r.visual();
        TextView vb = U.tv(c, vis ? "✓ 외관확인" : "외관확인", 14.5f, vis ? Color.WHITE : U.INK, U.BOLD);
        vb.setGravity(Gravity.CENTER);
        vb.setBackground(vis ? U.box(c, U.OK, 7, U.OK, 0) : U.box(c, Color.rgb(0xf3, 0xf5, 0xf1), 7, Color.rgb(0x8c, 0x96, 0x8b), 1.5f));
        vb.setOnClickListener(v -> { pull(h, fields); r.count = vis ? "" : Store.HandRow.VISUAL; rebuild.run(); });
        card.addView(vb, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 40)), c, 2, 4, 2, 0));

        // 특이사항
        card.addView(label(c, "특이사항"));
        EditText note = new EditText(c);
        note.setText(r.note);
        note.setHint("예: 좌우 2회 남음 · 각도 44°");
        note.setSingleLine(true);
        note.setTextSize(14);
        note.setBackground(U.box(c, Color.WHITE, 6, U.DASH, 1));
        note.setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 8));
        card.addView(note, U.match());

        box.addView(card, U.margins(U.match(), c, 0, 0, 0, 8));
        fields.add(new EditText[]{u, note});
    }

    private static TextView label(Context c, String t) {
        TextView v = U.tv(c, t, 11, U.SUB, null);
        v.setPadding(U.dp(c, 2), U.dp(c, 8), 0, U.dp(c, 4));
        return v;
    }

    /** 가이드에 등록된 스펙에서 고르기 (직경-전장별 묶음) */
    private static void pickSpec(MainActivity a, Store.HandRow r, Runnable rebuild) {
        Context c = a;
        LinearLayout s = a.sheetBox((r.unit.isEmpty() ? "" : r.unit + "호기 ") + "핀 스펙", "가이드에 등록된 스펙");
        String recent = "";
        try { recent = Store.machine(c, Integer.parseInt(r.unit.trim())).spec; } catch (Exception ignored) {}
        if (!recent.isEmpty()) {
            TextView rt = U.tv(c, "● 초록 테두리 = 이 호기에 설정된 스펙", 11, U.OK, U.BOLD);
            rt.setPadding(0, 0, 0, U.dp(c, 6));
            s.addView(rt);
        }
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        sv.addView(body);
        String cur = r.specLabel();
        for (java.util.Map.Entry<String, List<Store.Spec>> e : SpecScreen.groups(Store.specs(c)).entrySet()) {
            LinearLayout row = U.row(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, U.dp(c, 6), 0, U.dp(c, 6));
            row.addView(U.tv(c, e.getKey(), 17, U.INK, U.MONO_B), new LinearLayout.LayoutParams(U.dp(c, 82), ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = U.row(c);
            for (Store.Spec sp : e.getValue()) {
                View b = SpecScreen.kindButton(c, sp, sp.name.equals(cur));
                if (sp.name.equals(recent) && !sp.name.equals(cur))
                    b.setForeground(U.box(c, Color.TRANSPARENT, 8, U.OK, 2.5f));
                b.setOnClickListener(v -> {
                    String[] sk = Kind.split(sp.name);
                    r.spec = sk[0];
                    r.state = sk[1];
                    a.closeSheet();
                    rebuild.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, U.dp(c, 46));
                lp.rightMargin = U.dp(c, 6);
                chips.addView(b, lp);
            }
            hs.addView(chips);
            row.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            body.addView(row, U.match());
            View ln = new View(c);
            ln.setBackgroundColor(Color.rgb(0xd3, 0xda, 0xd1));
            body.addView(ln, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        s.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView g = U.tv(c, "목록에 없으면 가이드 → 스펙 편집에서 등록", 12.5f, U.SUB, null);
        g.setGravity(Gravity.CENTER);
        g.setPadding(0, U.dp(c, 10), 0, 0);
        s.addView(g, U.match());
        a.showSheet(s, null);
    }

    private static EditText cellEdit(Context c, String v, int type, float sp, boolean bold) {
        EditText e = new EditText(c);
        e.setText(v);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(sp);
        e.setTypeface(bold ? U.BOLD : U.MONO_B);
        e.setPadding(U.dp(c, 4), U.dp(c, 8), U.dp(c, 4), U.dp(c, 8));
        return e;
    }

    /** 입력칸 → 데이터 (호기 · 특이사항) */
    private static void pull(Store.Handover h, List<EditText[]> fields) {
        for (int i = 0; i < fields.size() && i < h.rows.size(); i++) {
            Store.HandRow r = h.rows.get(i);
            r.unit = fields.get(i)[0].getText().toString().trim();
            r.note = fields.get(i)[1].getText().toString().trim();
        }
    }

    // ================= 보내기 =================
    /** 보내기용 깔끔한 표 (입력칸 없이) */
    private static View paperView(Context c, Store.Handover h) {
        LinearLayout p = U.col(c);
        p.setBackgroundColor(Color.rgb(0xfb, 0xfc, 0xfa));
        p.setPadding(U.dp(c, 16), U.dp(c, 14), U.dp(c, 16), U.dp(c, 14));
        LinearLayout t = U.row(c);
        t.addView(U.tv(c, "H.G 인수인계 " + label(h.date) + " " + h.shift, 17, U.INK, U.BOLD), U.lpW(1));
        p.addView(t);
        p.addView(U.tv(c, "저장 " + U.time(h.savedAt, "M/d HH:mm") + " · " + h.author, 11.5f, U.SUB, null), U.margins(U.match(), c, 0, 3, 0, 0));
        View rule = new View(c);
        rule.setBackgroundColor(U.INK);
        p.addView(rule, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 2)), c, 0, 8, 0, 4));
        LinearLayout head = U.row(c);
        head.addView(U.tv(c, "호기", 11, U.SUB, null), new LinearLayout.LayoutParams(U.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(U.tv(c, "핀 스펙", 11, U.SUB, null), U.lpW(1));
        TextView hc = U.tv(c, "가공할 횟수", 11, U.SUB, null);
        hc.setGravity(Gravity.END);
        head.addView(hc, U.lpW(1));
        p.addView(head);
        for (Store.HandRow r : h.rows) {
            View line = new View(c);
            line.setBackgroundColor(Color.rgb(0xe3, 0xe8, 0xe1));
            p.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout row = U.row(c);
            row.setPadding(0, U.dp(c, 8), 0, U.dp(c, 8));
            row.addView(U.tv(c, r.unit, 18, U.INK, U.BOLD), new LinearLayout.LayoutParams(U.dp(c, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout spc = U.row(c);
            spc.setGravity(Gravity.CENTER_VERTICAL);
            spc.addView(U.tv(c, r.spec.isEmpty() ? "스펙 미입력" : r.spec, r.spec.isEmpty() ? 13 : 15, r.spec.isEmpty() ? Color.rgb(0xa9, 0xb3, 0xa8) : U.INK, r.spec.isEmpty() ? null : U.MONO_B));
            if (!r.state.isEmpty()) spc.addView(Kind.tag(c, r.state, 12), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 6, 0, 0, 0));
            row.addView(spc, U.lpW(1));
            TextView cnt = U.tv(c, r.todo(), r.visual() ? 15 : 17, r.visual() ? U.OK : U.INK, r.visual() ? U.BOLD : U.MONO_B);
            cnt.setGravity(Gravity.END);
            row.addView(cnt, U.lpW(1));
            p.addView(row, U.match());
            if (!r.note.isEmpty()) {
                TextView nt = U.tv(c, "  └ " + r.note, 12.5f, U.SUB, null);
                nt.setPadding(U.dp(c, 56), 0, 0, U.dp(c, 8));
                p.addView(nt, U.match());
            }
        }
        if (h.rows.isEmpty()) p.addView(U.tv(c, "가공한 호기 없음", 13, U.SUB, null), U.margins(U.match(), c, 0, 8, 0, 8));
        if (!h.memo.isEmpty()) {
            TextView m = U.tv(c, h.memo, 13, U.INK, null);
            m.setBackground(U.box(c, U.ENAMEL, 4, U.ENAMEL, 0));
            m.setPadding(U.dp(c, 10), U.dp(c, 8), U.dp(c, 10), U.dp(c, 8));
            p.addView(m, U.margins(U.match(), c, 0, 10, 0, 0));
        }
        return p;
    }

    private static void shareImage(MainActivity a, Store.Handover h) {
        try {
            View v = paperView(a, h);
            int w = a.getResources().getDisplayMetrics().widthPixels - U.dp(a, 16);
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
            Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "인수인계_" + h.date + "_" + h.shift + "_" + U.time(System.currentTimeMillis(), "HHmmss") + ".png");
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VibMeter");
            Uri uri = a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("이미지를 만들 수 없습니다");
            try (OutputStream o = a.getContentResolver().openOutputStream(uri)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(share, "인수인계 보내기"));
        } catch (Exception e) {
            Toast.makeText(a, "이미지 만들기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void shareText(MainActivity a, Store.Handover h) {
        StringBuilder sb = new StringBuilder("[H.G 인수인계] " + label(h.date) + " " + h.shift + "\n");
        for (Store.HandRow r : h.rows) {
            sb.append(r.unit).append("호기  ").append(r.specLabel()).append("  ").append(r.todo()).append('\n');
            if (!r.note.isEmpty()) sb.append("   - ").append(r.note).append('\n');
        }
        if (!h.memo.isEmpty()) sb.append('\n').append(h.memo);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString());
        a.startActivity(Intent.createChooser(share, "인수인계 보내기"));
    }
}
