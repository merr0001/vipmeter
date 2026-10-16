package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/** 측정 탭: 목표 범위 게이지 + 계기 창 + 기록 */
class MeasureScreen extends MainActivity.Screen implements SensorEventListener {

    private static final double WINDOW_SEC = 3.0;
    private static final int CAP = 8192;
    private static final double FIX_LIMIT = 20.0;
    private static final double STABLE_LIMIT = 0.05;
    private static final double MIN_ZRMS = 3.0;
    private static final double MIN_PROM = 8.0;

    private final long[] bt = new long[CAP];
    private final float[] bx = new float[CAP], by = new float[CAP], bz = new float[CAP];
    private int head = 0, count = 0;
    private final Object lock = new Object();
    private final SensorManager sm;
    private final Sensor acc;
    private boolean running;
    private final ArrayDeque<Double> recent = new ArrayDeque<>();
    private Analyzer.Result last;
    private boolean lastValid;
    private long lastAnalyze;

    // 원본 기록 (Phyphox 형식 zip): 측정 시작 또는 직전 기록 이후 전체
    private static final int RAW_CAP = 120000;
    private final long[] rt = new long[RAW_CAP];
    private final float[] rx = new float[RAW_CAP], ry = new float[RAW_CAP], rz = new float[RAW_CAP];
    private int rHead = 0, rCount = 0;
    private long rStartWall;

    int unit;
    private TextView tvUnit, tvSpec, tvStatus, tvTarget, tvFreq, tvZ, tvPeak, tvTimer, tvFs, tvX, tvY;
    private EditText etHz, etMemo;
    private Views.GaugeView gauge;
    private Views.WaveView wave;
    private Views.FixBar barX, barY;
    private TextView btnRun;
    private boolean loading;

    MeasureScreen(MainActivity a) {
        super(a);
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        unit = Store.getInt(a, "measureUnit", 1);
    }

