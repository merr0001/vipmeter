package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 가이드 탭: 스펙별 목표 + 호기별 실측, 스펙 상세(추천 설정 · 설정 찾기) */
class SpecScreen extends MainActivity.Screen {

    private LinearLayout rows;

    SpecScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        Context c = a;
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        sv.addView(col);
        col.addView(a.header("가이드", "설비별 스펙 주파수 쉽게 찾기"));
        rows = U.panel(c);
        col.addView(rows, U.margins(U.match(), c, 14, 0, 14, 0));
        LinearLayout legend = U.row(c);
        legend.setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), 0);
        legend.addView(U.tv(c, "초록 띠: 목표 범위 · 점: 호기별 최근 측정 · 줄을 누르면 호기별 설정", 11.5f, U.SUB, null));
        col.addView(legend);
        TextView edit = U.button(c, "스펙 편집", 1, v -> openEditor());
        col.addView(edit, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 52)), c, 14, 16, 14, 20));
        return sv;
    }

    @Override void onShow() { refresh(); }

    void refresh() {
        Context c = a;
        rows.removeAllViews();
        List<Store.Spec> specs = Store.specs(a);
        List<Store.Record> recs = Store.records(a);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Store.Spec s : specs) { lo = Math.min(lo, s.target - s.tol); hi = Math.max(hi, s.target + s.tol); }
        lo = Math.floor((lo - 6) / 10) * 10;
        hi = Math.ceil((hi + 6) / 10) * 10;
        for (Store.Spec s : specs) {
            // 호기별 최근 값
            Map<Integer, Store.Record> lastBy = new TreeMap<>();
            for (Store.Record r : recs) if (s.name.equals(r.spec)) {
                Store.Record p = lastBy.get(r.unit);
                if (p == null || r.time > p.time) lastBy.put(r.unit, r);
            }
            LinearLayout row = U.row(c);
            row.setPadding(0, U.dp(c, 6), 0, U.dp(c, 6));
            LinearLayout nm = U.col(c);
            nm.addView(U.tv(c, s.name, 13.5f, U.INK, U.MONO_B));
            nm.addView(U.tv(c, lastBy.isEmpty() ? "기록 없음" : lastBy.size() + "대 기록", 10.5f, U.SUB, null));
            row.addView(nm, new LinearLayout.LayoutParams(U.dp(c, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
            Views.ScaleView sc = new Views.ScaleView(c);
            sc.lo = lo; sc.hi = hi; sc.target = s.target; sc.tol = s.tol;
            double[] mk = new double[lastBy.size()];
            int i = 0;
            for (Store.Record r : lastBy.values()) mk[i++] = r.vRms;
            sc.marks = mk;
            row.addView(sc, new LinearLayout.LayoutParams(0, U.dp(c, 34), 1));
            row.addView(U.tv(c, "  ›", 18, Color.rgb(0x8b, 0x96, 0x8c), U.MONO_B));
            row.setOnClickListener(v -> openDetail(s));
            rows.addView(row, U.match());
            View line = new View(c);
            line.setBackgroundColor(Color.rgb(0xdd, 0xe3, 0xdb));
            rows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        // 눈금 글자
        LinearLayout ticks = U.row(c);
        ticks.setPadding(U.dp(c, 80), U.dp(c, 4), U.dp(c, 18), 0);
        for (double v = lo; v <= hi + 0.1; v += 10) {
            TextView t = U.tv(c, String.format(Locale.US, "%.0f", v), 10.5f, U.SUB, U.MONO);
            t.setGravity(v == lo ? Gravity.START : (v >= hi ? Gravity.END : Gravity.CENTER));
            ticks.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        rows.addView(ticks, U.match());
    }

    // ---------------- 스펙 상세 ----------------
    void openDetail(Store.Spec s) {
        Context c = a;
        LinearLayout col = U.col(c);
        col.addView(a.backRow("가이드"));
        LinearLayout hd = U.row(c);
        hd.setPadding(U.dp(c, 18), 0, U.dp(c, 18), U.dp(c, 10));
        hd.setGravity(Gravity.BOTTOM);
        hd.addView(U.tv(c, s.name, 30, U.INK, U.MONO_B), U.lpW(1));
        LinearLayout tg = U.col(c);
        tg.setGravity(Gravity.END);
        tg.addView(U.tv(c, "목표", 11, U.SUB, null));
        tg.addView(U.tv(c, U.f1(s.target).replace(".0", "") + " ±" + U.f1(s.tol).replace(".0", ""), 20, U.OK, U.MONO_B));
        tg.addView(U.tv(c, "mm/s RMS", 11, U.SUB, null));
        hd.addView(tg);
        col.addView(hd);

        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        body.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 20));
        sv.addView(body);

        Map<Integer, List<Store.Record>> by = new TreeMap<>();
        for (Store.Record r : Store.records(a)) if (s.name.equals(r.spec)) by.computeIfAbsent(r.unit, k -> new ArrayList<>()).add(r);
        List<Store.Result> results = Store.results(a);
        if (by.isEmpty()) body.addView(U.tv(c, "이 스펙의 측정 기록이 아직 없습니다.\n측정 탭에서 호기 · 스펙 · 설정 주파수를 넣고 기록하면 여기에 모입니다.", 14, U.SUB, null));
        for (Map.Entry<Integer, List<Store.Record>> e : by.entrySet()) {
            List<Store.Record> l = e.getValue();
            l.sort((x, y) -> Long.compare(x.time, y.time));
            body.addView(unitCard(s, e.getKey(), l, results), U.margins(U.match(), c, 0, 0, 0, 10));
        }
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        a.pushPage(col, null);
    }

    private View unitCard(Store.Spec s, int unit, List<Store.Record> l, List<Store.Result> results) {
        Context c = a;
        LinearLayout card = U.col(c);
        card.setBackground(U.box(c, U.PLATE, 7, U.RULE, 1));

        Store.Record best = null;
        for (Store.Record r : l) if (U.judge(s, r.vRms) == 0) best = r;   // 범위 안 중 가장 최근
        Store.Record shown = best != null ? best : l.get(l.size() - 1);

        LinearLayout top = U.row(c);
        top.setPadding(0, U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        LinearLayout no = U.col(c);
        no.setGravity(Gravity.CENTER);
        no.addView(U.tv(c, String.valueOf(unit), 26, U.INK, U.BOLD));
        no.addView(U.tv(c, "호기", 10, U.SUB, null));
        top.addView(no, new LinearLayout.LayoutParams(U.dp(c, 62), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout set = U.col(c);
        set.addView(U.tv(c, best != null ? "추천 설정 주파수" : "최근 설정 (범위 밖)", 10.5f, U.SUB, null));
        LinearLayout hz = U.row(c);
        hz.setGravity(Gravity.BOTTOM);
        hz.addView(U.tv(c, U.f1(shown.hz()), 26, best != null ? U.INK : U.SUB, U.MONO_B));
        hz.addView(U.tv(c, " Hz", 13, U.SUB, U.MONO));
        set.addView(hz);
        top.addView(set, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout res = U.col(c);
        res.setGravity(Gravity.END);
        res.addView(U.tv(c, U.f1(shown.vRms), 18, U.INK, U.MONO_B));
        res.addView(U.tv(c, "mm/s", 10, U.SUB, U.MONO));
        int j = U.judge(s, shown.vRms);
        res.addView(U.tag(c, U.judgeText(j), j), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 0, 3, 0, 0));
        top.addView(res);
        card.addView(top);

        View dash = new View(c);
        dash.setBackground(U.dashed(c, Color.TRANSPARENT, 0, U.RULE));
        card.addView(dash, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 1)));

        // 이력 (최근 순)
        LinearLayout hist = U.col(c);
        hist.setPadding(U.dp(c, 62), U.dp(c, 6), U.dp(c, 12), U.dp(c, 8));
        for (int i = l.size() - 1; i >= Math.max(0, l.size() - 6); i--) {
            Store.Record r = l.get(i);
            LinearLayout hr = U.row(c);
            hr.addView(U.tv(c, U.time(r.time, "M/d HH:mm"), 11.5f, U.SUB, U.MONO), new LinearLayout.LayoutParams(U.dp(c, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
            hr.addView(U.tv(c, U.f1(r.hz()), 12.5f, U.INK, U.MONO_B), new LinearLayout.LayoutParams(U.dp(c, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
            hr.addView(U.tv(c, "→ " + U.f1(r.vRms), 12, U.SUB, U.MONO), U.lpW(1));
            int jj = U.judge(s, r.vRms);
            hr.addView(U.tag(c, U.judgeText(jj), jj));
            hist.addView(hr, U.margins(U.match(), c, 0, 2, 0, 2));
        }
        card.addView(hist);

        // 가공 결과 요약
        Store.Result lastRes = null;
        for (Store.Result r : results) if (r.unit == unit && s.name.equals(r.spec) && (lastRes == null || r.time > lastRes.time)) lastRes = r;
        if (lastRes != null) {
            StringBuilder sb = new StringBuilder("가공 결과  ");
            if (!Double.isNaN(lastRes.setHz)) sb.append(U.f1(lastRes.setHz)).append(" Hz · ");
            if (lastRes.rounds > 0) sb.append(lastRes.rounds).append("회 ").append(lastRes.minutes).append("분 · ");
            if (!Double.isNaN(lastRes.angle)) sb.append(U.f1(lastRes.angle).replace(".0", "")).append("° · ");
            if (!Double.isNaN(lastRes.length)) sb.append("전장 ").append(U.f3(lastRes.length)).append(" · ");
            if (!lastRes.rShape.isEmpty()) sb.append("R ").append(lastRes.rShape);
            TextView rt = U.tv(c, sb.toString().replaceAll(" · $", ""), 12, U.PHOS, U.MONO);
            rt.setBackground(U.box(c, U.SCREEN, 6, U.SCREEN, 0));
            rt.setPadding(U.dp(c, 12), U.dp(c, 9), U.dp(c, 12), U.dp(c, 9));
            card.addView(rt, U.margins(U.match(), c, 10, 0, 10, 8));
        }

        // 설정 찾기 (점 2개 이상)
        LinkedHashMap<Double, Double> pts = new LinkedHashMap<>();
        for (Store.Record r : l) pts.put(Math.round(r.hz() * 10) / 10.0, r.vRms); // 같은 설정은 최근 값
        if (pts.size() >= 2) {
            double[] fx = new double[pts.size()], vy = new double[pts.size()];
            int i = 0;
            for (Map.Entry<Double, Double> p : pts.entrySet()) { fx[i] = p.getKey(); vy[i] = p.getValue(); i++; }
            double est = Views.estimate(fx, vy, s.target);
            LinearLayout fb = U.col(c);
            fb.setPadding(U.dp(c, 12), U.dp(c, 4), U.dp(c, 12), U.dp(c, 10));
            fb.addView(U.sectionTitle(c, "설정 찾기 · 설정 주파수 → 진동 속도"));
            Views.CurveView cvw = new Views.CurveView(c);
            cvw.fx = fx; cvw.vy = vy; cvw.target = s.target; cvw.tol = s.tol; cvw.estHz = est;
            fb.addView(cvw, U.match());
            LinearLayout w = U.window(c);
            w.addView(U.tv(c, "목표에 맞는 설정 (추정)", 11.5f, U.PHOS_DIM, U.MONO));
            LinearLayout er = U.row(c);
            er.setGravity(Gravity.BOTTOM);
            er.addView(U.tv(c, Double.isNaN(est) ? "계산 불가" : U.f1(est), Double.isNaN(est) ? 18 : 32, Color.rgb(0xff, 0xb4, 0x8f), U.MONO_B));
            if (!Double.isNaN(est)) er.addView(U.tv(c, " Hz 근처", 13, U.PHOS_DIM, U.MONO));
            w.addView(er);
            w.addView(U.tv(c, Double.isNaN(est) ? "목표를 사이에 둔 측정이 더 필요합니다" : "측정한 점 사이 기울기로 계산 · 맞춘 뒤 꼭 재측정", 11, U.PHOS_DIM, U.MONO));
            fb.addView(w, U.margins(U.match(), c, 0, 8, 0, 0));
            card.addView(fb);
        }

        final double goHz = pts.size() >= 2 && !Double.isNaN(Views.estimate(toArr(pts, true), toArr(pts, false), s.target)) && best == null
                ? Math.round(Views.estimate(toArr(pts, true), toArr(pts, false), s.target) * 10) / 10.0 : shown.hz();
        TextView go = U.tv(c, "+ " + U.f1(goHz) + " Hz 로 측정하러 가기", 14, U.INK, U.BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(U.dashed(c, Color.TRANSPARENT, 7, U.INK));
        go.setOnClickListener(v -> {
            Store.Machine m = Store.machine(a, unit);
            m.spec = s.name;
            m.setHz = goHz;
            Store.save(a, m);
            a.measure.unit = unit;
            Store.putInt(a, "measureUnit", unit);
            a.showTab(0);
        });
        card.addView(go, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 44)), c, 10, 0, 10, 10));
        return card;
    }

    private static double[] toArr(LinkedHashMap<Double, Double> m, boolean keys) {
        double[] r = new double[m.size()];
        int i = 0;
        for (Map.Entry<Double, Double> e : m.entrySet()) r[i++] = keys ? e.getKey() : e.getValue();
        return r;
    }

    // ---------------- 목표값 편집 ----------------
    // ---------------- 스펙 편집 (이름 · 가공 종류 · 목표 · 순서) ----------------
    private LinearLayout edList;

    private void openEditor() {
        Context c = a;
        LinearLayout col = U.col(c);
        col.addView(a.backRow("가이드"));
        col.addView(a.header("스펙 편집", "누르면 수정"));
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        sv.addView(body);
        DragList list = new DragList(c);
        list.scroll = sv;
        list.onDrop = this::saveOrder;
        list.setBackground(U.box(c, U.PLATE, 7, U.RULE, 1));
        list.setPadding(U.dp(c, 4), U.dp(c, 2), U.dp(c, 8), U.dp(c, 2));
        edList = list;
        body.addView(list, U.margins(U.match(), c, 14, 0, 14, 0));
        TextView add = U.tv(c, "+ 새 스펙 추가", 15, U.SUB, U.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setBackground(U.dashed(c, Color.TRANSPARENT, 7, Color.rgb(0x8c, 0x96, 0x8b)));
        add.setOnClickListener(v -> openSpecSheet(null, null));
        body.addView(add, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 48)), c, 14, 10, 14, 0));
        TextView tip = U.tv(c, "⋮⋮ 를 잡고 끌면 순서 이동 · 줄을 누르면 이름 · 가공 종류 · 목표 수정", 11.5f, U.SUB, null);
        tip.setPadding(U.dp(c, 16), U.dp(c, 10), U.dp(c, 16), U.dp(c, 24));
        body.addView(tip);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        a.pushPage(col, null);
        fillEditor();
    }

    private void fillEditor() {
        Context c = a;
        edList.removeAllViews();
        for (Store.Spec sp : Store.specs(a)) {
            LinearLayout r = U.row(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(U.dp(c, 56));
            r.setTag(sp.name);
            r.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(Color.rgb(0xdd, 0xe3, 0xdb)),
                    new android.graphics.drawable.ColorDrawable(U.PLATE)}) {{ setLayerInset(1, 0, 0, 0, 1); }});
            TextView h = U.tv(c, "⋮⋮", 18, Color.rgb(0x8c, 0x96, 0x8b), U.BOLD);
            h.setGravity(Gravity.CENTER);
            h.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) { ((DragList) edList).start(r, e.getRawY()); return true; }
                return false;
            });
            r.addView(h, new LinearLayout.LayoutParams(U.dp(c, 40), U.dp(c, 56)));
            String[] bk = split(sp.name);
            LinearLayout nm = U.col(c);
            LinearLayout top = U.row(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(U.tv(c, bk[0], 15, U.INK, U.MONO_B));
            if (!bk[1].isEmpty()) top.addView(kindTag(c, bk[1]), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 6, 0, 0, 0));
            nm.addView(top);
            int n = Store.countSpec(a, sp.name);
            nm.addView(U.tv(c, n == 0 ? "기록 없음" : "기록 " + n + "건", 11, U.SUB, null));
            r.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout tv = U.col(c);
            tv.setGravity(Gravity.END);
            tv.addView(U.tv(c, Form.num(sp.target, 1) + " ±" + Form.num(sp.tol, 1), 15, U.OK, U.MONO_B));
            tv.addView(U.tv(c, "mm/s", 10, U.SUB, U.MONO));
            r.addView(tv);
            r.addView(U.tv(c, "  ›", 18, Color.rgb(0x8b, 0x96, 0x8c), U.MONO_B));
            r.setOnClickListener(v -> openSpecSheet(Store.spec(a, sp.name), null));
            edList.addView(r, U.match());
        }
    }

    private void saveOrder() {
        List<Store.Spec> cur = Store.specs(a), out = new ArrayList<>();
        for (int i = 0; i < edList.getChildCount(); i++) {
            Object name = edList.getChildAt(i).getTag();
            for (Store.Spec s : cur) if (s.name.equals(name)) out.add(s);
        }
        for (Store.Spec s : cur) if (!out.contains(s)) out.add(s);
        Store.saveSpecs(a, out);
    }

    /** "67-30 R" → {"67-30", "R"} */
    static String[] split(String name) {
        String n = name.trim();
        if (n.endsWith(" R") || n.endsWith(" N")) return new String[]{n.substring(0, n.length() - 2).trim(), n.substring(n.length() - 1)};
        return new String[]{n, ""};
    }

    private TextView kindTag(Context c, String k) {
        boolean r = k.equals("R");
        TextView t = U.tv(c, k + " 가공", 10, r ? Color.rgb(0x2f, 0x5f, 0x8a) : Color.rgb(0x7a, 0x3f, 0x8a), U.BOLD);
        int bg = r ? Color.rgb(0xe0, 0xea, 0xf4) : Color.rgb(0xee, 0xe1, 0xf2);
        t.setBackground(U.box(c, bg, 3, bg, 0));
        t.setPadding(U.dp(c, 5), U.dp(c, 1), U.dp(c, 5), U.dp(c, 1));
        return t;
    }

    /** orig == null 이면 새 스펙 (pre 로 미리 채움) */
    private void openSpecSheet(Store.Spec orig, Store.Spec pre) {
        Context c = a;
        Store.Spec src = orig != null ? orig : pre;
        int count = orig != null ? Store.countSpec(a, orig.name) : 0;
        LinearLayout s = a.sheetBox(orig != null ? "스펙 수정" : (pre != null ? "새 스펙 (복사)" : "새 스펙"),
                orig != null ? orig.name + " · 기록 " + count + "건" : null);
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        sv.addView(body);
        s.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] bk = src != null ? split(src.name) : new String[]{"", ""};
        int kind = bk[1].equals("R") ? 1 : bk[1].equals("N") ? 2 : 0;
        if (orig == null && pre != null) kind = kind == 1 ? 2 : 1;   // 복사: R ↔ N
        Form f = new Form(c);
        EditText base = f.input(orig != null ? bk[0] : (pre != null ? bk[0] : ""), 0, "예: 67-30");
        base.setTypeface(U.MONO_B);
        f.row("핀 규격", base);
        Form.Seg seg = f.seg(new String[]{"없음", "R 가공", "N 가공"}, kind, false);
        f.row("가공 종류", Form.viewOf(seg));
        EditText tgt = f.input(src != null ? Form.num(src.target, 1) : "", 1, "mm/s");
        EditText tol = f.input(src != null ? Form.num(src.tol, 1) : "3", 1, "±");
        f.row("목표 RMS · ±", f.pair(tgt, tol));
        body.addView(f.group, U.match());

        LinearLayout pv = U.window(c);
        pv.setOrientation(LinearLayout.HORIZONTAL);
        pv.setGravity(Gravity.CENTER_VERTICAL);
        pv.addView(U.tv(c, "이름", 12, U.PHOS_DIM, null));
        TextView nmv = U.tv(c, "", 20, U.PHOS, U.MONO_B);
        nmv.setPadding(U.dp(c, 10), 0, 0, 0);
        pv.addView(nmv, U.lpW(1));
        if (orig != null) pv.addView(U.tv(c, "이전: " + orig.name, 11.5f, U.PHOS_DIM, U.MONO));
        body.addView(pv, U.margins(U.match(), c, 0, 10, 0, 0));

        final boolean[] cascade = {true};
        if (orig != null && count > 0) {
            LinearLayout ck = U.row(c);
            ck.setGravity(Gravity.CENTER_VERTICAL);
            ck.setBackground(U.box(c, U.PLATE, 7, U.RULE, 1));
            ck.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
            TextView box = U.tv(c, "✓", 14, Color.WHITE, U.BOLD);
            box.setGravity(Gravity.CENTER);
            LinearLayout tx = U.col(c);
            tx.addView(U.tv(c, "지난 기록 " + count + "건도 새 이름으로", 14, U.INK, U.BOLD));
            tx.addView(U.tv(c, "측정 · 가공 결과 · 인수인계 · 호기 설정이 함께 바뀝니다", 11.5f, U.SUB, null));
            Runnable upd = () -> {
                box.setText(cascade[0] ? "✓" : "");
                box.setBackground(U.box(c, cascade[0] ? U.SIGNAL : Color.WHITE, 4, cascade[0] ? U.SIGNAL : U.DASH, 1.5f));
            };
            upd.run();
            ck.setOnClickListener(v -> { cascade[0] = !cascade[0]; upd.run(); });
            ck.addView(box, new LinearLayout.LayoutParams(U.dp(c, 22), U.dp(c, 22)));
            tx.setPadding(U.dp(c, 10), 0, 0, 0);
            ck.addView(tx, U.lpW(1));
            body.addView(ck, U.margins(U.match(), c, 0, 10, 0, 0));
        }

        Runnable upName = () -> {
            String b = base.getText().toString().trim();
            nmv.setText(b.isEmpty() ? "—" : b + (seg.sel == 1 ? " R" : seg.sel == 2 ? " N" : ""));
        };
        upName.run();
        base.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence q, int x, int y, int z) {}
            public void onTextChanged(CharSequence q, int x, int y, int z) {}
            public void afterTextChanged(android.text.Editable e) { upName.run(); }
        });
        seg.onChange = upName;

        LinearLayout acts = U.row(c);
        if (orig != null) {
            TextView del = U.button(c, "삭제", 1, v -> new android.app.AlertDialog.Builder(a)
                    .setTitle(orig.name + " 스펙을 삭제할까요?")
                    .setMessage("지난 기록은 지워지지 않고 남습니다.")
                    .setPositiveButton("삭제", (d, w) -> {
                        List<Store.Spec> l = Store.specs(a);
                        l.removeIf(x -> x.name.equals(orig.name));
                        Store.saveSpecs(a, l);
                        a.closeSheet();
                        fillEditor();
                    }).setNegativeButton("취소", null).show());
            del.setTextColor(U.BAD);
            del.setBackground(U.box(c, U.PLATE, 8, U.BAD, 2));
            acts.addView(del, new LinearLayout.LayoutParams(0, U.dp(c, 52), 1));
            TextView copy = U.button(c, "복사", 1, v -> {
                Store.Spec p = new Store.Spec(orig.name, orig.target);
                p.tol = orig.tol;
                a.closeSheet();
                openSpecSheet(null, p);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, U.dp(c, 52), 1);
            cp.leftMargin = U.dp(c, 8);
            acts.addView(copy, cp);
        }
        TextView save = U.button(c, "저장", 0, v -> {
            String b = base.getText().toString().trim();
            if (b.isEmpty()) { Toast.makeText(a, "핀 규격을 넣으세요", Toast.LENGTH_SHORT).show(); return; }
            String name = b + (seg.sel == 1 ? " R" : seg.sel == 2 ? " N" : "");
            double t = Form.parse(tgt), tl = Form.parse(tol);
            if (Double.isNaN(t) || t <= 0) { Toast.makeText(a, "목표 RMS를 넣으세요", Toast.LENGTH_SHORT).show(); return; }
            if (Double.isNaN(tl) || tl < 0) tl = 3;
            List<Store.Spec> l = Store.specs(a);
            for (Store.Spec x : l)
                if (x.name.equals(name) && (orig == null || !x.name.equals(orig.name))) {
                    Toast.makeText(a, name + " 은(는) 이미 있습니다", Toast.LENGTH_SHORT).show();
                    return;
                }
            Store.Spec ns = new Store.Spec(name, t);
            ns.tol = tl;
            if (orig != null) {
                for (int i = 0; i < l.size(); i++) if (l.get(i).name.equals(orig.name)) l.set(i, ns);
            } else {
                int at = l.size();
                if (pre != null) for (int i = 0; i < l.size(); i++) if (l.get(i).name.equals(pre.name)) at = i + 1;
                l.add(at, ns);
            }
            Store.saveSpecs(a, l);
            if (orig != null && !orig.name.equals(name) && cascade[0]) Store.renameSpec(a, orig.name, name);
            a.closeSheet();
            Toast.makeText(a, "저장했습니다 · " + name, Toast.LENGTH_SHORT).show();
            fillEditor();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, U.dp(c, 52), orig != null ? 1.6f : 1);
        if (orig != null) sp.leftMargin = U.dp(c, 8);
        acts.addView(save, sp);
        body.addView(acts, U.margins(U.match(), c, 0, 14, 0, 0));
        a.showSheet(s, null);
    }

    /** ⋮⋮ 손잡이로 끌어서 순서 바꾸는 목록 */
    static class DragList extends LinearLayout {
        ScrollView scroll;
        Runnable onDrop;
        private View drag;
        private float lastY, offset;

        DragList(Context c) { super(c); setOrientation(VERTICAL); }

        void start(View row, float rawY) {
            drag = row;
            lastY = rawY;
            offset = 0;
            row.setElevation(U.dp(getContext(), 6));
            row.setAlpha(0.92f);
            if (scroll != null) scroll.requestDisallowInterceptTouchEvent(true);
        }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            return drag != null || super.onInterceptTouchEvent(e);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (drag == null) return super.onTouchEvent(e);
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_MOVE) {
                offset += e.getRawY() - lastY;
                lastY = e.getRawY();
                int i = indexOfChild(drag);
                int h = drag.getHeight();
                if (offset > h / 2f && i < getChildCount() - 1) {
                    View next = getChildAt(i + 1);
                    removeViewAt(i + 1);
                    addView(next, i);
                    offset -= next.getHeight();
                } else if (offset < -h / 2f && i > 0) {
                    View prev = getChildAt(i - 1);
                    removeViewAt(i - 1);
                    addView(prev, i);
                    offset += prev.getHeight();
                }
                drag.setTranslationY(offset);
                return true;
            }
            if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                drag.setTranslationY(0);
                drag.setElevation(0);
                drag.setAlpha(1f);
                drag = null;
                if (onDrop != null) onDrop.run();
                return true;
            }
            return true;
        }
    }
}
