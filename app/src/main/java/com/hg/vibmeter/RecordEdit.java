package com.hg.vibmeter;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 기록 수정 창 (측정 기록 · 가공 결과) */
final class RecordEdit {
    private RecordEdit() {}

    private static LinearLayout frame(MainActivity a, String title, String sub, LinearLayout[] bodyOut) {
        LinearLayout s = a.sheetBox(title, sub);
        ScrollView sv = new ScrollView(a);
        LinearLayout body = U.col(a);
        sv.addView(body);
        s.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bodyOut[0] = body;
        return s;
    }

    private static LinearLayout actions(MainActivity a, Runnable del, Runnable save) {
        LinearLayout r = U.row(a);
        TextView d = U.button(a, "지우기", 1, v -> del.run());
        d.setTextColor(U.BAD);
        d.setBackground(U.box(a, U.PLATE, 8, U.BAD, 2));
        r.addView(d, new LinearLayout.LayoutParams(0, U.dp(a, 52), 1));
        TextView s = U.button(a, "저장", 0, v -> save.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.dp(a, 52), 2);
        p.leftMargin = U.dp(a, 8);
        r.addView(s, p);
        return r;
    }

    private static void confirm(MainActivity a, Runnable yes) {
        new android.app.AlertDialog.Builder(a).setTitle("이 기록을 지울까요?")
                .setPositiveButton("지우기", (d, w) -> yes.run())
                .setNegativeButton("취소", null).show();
    }

    // ---------------- 측정 기록 ----------------
    static void openRecord(MainActivity a, Store.Record r, Runnable done) {
        LinearLayout[] b = new LinearLayout[1];
        LinearLayout s = frame(a, "측정 기록 수정", U.time(r.time, "M/d HH:mm") + " 측정", b);
        LinearLayout body = b[0];

        Form f = new Form(a);
        Form.Stepper unit = f.stepper(r.unit, 1, Store.UNITS, 1, "호기");
        f.row("호기", Form.viewOf(unit));
        Form.Picker spec = f.picker(r.spec, "스펙 고르기", Form.specNames(a));
        f.row("스펙", spec.view);
        EditText hz = f.input(Form.num(r.setHz, 2), 1, "Hz");
        f.row("설정 주파수", hz);
        Form.When when = f.when(r.time);
        f.row("날짜 · 시간", when.view);
        EditText memo = f.input(r.memo, 0, "메모");
        f.row("메모", memo);
        body.addView(f.group, U.match());

        TextView t2 = U.sectionTitle(a, "측정값 · 직접 고칠 수 있음");
        t2.setPadding(U.dp(a, 4), U.dp(a, 14), 0, U.dp(a, 6));
        body.addView(t2);
        Form g = new Form(a);
        EditText v = g.input(Form.num(r.vRms, 2), 1, "mm/s");
        EditText fr = g.input(Form.num(r.freq, 2), 1, "Hz");
        g.row("진동 속도", v);
        g.row("측정 주파수", fr);
        body.addView(g.group, U.match());
        TextView note = U.tv(a, "진동 속도 단위 RMS mm/s · 진동 속도나 주파수를 고치면 가속도(Z RMS)도 다시 계산", 11.5f, U.SUB, null);
        note.setPadding(U.dp(a, 4), U.dp(a, 6), 0, 0);
        body.addView(note);

        final long key = r.time;
        final int keyUnit = r.unit;
        body.addView(actions(a, () -> confirm(a, () -> {
            List<Store.Record> l = Store.records(a);
            l.removeIf(x -> x.time == key && x.unit == keyUnit);
            Store.saveRecords(a, l);
            a.closeSheet();
            done.run();
        }), () -> {
            double nv = Form.parse(v), nf = Form.parse(fr);
            if (Double.isNaN(nv) || Double.isNaN(nf) || nf <= 0) {
                Toast.makeText(a, "진동 속도와 주파수를 확인하세요", Toast.LENGTH_SHORT).show();
                return;
            }
            List<Store.Record> l = Store.records(a);
            for (Store.Record x : l) if (x.time == key && x.unit == keyUnit) {
                boolean measChanged = Math.abs(x.vRms - nv) > 1e-9 || Math.abs(x.freq - nf) > 1e-9;
                x.unit = unit.value;
                x.spec = spec.value == null ? "" : spec.value;
                x.setHz = Form.parse(hz);
                x.time = when.time;
                x.memo = memo.getText().toString().trim();
                x.vRms = nv;
                x.freq = nf;
                if (measChanged) {
                    x.zRms = nv * nf / Analyzer.K_RMS_MM;
                    x.vPeak = Analyzer.K_PEAK_CM * x.zRms / nf;
                }
                x.edited = true;
                break;
            }
            Store.saveRecords(a, l);
            a.closeSheet();
            Toast.makeText(a, "수정했습니다", Toast.LENGTH_SHORT).show();
            done.run();
        }), U.margins(U.match(), a, 0, 14, 0, 0));
        a.showSheet(s, null);
    }

