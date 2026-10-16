package com.hg.vibmeter;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** 기록 탭: 측정 기록 + 가공 결과, 호기 필터, CSV, 인수인계 */
class RecordScreen extends MainActivity.Screen {

    private LinearLayout list, chips;
    private TextView count;
    private int filter = 0; // 0 = 전체

    RecordScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        Context c = a;
        LinearLayout col = U.col(c);
        LinearLayout hd = a.header("기록", "");
        count = (TextView) hd.getChildAt(1);
        col.addView(hd);

        LinearLayout acts = U.row(c);
        acts.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 10));
        TextView ho = U.button(c, "인수인계", 2, v -> openHandover());
        acts.addView(ho, new LinearLayout.LayoutParams(0, U.dp(c, 46), 1));
        TextView ex = U.button(c, "내보내기", 1, v -> export());
        ex.setTextSize(15);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, U.dp(c, 46), 1);
        el.leftMargin = U.dp(c, 6);
        acts.addView(ex, el);
        TextView im = U.button(c, "불러오기", 1, v -> pickImport());
        im.setTextSize(15);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, U.dp(c, 46), 1);
        il.leftMargin = U.dp(c, 6);
        acts.addView(im, il);
        col.addView(acts);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = U.row(c);
        chips.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 10));
        hs.addView(chips);
        col.addView(hs);
        TextView hint = U.tv(c, "누르기: 수정 · 길게 누르기: 지우기", 11.5f, U.SUB, null);
        hint.setPadding(U.dp(c, 16), 0, U.dp(c, 16), U.dp(c, 8));
        col.addView(hint);

        ScrollView sv = new ScrollView(c);
        list = U.col(c);
        list.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 20));
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    @Override void onShow() { refresh(); }

    private static class Item {
        long time; Store.Record r; Store.Result res;
    }

    void refresh() {
        List<Store.Record> rs = Store.records(a);
        List<Store.Result> results = Store.results(a);
        TreeSet<Integer> units = new TreeSet<>();
        for (Store.Record r : rs) units.add(r.unit);
        for (Store.Result r : results) units.add(r.unit);
        chips.removeAllViews();
        addChip("전체", 0);
        for (int u : units) addChip(u + "호기", u);

        List<Item> items = new ArrayList<>();
        for (Store.Record r : rs) if (filter == 0 || r.unit == filter) { Item i = new Item(); i.time = r.time; i.r = r; items.add(i); }
        for (Store.Result r : results) if (filter == 0 || r.unit == filter) { Item i = new Item(); i.time = r.time; i.res = r; items.add(i); }
        items.sort((x, y) -> Long.compare(y.time, x.time));
        count.setText(items.size() + "건");
        list.removeAllViews();
        if (items.isEmpty()) {
            list.addView(U.tv(a, "아직 기록이 없습니다", 14, U.SUB, null));
            return;
        }
        for (Item it : items) list.addView(it.r != null ? recordCard(it.r) : resultCard(it.res), U.margins(U.match(), a, 0, 0, 0, 8));
    }

    private void addChip(String t, int u) {
        TextView c = U.tv(a, t, 13, u == filter ? U.PLATE : U.INK, null);
        c.setBackground(u == filter ? U.box(a, U.INK, 16, U.INK, 1) : U.box(a, U.PLATE, 16, U.RULE, 1));
        c.setPadding(U.dp(a, 12), U.dp(a, 6), U.dp(a, 12), U.dp(a, 6));
        c.setOnClickListener(v -> { filter = u; refresh(); });
        chips.addView(c, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 0, 0, 6, 0));
    }

    private LinearLayout card(int unit) {
        LinearLayout row = U.row(a);
        row.setBackground(U.box(a, U.PLATE, 6, U.RULE, 1));
        row.setPadding(U.dp(a, 6), U.dp(a, 10), U.dp(a, 12), U.dp(a, 10));
        LinearLayout no = U.col(a);
        no.setGravity(Gravity.CENTER);
        no.addView(U.tv(a, String.valueOf(unit), 22, U.INK, U.BOLD));
        no.addView(U.tv(a, "호기", 10, U.SUB, null));
        row.addView(no, new LinearLayout.LayoutParams(U.dp(a, 52), ViewGroup.LayoutParams.WRAP_CONTENT));
        View sep = new View(a);
        sep.setBackground(U.dashed(a, android.graphics.Color.TRANSPARENT, 0, U.RULE));
        row.addView(sep, U.margins(new LinearLayout.LayoutParams(U.dp(a, 1), ViewGroup.LayoutParams.MATCH_PARENT), a, 4, 0, 12, 0));
        return row;
    }

    private View recordCard(Store.Record r) {
        LinearLayout row = card(r.unit);
        LinearLayout mid = U.col(a);
        LinearLayout top = U.row(a);
        top.addView(U.tv(a, r.spec.isEmpty() ? "스펙 없음" : r.spec, 14, U.INK, U.MONO_B));
        int j = U.judge(Store.spec(a, r.spec), r.vRms);
        if (j >= 0) {
            TextView t = U.tag(a, U.judgeText(j), j);
            top.addView(t, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 6, 0, 0, 0));
        }
        if (r.edited) top.addView(editedTag(), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 5, 0, 0, 0));
        mid.addView(top);
        String sub = U.time(r.time, "M/d HH:mm") + " · " + U.f1(r.hz()) + " Hz" + (r.memo.isEmpty() ? "" : " · " + r.memo);
        TextView st = U.tv(a, sub, 11.5f, U.SUB, null);
        st.setPadding(0, U.dp(a, 3), 0, 0);
        mid.addView(st);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout val = U.col(a);
        val.setGravity(Gravity.END);
        val.addView(U.tv(a, U.f1(r.vRms), 22, U.INK, U.MONO_B));
        val.addView(U.tv(a, "mm/s", 10, U.SUB, U.MONO));
        row.addView(val);
        row.setOnClickListener(v -> RecordEdit.openRecord(a, r, this::refresh));
        row.setOnLongClickListener(v -> { confirmDelete(r, null); return true; });
        return row;
    }

    private View resultCard(Store.Result r) {
        LinearLayout row = card(r.unit);
        row.setBackground(U.box(a, U.PLATE, 6, U.INK, 1.5f));
        LinearLayout mid = U.col(a);
        LinearLayout top = U.row(a);
        top.addView(U.tv(a, r.spec, 14, U.INK, U.MONO_B));
        top.addView(U.tag(a, "가공 결과", 3), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 6, 0, 0, 0));
        if (!r.rShape.isEmpty()) top.addView(U.tag(a, "R " + r.rShape, r.rShape.equals("양호") ? 0 : 1), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 4, 0, 0, 0));
        if (r.edited) top.addView(editedTag(), U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), a, 4, 0, 0, 0));
        mid.addView(top);
        StringBuilder sb = new StringBuilder(U.time(r.time, "M/d HH:mm"));
        if (!Double.isNaN(r.setHz)) sb.append(" · ").append(U.f1(r.setHz)).append(" Hz");
        if (r.rounds > 0) sb.append(" · ").append(r.rounds).append("회 ").append(r.minutes).append("분");
        if (!Double.isNaN(r.angle)) sb.append(" · ").append(U.f1(r.angle).replace(".0", "")).append("°");
        if (!Double.isNaN(r.length)) sb.append(" · 전장 ").append(U.f3(r.length));
        if (!r.memo.isEmpty()) sb.append(" · ").append(r.memo);
        TextView st = U.tv(a, sb.toString(), 11.5f, U.SUB, null);
        st.setPadding(0, U.dp(a, 3), 0, 0);
        mid.addView(st);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout val = U.col(a);
        val.setGravity(Gravity.END);
        val.addView(U.tv(a, U.f1(r.vRms), 18, U.INK, U.MONO_B));
        val.addView(U.tv(a, "mm/s", 10, U.SUB, U.MONO));
        row.addView(val);
        row.setOnClickListener(v -> RecordEdit.openResult(a, r, this::refresh));
        row.setOnLongClickListener(v -> { confirmDelete(null, r); return true; });
        return row;
    }

    private TextView editedTag() {
        TextView t = U.tv(a, "수정됨", 9.5f, U.SIGNAL, U.BOLD);
        t.setBackground(U.box(a, android.graphics.Color.TRANSPARENT, 3, U.SIGNAL, 1));
        t.setPadding(U.dp(a, 4), U.dp(a, 1), U.dp(a, 4), U.dp(a, 1));
        return t;
    }

    private void confirmDelete(Store.Record rec, Store.Result res) {
        new android.app.AlertDialog.Builder(a)
                .setTitle("이 기록을 지울까요?")
                .setPositiveButton("지우기", (d, w) -> {
                    if (rec != null) {
                        List<Store.Record> l = Store.records(a);
                        l.removeIf(x -> x.time == rec.time && x.unit == rec.unit);
                        Store.saveRecords(a, l);
                    } else {
                        List<Store.Result> l = Store.results(a);
                        l.removeIf(x -> x.time == res.time && x.unit == res.unit);
                        Store.saveResults(a, l);
                    }
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------------- CSV ----------------
    private static String q(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private void export() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("[측정 기록]\n일시,호기,스펙,설정주파수(Hz),실측주파수(Hz),진동속도RMS(mm/s),목표,판정,피크(cm/s),Z RMS(m/s2),X/Z(%),Y/Z(%),메모\n");
        for (Store.Record r : Store.records(a)) {
            Store.Spec s = Store.spec(a, r.spec);
            sb.append(String.format(Locale.US, "%s,%d,%s,%s,%.2f,%.1f,%s,%s,%.2f,%.2f,%.0f,%.0f,%s\n",
                    U.time(r.time, "yyyy-MM-dd HH:mm:ss"), r.unit, q(r.spec), U.f1(r.setHz), r.freq, r.vRms,
                    s == null ? "" : U.f1(s.target), U.judgeText(U.judge(s, r.vRms)), r.vPeak, r.zRms, r.pctX, r.pctY, q(r.memo)));
        }
        sb.append("\n[가공 결과]\n일시,호기,스펙,설정주파수(Hz),진동속도RMS(mm/s),회차,가공시간(분),각도,R형상,전장,메모\n");
        for (Store.Result r : Store.results(a)) {
            sb.append(String.format(Locale.US, "%s,%d,%s,%s,%s,%d,%d,%s,%s,%s,%s\n",
                    U.time(r.time, "yyyy-MM-dd HH:mm:ss"), r.unit, q(r.spec), U.f1(r.setHz), U.f1(r.vRms), r.rounds, r.minutes,
                    U.f1(r.angle), r.rShape, U.f3(r.length), q(r.memo)));
        }
        sb.append("\n[스펙]\n스펙,목표RMS(mm/s),범위(±)\n");
        for (Store.Spec s : Store.specs(a))
            sb.append(q(s.name)).append(',').append(Double.isNaN(s.target) ? "" : U.f1(s.target)).append(',').append(U.f1(s.tol)).append('\n');
        String name = "진동지기_" + U.time(System.currentTimeMillis(), "yyyyMMdd_HHmm") + ".csv";
        try {
            Uri uri = saveDownload(name, "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(share, "다운로드/진동지기 에 저장됨 · 보내기"));
        } catch (Exception e) {
            Toast.makeText(a, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Uri saveDownload(String name, String mime, byte[] data) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/진동지기");
        Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("파일을 만들 수 없습니다");
        try (OutputStream o = a.getContentResolver().openOutputStream(uri)) { o.write(data); }
        return uri;
    }

    // ---------------- 불러오기 (내보낸 CSV → 기록 · 가공 결과 · 스펙에 적용) ----------------
    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"});
        try { a.startActivityForResult(i, MainActivity.REQ_IMPORT); }
        catch (Exception e) { Toast.makeText(a, "파일 고르기를 열 수 없습니다", Toast.LENGTH_SHORT).show(); }
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(ch);
            } else if (ch == '"') inQ = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static double num(List<String> f, int i) {
        if (i >= f.size()) return Double.NaN;
        String s = f.get(i).trim();
        if (s.isEmpty() || s.equals("—") || s.equals("-")) return Double.NaN;
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }

    private static String str(List<String> f, int i) { return i < f.size() ? f.get(i).trim() : ""; }

    private static long time(String s) {
        for (String fmt : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm"}) {
            try { return new java.text.SimpleDateFormat(fmt, Locale.KOREA).parse(s.trim()).getTime(); } catch (Exception ignored) {}
        }
        return -1;
    }

    void importFile(Uri uri) {
        String text;
        try (java.io.InputStream in = a.getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            text = new String(bo.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        } catch (Exception e) {
            Toast.makeText(a, "파일을 읽을 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        List<Store.Record> recs = new ArrayList<>();
        List<Store.Result> ress = new ArrayList<>();
        List<Store.Spec> specs = new ArrayList<>();
        java.util.Map<String, Double> targets = new java.util.HashMap<>();
        String sec = "";
        boolean header = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) { sec = line; header = true; continue; }
            if (header) { header = false; continue; }
            List<String> f = splitCsv(raw);
            try {
                if (sec.contains("측정")) {
                    long t = time(str(f, 0));
                    int u = (int) num(f, 1);
                    if (t < 0 || u < 1 || u > Store.UNITS) continue;
                    Store.Record r = new Store.Record();
                    r.time = t; r.unit = u; r.spec = str(f, 2);
                    r.setHz = num(f, 3); r.freq = num(f, 4); r.vRms = num(f, 5);
                    double tg = num(f, 6);
                    if (!r.spec.isEmpty() && !Double.isNaN(tg)) targets.put(r.spec, tg);
                    r.vPeak = num(f, 8); r.zRms = num(f, 9); r.pctX = num(f, 10); r.pctY = num(f, 11);
                    r.memo = str(f, 12);
                    if (Double.isNaN(r.freq)) r.freq = Double.isNaN(r.setHz) ? 0 : r.setHz;
                    if (Double.isNaN(r.vRms)) continue;
                    if (Double.isNaN(r.zRms) && r.freq > 0) r.zRms = r.vRms * r.freq / Analyzer.K_RMS_MM;
                    if (Double.isNaN(r.vPeak) && r.freq > 0) r.vPeak = Analyzer.K_PEAK_CM * r.zRms / r.freq;
                    if (Double.isNaN(r.pctX)) r.pctX = 0;
                    if (Double.isNaN(r.pctY)) r.pctY = 0;
                    recs.add(r);
                } else if (sec.contains("가공")) {
                    long t = time(str(f, 0));
                    int u = (int) num(f, 1);
                    if (t < 0 || u < 1 || u > Store.UNITS) continue;
                    Store.Result r = new Store.Result();
                    r.time = t; r.unit = u; r.spec = str(f, 2);
                    r.setHz = num(f, 3); r.vRms = num(f, 4);
                    double ro = num(f, 5), mi = num(f, 6);
                    r.rounds = Double.isNaN(ro) ? 0 : (int) ro;
                    r.minutes = Double.isNaN(mi) ? 0 : (int) mi;
                    r.angle = num(f, 7); r.rShape = str(f, 8).replace("—", ""); r.length = num(f, 9);
                    r.memo = str(f, 10);
                    ress.add(r);
                } else if (sec.contains("스펙")) {
                    String nm = str(f, 0);
                    if (nm.isEmpty()) continue;
                    Store.Spec s = new Store.Spec(nm, num(f, 1));
                    double tl = num(f, 2);
                    s.tol = Double.isNaN(tl) ? 3 : tl;
                    specs.add(s);
                }
            } catch (Exception ignored) {}
        }
        if (recs.isEmpty() && ress.isEmpty() && specs.isEmpty()) {
            Toast.makeText(a, "불러올 내용이 없습니다 (진동지기에서 내보낸 CSV인지 확인)", Toast.LENGTH_LONG).show();
            return;
        }
        // 미리 세어 보기
        int newR = 0, updR = 0;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Store.Record r : Store.records(a)) have.add(r.time / 1000 + "/" + r.unit);
        for (Store.Record r : recs) if (have.contains(r.time / 1000 + "/" + r.unit)) updR++; else newR++;
        String msg = "측정 " + recs.size() + "건 (새로 " + newR + " · 바뀜 " + updR + ")\n"
                + "가공 결과 " + ress.size() + "건\n"
                + "스펙 " + Math.max(specs.size(), targets.size()) + "개\n\n"
                + "같은 시각 · 같은 호기 기록은 파일 내용으로 바꿉니다. 가이드에 바로 반영됩니다.";
        new android.app.AlertDialog.Builder(a).setTitle("불러오기")
                .setMessage(msg)
                .setPositiveButton("적용", (d, w) -> applyImport(recs, ress, specs, targets))
                .setNegativeButton("취소", null).show();
    }

    private void applyImport(List<Store.Record> recs, List<Store.Result> ress, List<Store.Spec> specs, java.util.Map<String, Double> targets) {
        // 측정 기록
        List<Store.Record> cur = Store.records(a);
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < cur.size(); i++) idx.put(cur.get(i).time / 1000 + "/" + cur.get(i).unit, i);
        for (Store.Record r : recs) {
            Integer i = idx.get(r.time / 1000 + "/" + r.unit);
            if (i != null) { r.fs = cur.get(i).fs; r.edited = cur.get(i).edited; cur.set(i, r); }
            else { idx.put(r.time / 1000 + "/" + r.unit, cur.size()); cur.add(r); }
        }
        cur.sort((x, y) -> Long.compare(x.time, y.time));
        Store.saveRecords(a, cur);
        // 가공 결과
        List<Store.Result> cr = Store.results(a);
        java.util.Map<String, Integer> ri = new java.util.HashMap<>();
        for (int i = 0; i < cr.size(); i++) ri.put(cr.get(i).time / 1000 + "/" + cr.get(i).unit, i);
        for (Store.Result r : ress) {
            Integer i = ri.get(r.time / 1000 + "/" + r.unit);
            if (i != null) cr.set(i, r); else { ri.put(r.time / 1000 + "/" + r.unit, cr.size()); cr.add(r); }
        }
        cr.sort((x, y) -> Long.compare(x.time, y.time));
        Store.saveResults(a, cr);
        // 스펙: 파일의 [스펙] 우선, 없으면 측정 기록의 목표값, 그래도 없으면 목표 미정으로 추가
        List<Store.Spec> sl = Store.specs(a);
        for (Store.Spec s : specs) {
            boolean done = false;
            for (int i = 0; i < sl.size(); i++) if (sl.get(i).name.equals(s.name)) { sl.set(i, s); done = true; }
            if (!done) sl.add(s);
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Store.Spec s : sl) names.add(s.name);
        java.util.List<String> used = new ArrayList<>();
        for (Store.Record r : recs) if (!r.spec.isEmpty() && !used.contains(r.spec)) used.add(r.spec);
        for (Store.Result r : ress) if (!r.spec.isEmpty() && !used.contains(r.spec)) used.add(r.spec);
        for (String nm : used) if (!names.contains(nm)) {
            Double t = targets.get(nm);
            sl.add(new Store.Spec(nm, t == null ? Double.NaN : t));
            names.add(nm);
        }
        Store.saveSpecs(a, sl);
        // 호기 설정: 스펙이 비어 있는 호기는 가장 최근 측정의 스펙 · 주파수로
        for (int u = 1; u <= Store.UNITS; u++) {
            Store.Machine m = Store.machine(a, u);
            if (!m.spec.isEmpty()) continue;
            Store.Record last = Store.lastRecord(cur, u, null);
            if (last == null) continue;
            m.spec = last.spec;
            m.setHz = last.setHz;
            Store.save(a, m);
        }
        Toast.makeText(a, "불러오기 완료 · 측정 " + recs.size() + "건 · 결과 " + ress.size() + "건", Toast.LENGTH_LONG).show();
        refresh();
    }

    // ---------------- 인수인계 (날짜별) ----------------
    private void openHandover() { HandoverPages.openCalendar(a); }
}