    @Override
    View build() {
        Context c = a;
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        col.setPadding(U.dp(c, 14), U.dp(c, 10), U.dp(c, 14), U.dp(c, 16));
        sv.addView(col);

        // 명판
        LinearLayout plate = U.row(c);
        plate.setBackground(U.box(c, U.PLATE, 6, U.RULE, 1));
        plate.setPadding(U.dp(c, 14), U.dp(c, 9), U.dp(c, 10), U.dp(c, 9));
        tvUnit = U.tv(c, "", 24, U.INK, U.BOLD);
        tvUnit.setOnClickListener(v -> pickUnit());
        plate.addView(tvUnit, U.lpW(1));
        tvSpec = U.tv(c, "", 14, U.PLATE, U.MONO_B);
        tvSpec.setBackground(U.box(c, U.INK, 4, U.INK, 0));
        tvSpec.setPadding(U.dp(c, 10), U.dp(c, 6), U.dp(c, 10), U.dp(c, 6));
        tvSpec.setOnClickListener(v -> pickSpec());
        plate.addView(tvSpec);
        col.addView(plate, U.match());

        // 설정 주파수
        LinearLayout hzRow = U.row(c);
        hzRow.setPadding(U.dp(c, 4), U.dp(c, 6), U.dp(c, 4), 0);
        hzRow.addView(U.tv(c, "설정 주파수", 13, U.SUB, null), U.lpW(1));
        etHz = new EditText(c);
        etHz.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etHz.setTypeface(U.MONO_B);
        etHz.setTextSize(18);
        etHz.setGravity(Gravity.END);
        etHz.setHint("—");
        etHz.setMinWidth(U.dp(c, 90));
        etHz.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a1, int b, int d) {}
            public void onTextChanged(CharSequence s, int a1, int b, int d) {}
            public void afterTextChanged(Editable e) {
                if (loading) return;
                Store.Machine m = Store.machine(a, unit);
                try { m.setHz = Double.parseDouble(e.toString().trim()); } catch (Exception ex) { m.setHz = Double.NaN; }
                Store.save(a, m);
            }
        });
        hzRow.addView(etHz);
        hzRow.addView(U.tv(c, " Hz", 13, U.SUB, U.MONO));
        col.addView(hzRow, U.match());

        gauge = new Views.GaugeView(c);
        col.addView(gauge, U.match());

        // 기록 버튼: 게이지 바로 아래 (엄지 위치)
        LinearLayout btns = U.row(c);
        btnRun = U.button(c, "측정 시작", 1, v -> { if (running) stopMeasuring(); else start(); });
        btns.addView(btnRun, new LinearLayout.LayoutParams(0, U.dp(c, 68), 1));
        TextView rec = U.button(c, "● 기록", 0, v -> save());
        rec.setTextSize(23);
        rec.setElevation(U.dp(c, 3));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, U.dp(c, 68), 2.4f);
        rl.leftMargin = U.dp(c, 8);
        btns.addView(rec, rl);
        col.addView(btns, U.margins(U.match(), c, 0, 2, 0, 12));

        // 판정 칩
        LinearLayout verdict = U.row(c);
        tvStatus = U.tv(c, "측정 시작을 누르세요", 14, Color.WHITE, U.BOLD);
        tvStatus.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        verdict.addView(tvStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        tvTarget = U.tv(c, "", 13.5f, U.INK, null);
        tvTarget.setBackground(U.box(c, U.PLATE, 6, U.RULE, 1));
        tvTarget.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = U.dp(c, 8);
        verdict.addView(tvTarget, tl);
        col.addView(verdict, U.match());

        // 계기 창
        LinearLayout win = U.window(c);
        GridLayout g = new GridLayout(c);
        g.setColumnCount(2);
        tvFreq = reading(g, "주파수");
        tvZ = reading(g, "Z RMS");
        tvPeak = reading(g, "피크");
        tvTimer = reading(g, "타이머");
        win.addView(g, U.match());
        wave = new Views.WaveView(c);
        win.addView(wave, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(c, 58)), c, 0, 8, 0, 6));
        barX = new Views.FixBar(c);
        barY = new Views.FixBar(c);
        tvX = fixRow(win, "X/Z", barX);
        tvY = fixRow(win, "Y/Z", barY);
        tvFs = U.tv(c, "", 11, U.PHOS_DIM, U.MONO);
        tvFs.setPadding(0, U.dp(c, 6), 0, 0);
        win.addView(tvFs);
        col.addView(win, U.margins(U.match(), c, 0, 12, 0, 0));

        etMemo = new EditText(c);
        etMemo.setHint("메모 (예: 핀 전량 투입)");
        etMemo.setSingleLine(true);
        etMemo.setTextSize(15);
        col.addView(etMemo, U.margins(U.match(), c, 0, 6, 0, 0));

        return sv;
    }

    private TextView reading(GridLayout g, String label) {
        Context c = a;
        LinearLayout r = U.row(c);
        r.addView(U.tv(c, label, 13, U.PHOS_DIM, U.MONO), U.lpW(1));
        TextView v = U.tv(c, "—", 13.5f, U.PHOS, U.MONO_B);
        r.addView(v);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        p.width = 0;
        p.setMargins(0, U.dp(c, 3), U.dp(c, 10), U.dp(c, 3));
        g.addView(r, p);
        return v;
    }

    private TextView fixRow(LinearLayout win, String label, Views.FixBar bar) {
        Context c = a;
        LinearLayout r = U.row(c);
        r.addView(U.tv(c, label, 12, U.PHOS_DIM, U.MONO), new LinearLayout.LayoutParams(U.dp(c, 34), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(bar, new LinearLayout.LayoutParams(0, U.dp(c, 16), 1));
        TextView t = U.tv(c, "—", 12, U.PHOS, U.MONO);
        t.setGravity(Gravity.END);
        r.addView(t, new LinearLayout.LayoutParams(U.dp(c, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
        win.addView(r, U.margins(U.match(), c, 0, 2, 0, 2));
        return t;
    }

    void setUnit(int u) {
        unit = u;
        Store.putInt(a, "measureUnit", u);
        loadMachine();
    }

    private void loadMachine() {
        Store.Machine m = Store.machine(a, unit);
        loading = true;
        tvUnit.setText(android.text.Html.fromHtml("<font color='#e2571b'>" + unit + "</font>호기", 0));
        tvSpec.setText(m.spec.isEmpty() ? "스펙 선택" : m.spec);
        etHz.setText(Double.isNaN(m.setHz) ? "" : U.f1(m.setHz));
        loading = false;
        Store.Spec s = Store.spec(a, m.spec);
        if (s != null && !Double.isNaN(s.target)) {
            gauge.target = s.target; gauge.tol = s.tol;
            gauge.max = Math.max(80, Math.ceil(s.target * 1.6 / 10) * 10);
            tvTarget.setText(String.format(Locale.US, "목표 %s ±%s", U.f1(s.target).replace(".0", ""), U.f1(s.tol).replace(".0", "")));
        } else {
            gauge.target = Double.NaN;
            gauge.max = 80;
            tvTarget.setText("목표 없음");
        }
        gauge.invalidate();
        updateVerdict();
    }

    private void pickUnit() {
        LinearLayout s = a.sheetBox("호기 선택", null);
        GridLayout g = new GridLayout(a);
        g.setColumnCount(5);
        for (int u = 1; u <= Store.UNITS; u++) {
            final int uu = u;
            TextView b = U.tv(a, String.valueOf(u), 20, u == unit ? Color.WHITE : U.INK, U.MONO_B);
            b.setGravity(Gravity.CENTER);
            b.setBackground(u == unit ? U.box(a, U.SIGNAL, 7, U.SIGNAL, 0) : U.box(a, U.ENAMEL, 7, U.INK, 2));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            p.width = 0; p.height = U.dp(a, 52);
            p.setMargins(U.dp(a, 3), U.dp(a, 3), U.dp(a, 3), U.dp(a, 3));
            b.setOnClickListener(v -> { a.closeSheet(); setUnit(uu); });
            g.addView(b, p);
        }
        s.addView(g, U.match());
        a.showSheet(s, null);
    }

    private void pickSpec() {
        LinearLayout s = a.sheetBox(unit + "호기 스펙", "목표 RMS mm/s");
        Store.Machine m = Store.machine(a, unit);
        for (Store.Spec sp : Store.specs(a)) {
            LinearLayout r = U.row(a);
            r.setPadding(U.dp(a, 12), 0, U.dp(a, 12), 0);
            boolean on = sp.name.equals(m.spec);
            r.setBackground(on ? U.box(a, U.INK, 7, U.INK, 0) : U.box(a, U.ENAMEL, 7, U.RULE, 1));
            r.addView(U.tv(a, sp.name, 17, on ? U.PLATE : U.INK, U.MONO_B), U.lpW(1));
            r.addView(U.tv(a, (Double.isNaN(sp.target) ? "목표 미정" : U.f1(sp.target) + " ±" + U.f1(sp.tol)), 14, on ? U.PHOS : U.OK, U.MONO_B));
            r.setOnClickListener(v -> {
                Store.Machine mm = Store.machine(a, unit);
                mm.spec = sp.name;
                Store.save(a, mm);
                a.closeSheet();
                loadMachine();
            });
            s.addView(r, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 50)), a, 0, 0, 0, 6));
        }
        a.showSheet(s, null);
    }

    @Override void onShow() { loadMachine(); tick(); }
    @Override void onHide() { stopMeasuring(); }

    // ---------------- 측정 ----------------
    private void start() {
        if (acc == null) { Toast.makeText(a, "가속도 센서를 찾지 못했습니다", Toast.LENGTH_LONG).show(); return; }
        synchronized (lock) { head = 0; count = 0; rHead = 0; rCount = 0; rStartWall = System.currentTimeMillis(); }
        recent.clear();
        gauge.hist.clear();
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
        running = true;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnRun.setText("멈춤");
        setStatus("측정 중…", U.WARN);
    }

    void stopMeasuring() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false;
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (btnRun != null) btnRun.setText("측정 시작");
        if (lastValid) setStatus("멈춤 · 마지막 값", U.SUB);
        else setStatus("측정 시작을 누르세요", U.SUB);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        synchronized (lock) {
            bt[head] = e.timestamp; bx[head] = e.values[0]; by[head] = e.values[1]; bz[head] = e.values[2];
            head = (head + 1) % CAP;
            if (count < CAP) count++;
            rt[rHead] = e.timestamp; rx[rHead] = e.values[0]; ry[rHead] = e.values[1]; rz[rHead] = e.values[2];
            rHead = (rHead + 1) % RAW_CAP;
            if (rCount < RAW_CAP) rCount++;
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private void setStatus(String t, int color) {
        tvStatus.setText(t);
        tvStatus.setBackground(U.box(a, color, 6, color, 0));
    }

    @Override void tick() {
        // 이 호기 타이머
        Store.Machine m = Store.machine(a, unit);
        long now = System.currentTimeMillis();
        if (m.endAt > now) { tvTimer.setText(U.mmss(m.endAt - now)); tvTimer.setTextColor(Color.rgb(0xff, 0xb4, 0x8f)); }
        else if (m.paused()) { tvTimer.setText("❚❚ " + U.mmss(m.pausedLeft)); tvTimer.setTextColor(Color.rgb(0xe8, 0xc0, 0x6a)); }
        else if (m.finished) { tvTimer.setText("완료"); tvTimer.setTextColor(U.SIGNAL); }
        else { tvTimer.setText("—"); tvTimer.setTextColor(U.PHOS); }
        if (running && now - lastAnalyze >= 450) { lastAnalyze = now; analyze(); }
    }

    private void analyze() {
        long[] t; float[] x, y, z; int n;
        synchronized (lock) {
            if (count < 64) return;
            int start = (head - count + CAP) % CAP;
            long tEnd = bt[(head - 1 + CAP) % CAP];
            long tMin = tEnd - (long) (WINDOW_SEC * 1e9);
            int skip = 0;
            while (skip < count && bt[(start + skip) % CAP] < tMin) skip++;
            n = count - skip;
            t = new long[n]; x = new float[n]; y = new float[n]; z = new float[n];
            for (int i = 0; i < n; i++) {
                int k = (start + skip + i) % CAP;
                t[i] = bt[k]; x[i] = bx[k]; y[i] = by[k]; z[i] = bz[k];
            }
        }
        double have = (t[n - 1] - t[0]) / 1e9;
        Analyzer.Result r = Analyzer.analyze(t, x, y, z, n);
        int show = Math.min(n, 125);
        float[] w = new float[show];
        System.arraycopy(z, n - show, w, 0, show);
        wave.data = w;
        wave.invalidate();
        tvFs.setText(String.format(Locale.US, "샘플 %.0f Hz · 최근 %.1f초", r.fs, have));
        if (!r.valid || r.rmsZ < MIN_ZRMS || r.prominence < MIN_PROM) {
            lastValid = false;
            recent.clear();
            gauge.value = Double.NaN;
            gauge.invalidate();
            tvFreq.setText("—"); tvPeak.setText("—");
            tvZ.setText(U.f2(r.rmsZ));
            barX.pct = Double.NaN; barY.pct = Double.NaN; barX.invalidate(); barY.invalidate();
            tvX.setText("—"); tvY.setText("—");
            setStatus("설비 진동 없음 · 진동기 위에 고정", U.SUB);
            return;
        }
        last = r;
        lastValid = true;
        gauge.value = r.vRms;
        gauge.hist.add(r.vRms);
        while (gauge.hist.size() > 8) gauge.hist.remove(0);
        gauge.invalidate();
        tvFreq.setText(String.format(Locale.US, "%.2f Hz", r.freq));
        tvZ.setText(U.f2(r.rmsZ));
        tvPeak.setText(String.format(Locale.US, "%.2f cm/s", r.vPeak));
        barX.pct = r.pctX; barY.pct = r.pctY; barX.invalidate(); barY.invalidate();
        tvX.setText(String.format(Locale.US, "%.0f%%", r.pctX));
        tvY.setText(String.format(Locale.US, "%.0f%%", r.pctY));
        recent.addLast(r.vRms);
        while (recent.size() > 6) recent.removeFirst();
        updateVerdict();
    }

    private boolean stable() {
        if (recent.size() < 6) return false;
        double mn = Double.MAX_VALUE, mx = 0, sum = 0;
        for (double v : recent) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; }
        return (mx - mn) / 2 <= STABLE_LIMIT * (sum / recent.size());
    }

    private void updateVerdict() {
        if (!running || !lastValid || last == null) return;
        boolean fixOk = last.pctX <= FIX_LIMIT && last.pctY <= FIX_LIMIT;
        if (!fixOk) { setStatus("폰 고정 불량 · 다시 고정", U.BAD); return; }
        if (!stable()) { setStatus("측정 중 · 값이 흔들림", U.WARN); return; }
        Store.Spec s = Store.spec(a, Store.machine(a, unit).spec);
        int j = U.judge(s, last.vRms);
        if (j == 0) setStatus("안정 · 목표 범위 안", U.OK);
        else if (j == 1) setStatus("안정 · 목표보다 낮음", U.WARN);
        else if (j == 2) setStatus("안정 · 목표보다 높음", U.BAD);
        else setStatus("안정 · 기록해도 됩니다", U.OK);
    }

    private void save() {
        if (!lastValid || last == null) { Toast.makeText(a, "설비 위에서 먼저 측정하세요", Toast.LENGTH_SHORT).show(); return; }
        Store.Machine m = Store.machine(a, unit);
        Store.Record r = new Store.Record();
        r.time = System.currentTimeMillis();
        r.unit = unit;
        r.spec = m.spec;
        r.setHz = m.setHz;
        r.freq = last.freq; r.vRms = last.vRms; r.vPeak = last.vPeak; r.zRms = last.rmsZ;
        r.pctX = last.pctX; r.pctY = last.pctY; r.fs = last.fs;
        r.memo = etMemo.getText().toString().trim();
        if (!stable()) r.memo = (r.memo.isEmpty() ? "" : r.memo + " · ") + "불안정할 때 기록";
        Store.addRecord(a, r);
        etMemo.setText("");
        // 원본 꺼내고 다음 측정을 위해 비움
        long[] t; float[] x, y, z; int n; long wall0;
        synchronized (lock) {
            n = rCount;
            t = new long[n]; x = new float[n]; y = new float[n]; z = new float[n];
            int st = (rHead - rCount + RAW_CAP) % RAW_CAP;
            for (int i = 0; i < n; i++) {
                int k = (st + i) % RAW_CAP;
                t[i] = rt[k]; x[i] = rx[k]; y[i] = ry[k]; z[i] = rz[k];
            }
            wall0 = rStartWall;
            rHead = 0; rCount = 0; rStartWall = System.currentTimeMillis();
        }
        boolean stab = stable();
        RawExport.saveAsync(a, r, stab, t, x, y, z, n, wall0, acc);
        Toast.makeText(a, String.format(Locale.US, "%d호기 %.1f mm/s 기록 · 원본 저장 중", unit, r.vRms), Toast.LENGTH_SHORT).show();
    }
}