    // ---------------- 가공 결과 ----------------
    static void openResult(MainActivity a, Store.Result r, Runnable done) {
        LinearLayout[] b = new LinearLayout[1];
        LinearLayout s = frame(a, "가공 결과 수정", r.unit + "호기 · " + U.time(r.time, "M/d HH:mm"), b);
        LinearLayout body = b[0];

        Form f = new Form(a);
        Form.Stepper unit = f.stepper(r.unit, 1, Store.UNITS, 1, "호기");
        f.row("호기", Form.viewOf(unit));
        Form.Picker spec = f.picker(r.spec, "스펙 고르기", Form.specNames(a));
        f.row("스펙", spec.view);
        EditText hz = f.input(Form.num(r.setHz, 2), 1, "Hz");
        EditText v = f.input(Form.num(r.vRms, 2), 1, "mm/s");
        f.row("주파수 · 속도", f.pair(hz, v));
        Form.Stepper rounds = f.stepper(r.rounds, 0, 30, 1, "회");
        f.row("가공 횟수", Form.viewOf(rounds));
        Form.Stepper mins = f.stepper(r.minutes, 0, 600, 5, "분");
        f.row("총 시간", Form.viewOf(mins));
        String[] shapes = {"부족", "양호", "과다"};
        int si = -1;
        for (int i = 0; i < 3; i++) if (shapes[i].equals(r.rShape)) si = i;
        Form.Seg shape = f.seg(shapes, si, true);
        f.row("R 모양", Form.viewOf(shape));
        EditText ang = f.input(Form.num(r.angle, 1), 1, "°");
        EditText len = f.input(Form.num(r.length, 3), 1, "전장 mm");
        f.row("각도 · 전장", f.pair(ang, len));
        Form.When when = f.when(r.time);
        f.row("날짜 · 시간", when.view);
        EditText memo = f.input(r.memo, 0, "메모");
        f.row("메모", memo);
        body.addView(f.group, U.match());

        final long key = r.time;
        final int keyUnit = r.unit;
        body.addView(actions(a, () -> confirm(a, () -> {
            List<Store.Result> l = Store.results(a);
            l.removeIf(x -> x.time == key && x.unit == keyUnit);
            Store.saveResults(a, l);
            a.closeSheet();
            done.run();
        }), () -> {
            List<Store.Result> l = Store.results(a);
            for (Store.Result x : l) if (x.time == key && x.unit == keyUnit) {
                x.unit = unit.value;
                x.spec = spec.value == null ? "" : spec.value;
                x.setHz = Form.parse(hz);
                x.vRms = Form.parse(v);
                x.rounds = rounds.value;
                x.minutes = mins.value;
                x.rShape = shape.sel < 0 ? "" : shapes[shape.sel];
                x.angle = Form.parse(ang);
                x.length = Form.parse(len);
                x.time = when.time;
                x.memo = memo.getText().toString().trim();
                x.edited = true;
                break;
            }
            Store.saveResults(a, l);
            a.closeSheet();
            Toast.makeText(a, "수정했습니다", Toast.LENGTH_SHORT).show();
            done.run();
        }), U.margins(U.match(), a, 0, 14, 0, 0));
        a.showSheet(s, null);
    }
}
