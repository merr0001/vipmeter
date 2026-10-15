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
        TextView ex = U.button(c, "CSV 내보내기", 1, v -> export());
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, U.dp(c, 46), 1);
        el.leftMargin = U.dp(c, 8);
        acts.addView(ex, el);
        col.addView(acts);

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = U.row(c);
        chips.setPadding(U.dp(c, 14), 0, U.dp(c, 14), U.dp(c, 10));
        hs.addView(chips);
        col.addView(hs);

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
        row.setPadding(0, U.dp(a, 10), U.dp(a, 12), U.dp(a, 10));
        LinearLayout no = U.col(a);
        no.setGravity(Gravity.CENTER);
        no.addView(U.tv(a, String.valueOf(unit), 22, U.INK, U.BOLD));
        no.addView(U.tv(a, "호기", 10, U.SUB, null));
        row.addView(no, new LinearLayout.LayoutParams(U.dp(a, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
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
        row.setOnLongClickListener(v -> { confirmDelete(null, r); return true; });
        return row;
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
        String name = "진동속도_" + U.time(System.currentTimeMillis(), "yyyyMMdd_HHmm") + ".csv";
        try {
            Uri uri = saveDownload(name, "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(share, "다운로드/VibMeter 에 저장됨 · 보내기"));
        } catch (Exception e) {
            Toast.makeText(a, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Uri saveDownload(String name, String mime, byte[] data) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VibMeter");
        Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("파일을 만들 수 없습니다");
        try (OutputStream o = a.getContentResolver().openOutputStream(uri)) { o.write(data); }
        return uri;
    }

    // ---------------- 인수인계 ----------------
    private void openHandover() {
        Context c = a;
        LinearLayout col = U.col(c);
        col.addView(a.backRow("기록"));
        LinearLayout hd = a.header("인수인계", U.time(System.currentTimeMillis(), "M/d HH:mm"));
        col.addView(hd);
        ScrollView sv = new ScrollView(c);
        LinearLayout body = U.col(c);
        body.setPadding(U.dp(c, 12), 0, U.dp(c, 12), U.dp(c, 20));
        sv.addView(body);

        LinearLayout paper = U.col(c);
        paper.setBackground(U.box(c, Color.rgb(0xfb, 0xfc, 0xfa), 6, U.RULE, 1));
        paper.setPadding(U.dp(c, 10), U.dp(c, 10), U.dp(c, 10), U.dp(c, 8));
        LinearLayout ph = U.row(c);
        ph.addView(U.tv(c, "H.G 진동연마 현황", 16, U.INK, U.BOLD), U.lpW(1));
        String who = Store.getStr(a, "author", "박동원");
        ph.addView(U.tv(c, U.time(System.currentTimeMillis(), "M/d HH:mm") + " · " + who, 11, U.SUB, null));
        paper.addView(ph);
        View rule = new View(c);
        rule.setBackgroundColor(U.INK);
        paper.addView(rule, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 2)), c, 0, 6, 0, 4));
        paper.addView(hRow(c, new String[]{"호기", "스펙", "설정Hz", "mm/s", "상태"}, true, -1, false));

        List<Store.Record> recs = Store.records(a);
        long now = System.currentTimeMillis();
        for (Store.Machine m : Store.machines(a)) {
            Store.Record last = Store.lastRecord(recs, m.unit, m.spec.isEmpty() ? null : m.spec);
            String state;
            if (m.endAt > now) state = (m.round ? TimerScreen.roundLabel(m) : m.durMin + "분") + " " + U.mmss(m.endAt - now);
            else if (m.finished) state = "완료";
            else if (m.planActive && m.swapDue()) state = "좌우 대기";
            else if (m.planActive && !m.planDone()) state = m.doneRounds + "/" + m.planTotal() + "회";
            else state = "";
            boolean off = m.spec.isEmpty() && state.isEmpty();
            int j = last == null ? -1 : U.judge(Store.spec(a, m.spec), last.vRms);
            paper.addView(hRow(c, new String[]{String.valueOf(m.unit), m.spec.isEmpty() ? "—" : m.spec,
                    Double.isNaN(m.setHz) ? "" : U.f1(m.setHz), last == null ? "" : U.f1(last.vRms), state}, false, j, off));
        }
        EditText memo = new EditText(c);
        memo.setHint("인수인계 메모 (예: 14호기 전장 공차 끝, 재측정 필요)");
        memo.setTextSize(12.5f);
        memo.setText(Store.getStr(a, "handoverMemo", ""));
        memo.setBackground(U.box(c, U.ENAMEL, 4, U.ENAMEL, 0));
        memo.setPadding(U.dp(c, 8), U.dp(c, 6), U.dp(c, 8), U.dp(c, 6));
        memo.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) {}
            public void onTextChanged(CharSequence s, int x, int y, int z) {}
            public void afterTextChanged(Editable e) { Store.putStr(a, "handoverMemo", e.toString()); }
        });
        paper.addView(memo, U.margins(U.match(), c, 0, 8, 0, 0));
        body.addView(paper, U.match());

        LinearLayout share = U.row(c);
        TextView k = U.button(c, "카톡으로 보내기 (이미지)", 0, v -> { memo.clearFocus(); memo.setCursorVisible(false); shareImage(paper); memo.setCursorVisible(true); });
        k.setBackground(U.box(c, Color.rgb(0xf7, 0xe0, 0x4b), 8, 0, 0));
        k.setTextColor(Color.rgb(0x3a, 0x2e, 0x0b));
        share.addView(k, new LinearLayout.LayoutParams(0, U.dp(c, 52), 1.4f));
        TextView tx = U.button(c, "글로 보내기", 1, v -> shareText(memo.getText().toString()));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, U.dp(c, 52), 1);
        tl.leftMargin = U.dp(c, 8);
        share.addView(tx, tl);
        body.addView(share, U.margins(U.match(), c, 0, 10, 0, 0));

        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        a.pushPage(col, null);
    }

    private LinearLayout hRow(Context c, String[] v, boolean head, int judge, boolean off) {
        LinearLayout r = U.row(c);
        r.setPadding(0, U.dp(c, head ? 2 : 4), 0, U.dp(c, head ? 4 : 4));
        float[] w = {0.6f, 1.5f, 1.0f, 1.3f, 1.7f};
        for (int i = 0; i < v.length; i++) {
            int color = head ? U.SUB : (off ? Color.rgb(0xa3, 0xad, 0xa2) : U.INK);
            TextView t = U.tv(c, v[i], head ? 10 : (i == 0 ? 13 : 11.5f), color, head ? null : (i == 0 ? U.BOLD : U.MONO));
            if (i == 2 || i == 3) t.setGravity(Gravity.END);
            if (i == 3 && !head && judge >= 0) {
                t.setText(v[i] + (judge == 0 ? " ✓" : judge == 1 ? " ↓" : " ↑"));
                t.setTextColor(judge == 0 ? U.OK : judge == 1 ? Color.rgb(0x8a, 0x61, 0x10) : U.BAD);
            }
            if (i == 4) t.setPadding(U.dp(c, 8), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w[i]));
        }
        if (!head) r.setBackground(U.box(c, Color.TRANSPARENT, 0, Color.rgb(0xe3, 0xe8, 0xe1), 0));
        return r;
    }

    private void shareImage(View paper) {
        try {
            Bitmap b = Bitmap.createBitmap(paper.getWidth(), paper.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.drawColor(U.ENAMEL);
            paper.draw(cv);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "인수인계_" + U.time(System.currentTimeMillis(), "yyyyMMdd_HHmm") + ".png");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VibMeter");
            Uri uri = a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
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

    private void shareText(String memo) {
        StringBuilder sb = new StringBuilder("[H.G 진동연마 현황] " + U.time(System.currentTimeMillis(), "M/d HH:mm") + "\n");
        List<Store.Record> recs = Store.records(a);
        long now = System.currentTimeMillis();
        for (Store.Machine m : Store.machines(a)) {
            if (m.spec.isEmpty() && m.endAt <= now && !m.planActive) continue;
            Store.Record last = Store.lastRecord(recs, m.unit, m.spec.isEmpty() ? null : m.spec);
            sb.append(m.unit).append("호기 ").append(m.spec);
            if (!Double.isNaN(m.setHz)) sb.append(" · ").append(U.f1(m.setHz)).append("Hz");
            if (last != null) sb.append(" · ").append(U.f1(last.vRms)).append("mm/s");
            if (m.endAt > now) sb.append(" · 남은 ").append(U.mmss(m.endAt - now));
            else if (m.planActive && m.swapDue()) sb.append(" · 좌우 대기");
            sb.append('\n');
        }
        if (!memo.trim().isEmpty()) sb.append("\n").append(memo.trim());
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString());
        a.startActivity(Intent.createChooser(share, "인수인계 보내기"));
    }
}
