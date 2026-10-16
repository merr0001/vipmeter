package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 측정 탭 (자동 측정)
 * 폰 뒷면을 설비에 대면 진동을 감지해 스스로 시작하고,
 * 가장 안정된 구간을 골라 저장한 뒤 완료/실패 메시지를 띄운다.
 */
class MeasureScreen extends MainActivity.Screen implements SensorEventListener {

    // ---- 계산 창 ----
    private static final double WINDOW_SEC = 3.0;   // 값 하나 = 최근 3초
    private static final long STEP_MS = 450;        // 0.45초마다 새로 계산
    private static final int CAP = 8192;
    // ---- 진동 감지 ----
    private static final double MIN_ZRMS = 3.0;     // 이보다 약하면 진동 없음
    private static final double MIN_PROM = 8.0;     // 주파수 봉우리 뚜렷함
    private static final double SHORT_SEC = 0.5;    // 대고/떼는 것 판단용 짧은 창
    // ---- 안정 구간 규칙 (Phyphox 기록으로 검증) ----
    private static final double FIX_LIMIT = 20.0;   // X/Z, Y/Z 이하 %
    private static final double FREQ_TOL = 0.2;     // 대표 주파수 ±Hz
    private static final double CAND_TOL = 0.10;    // 중앙값 ±10%
    private static final int RUN = 5;               // 연속 5개 창
    private static final double CV_LIMIT = 0.03;    // 연속 5개 변동 3% 이하
    private static final double TREND_LIMIT = 0.08; // 세기 변화 8% 이하
    private static final double NEED_SEC = 6.0;     // 안정 구간 6초
    private static final double SPAN_SEC = 8.0;     // 최근 8초 안에서 판단
    private static final double TIMEOUT_SEC = 18.0; // 이 안에 못 찾으면 실패
    private static final long DONE_AUTOCLOSE_MS = 5000;

    private static final int IDLE = 0, MEAS = 1, LIFT = 2;

    private final long[] bt = new long[CAP];
    private final float[] bx = new float[CAP], by = new float[CAP], bz = new float[CAP];
    private int head = 0, count = 0;
    // 원본 (zip 저장용, 약 4분)
    private static final int RAW_CAP = 120000;
    private final long[] rt = new long[RAW_CAP];
    private final float[] rx = new float[RAW_CAP], ry = new float[RAW_CAP], rz = new float[RAW_CAP];
    private int rHead = 0, rCount = 0;
    private final Object lock = new Object();

    private final SensorManager sm;
    private final Sensor acc;
    private boolean listening;
    private final Handler h = new Handler(Looper.getMainLooper());

    private int state = IDLE;
    private int onCount, offCount;
    private long beginNs;          // 자동 시작 시각 (센서 시간)
    private long rawFromNs;        // 원본 저장 시작
    private final List<Win> wins = new ArrayList<>();

    /** 3초 창 하나의 결과 */
    private static final class Win {
        long end; double v, f, z, peak, px, py, prom, h2, fs;
        boolean pass;
    }

    int unit;
    private TextView tvUnit, tvSpec, tvStatus, tvTarget, tvGuide, tvProg, tvFreq, tvZ, tvPeak, tvTimer, tvFs, tvX, tvY;
    private final TextView[] checks = new TextView[4];
    private ProgressBar prog;
    private EditText etHz, etMemo;
    private Views.GaugeView gauge;
    private Views.WaveView wave;
    private Views.FixBar barX, barY;
    private FrameLayout lockLayer, modalLayer;
    private boolean loading;
    private Runnable autoClose;

    MeasureScreen(MainActivity a) {
        super(a);
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        unit = Store.getInt(a, "measureUnit", 1);
    }

    @Override
    View build() {
        Context c = a;
        FrameLayout root = new FrameLayout(c);
        ScrollView sv = new ScrollView(c);
        LinearLayout col = U.col(c);
        col.setPadding(U.dp(c, 14), U.dp(c, 10), U.dp(c, 14), U.dp(c, 16));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        // 상태 + 목표
        LinearLayout verdict = U.row(c);
        tvStatus = U.tv(c, "", 15, Color.WHITE, U.BOLD);
        tvStatus.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        verdict.addView(tvStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        tvTarget = U.tv(c, "", 13.5f, U.INK, null);
        tvTarget.setBackground(U.box(c, U.PLATE, 6, U.RULE, 1));
        tvTarget.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = U.dp(c, 8);
        verdict.addView(tvTarget, tl);
        col.addView(verdict, U.match());

        // 안내 + 진행 + 확인 항목
        LinearLayout card = U.col(c);
        card.setBackground(U.box(c, U.PLATE, 7, U.RULE, 1));
        card.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        tvGuide = U.tv(c, "", 14.5f, U.INK, U.BOLD);
        card.addView(tvGuide);
        LinearLayout pr = U.row(c);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        prog = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(1000);
        prog.setProgressTintList(android.content.res.ColorStateList.valueOf(U.OK));
        pr.addView(prog, new LinearLayout.LayoutParams(0, U.dp(c, 14), 1));
        tvProg = U.tv(c, "", 12.5f, U.SUB, U.MONO_B);
        tvProg.setPadding(U.dp(c, 8), 0, 0, 0);
        pr.addView(tvProg);
        card.addView(pr, U.margins(U.match(), c, 0, 8, 0, 6));
        GridLayout cg = new GridLayout(c);
        cg.setColumnCount(2);
        String[] cn = {"주파수 일정", "옆 흔들림", "값 변동", "세기 변화"};
        for (int i = 0; i < 4; i++) {
            TextView t = U.tv(c, "", 12.5f, U.SUB, U.BOLD);
            t.setPadding(U.dp(c, 8), U.dp(c, 5), U.dp(c, 8), U.dp(c, 5));
            GridLayout.LayoutParams p = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            p.width = 0;
            p.setMargins(U.dp(c, 2), U.dp(c, 2), U.dp(c, 2), U.dp(c, 2));
            t.setTag(cn[i]);
            cg.addView(t, p);
            checks[i] = t;
        }
        card.addView(cg, U.match());
        col.addView(card, U.margins(U.match(), c, 0, 8, 0, 0));

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
        etMemo.setHint("메모 (다음 측정 기록에 붙음)");
        etMemo.setSingleLine(true);
        etMemo.setTextSize(15);
        col.addView(etMemo, U.margins(U.match(), c, 0, 6, 0, 0));

        // 측정 중 터치 막기
        lockLayer = new FrameLayout(c);
        lockLayer.setClickable(true);
        lockLayer.setBackgroundColor(Color.argb(0x18, 0, 0, 0));
        TextView lk = U.tv(c, "🔒 측정 중에는 화면 터치가 막혀 있습니다", 13.5f, Color.WHITE, U.BOLD);
        lk.setGravity(Gravity.CENTER);
        lk.setBackground(U.box(c, U.INK, 20, U.INK, 0));
        lk.setPadding(U.dp(c, 16), U.dp(c, 9), U.dp(c, 16), U.dp(c, 9));
        FrameLayout.LayoutParams lkp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lkp.bottomMargin = U.dp(c, 14);
        lockLayer.addView(lk, lkp);
        lockLayer.setVisibility(View.GONE);
        root.addView(lockLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 완료/실패 창
        modalLayer = new FrameLayout(c);
        modalLayer.setClickable(true);
        modalLayer.setBackgroundColor(Color.argb(0x99, 0x10, 0x14, 0x12));
        modalLayer.setVisibility(View.GONE);
        root.addView(modalLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        showIdle();
        return root;
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

    @Override void onShow() { loadMachine(); tick(); startSensor(); }
    @Override void onHide() { stopMeasuring(); }

    // ---------------- 센서 ----------------
    private void startSensor() {
        if (listening) return;
        if (acc == null) { setStatus("가속도 센서를 찾지 못했습니다", U.BAD); return; }
        synchronized (lock) { head = 0; count = 0; rHead = 0; rCount = 0; }
        state = IDLE; onCount = 0; offCount = 0; wins.clear();
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
        listening = true;
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (modalLayer.getVisibility() != View.VISIBLE) showIdle();
        h.removeCallbacks(loop);
        h.postDelayed(loop, STEP_MS);
    }

    /** 화면을 벗어나면 센서를 끈다 (측정 중이면 취소) */
    void stopMeasuring() {
        h.removeCallbacks(loop);
        if (!listening) return;
        sm.unregisterListener(this);
        listening = false;
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state = IDLE;
        wins.clear();
        if (lockLayer != null) lockLayer.setVisibility(View.GONE);
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            try { step(); } catch (Exception ignored) {}
            h.postDelayed(this, STEP_MS);
        }
    };

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
    }

    // ---------------- 한 번 계산 (0.45초마다) ----------------
    private void step() {
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
        if (n < 64) return;
        long tEnd = t[n - 1];
        double have = (tEnd - t[0]) / 1e9;

        // 짧은 창(0.5초): 지금 설비에 대고 있는지
        int s0 = n - 1;
        while (s0 > 0 && t[s0 - 1] >= tEnd - (long) (SHORT_SEC * 1e9)) s0--;
        double shortStd = Analyzer.std(Arrays.copyOfRange(z, s0, n), n - s0);
        boolean on = shortStd >= MIN_ZRMS;

        Analyzer.Result r = Analyzer.analyze(t, x, y, z, n);
        boolean vib = r.valid && r.rmsZ >= MIN_ZRMS && r.prominence >= MIN_PROM;
        showLive(r, vib, z, n, have);

        if (state == IDLE) {
            if (modalLayer.getVisibility() == View.VISIBLE) return;
            onCount = (on && vib) ? onCount + 1 : 0;
            if (onCount >= 2) begin(tEnd);
        } else if (state == MEAS) {
            offCount = on ? 0 : offCount + 1;
            if (offCount >= 2) { cancel(); return; }
            // 자동 시작 이후의 3초 창만 사용 (손이 자리 잡는 시간 제외)
            if (r.valid && t[0] >= beginNs && have >= WINDOW_SEC - 0.2) {
                Win w = new Win();
                w.end = tEnd; w.v = r.vRms; w.f = r.freq; w.z = r.rmsZ; w.peak = r.vPeak;
                w.px = r.pctX; w.py = r.pctY; w.prom = r.prominence; w.h2 = r.h2; w.fs = r.fs;
                wins.add(w);
            }
            evaluate(tEnd);
        } else if (state == LIFT) {
            offCount = on ? 0 : offCount + 1;
            if (offCount >= 2) {
                state = IDLE; onCount = 0;
                if (modalLayer.getVisibility() != View.VISIBLE) showIdle();
            }
        }
    }

    private void showLive(Analyzer.Result r, boolean vib, float[] z, int n, double have) {
        int show = Math.min(n, 125);
        float[] w = new float[show];
        System.arraycopy(z, n - show, w, 0, show);
        wave.data = w;
        wave.invalidate();
        tvFs.setText(String.format(Locale.US, "샘플 %.0f Hz · 최근 %.1f초", r.fs, have));
        tvZ.setText(U.f2(r.rmsZ));
        if (!vib) {
            if (state != MEAS) { gauge.value = Double.NaN; gauge.invalidate(); }
            tvFreq.setText("—"); tvPeak.setText("—");
            barX.pct = Double.NaN; barY.pct = Double.NaN; barX.invalidate(); barY.invalidate();
            tvX.setText("—"); tvY.setText("—");
            return;
        }
        gauge.value = r.vRms;
        gauge.hist.add(r.vRms);
        while (gauge.hist.size() > 8) gauge.hist.remove(0);
        gauge.invalidate();
        tvFreq.setText(String.format(Locale.US, "%.2f Hz", r.freq));
        tvPeak.setText(String.format(Locale.US, "%.2f cm/s", r.vPeak));
        barX.pct = r.pctX; barY.pct = r.pctY; barX.invalidate(); barY.invalidate();
        tvX.setText(String.format(Locale.US, "%.0f%%", r.pctX));
        tvY.setText(String.format(Locale.US, "%.0f%%", r.pctY));
    }

    // ---------------- 상태 ----------------
    private void showIdle() {
        setStatus("진동을 기다리는 중", U.SUB);
        tvGuide.setText("폰 뒷면을 설비 중앙에 대고 손가락으로 누르세요");
        prog.setProgress(0);
        tvProg.setText("");
        for (TextView c : checks) setCheck(c, 0, null);
        lockLayer.setVisibility(View.GONE);
    }

    private void begin(long tEnd) {
        state = MEAS;
        offCount = 0;
        beginNs = tEnd;
        rawFromNs = tEnd - (long) (WINDOW_SEC * 1e9);
        wins.clear();
        gauge.hist.clear();
        setStatus("자동 시작 · 측정 중", U.WARN);
        tvGuide.setText("그대로 누르고 계세요");
        prog.setProgress(0);
        tvProg.setText(String.format(Locale.US, "안정 구간 0.0 / %.0f초", NEED_SEC));
        for (TextView c : checks) setCheck(c, 0, null);
        lockLayer.setVisibility(View.VISIBLE);
    }

    private void cancel() {
        state = IDLE;
        onCount = 0;
        wins.clear();
        showIdle();
        setStatus("측정 취소 · 폰을 뗐습니다", U.SUB);
    }

    /** 0 대기, 1 통과, 2 문제 */
    private void setCheck(TextView c, int st, String extra) {
        String name = (String) c.getTag();
        String mark = st == 1 ? "✓ " : st == 2 ? "✕ " : "· ";
        c.setText(mark + name + (extra != null ? " " + extra : ""));
        int bg = st == 1 ? Color.rgb(0xd3, 0xec, 0xdc) : st == 2 ? Color.rgb(0xf3, 0xd2, 0xcc) : U.ENAMEL;
        int fg = st == 1 ? U.OK : st == 2 ? U.BAD : U.SUB;
        c.setTextColor(fg);
        c.setBackground(U.box(a, bg, 5, bg, 0));
    }

    private static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2;
    }

    /** 선택 결과 */
    private static final class Pick {
        final List<Win> span = new ArrayList<>();
        int passN;
        double fdom = Double.NaN, trend = Double.NaN, sideBadFrac, medPy, medPx;
        List<Win> best;  // 연속 5개
        double bestCv = Double.NaN;
    }

    private Pick pick(long nowNs) {
        Pick p = new Pick();
        long from = nowNs - (long) (SPAN_SEC * 1e9);
        for (Win w : wins) if (w.end >= from) p.span.add(w);
        int n = p.span.size();
        if (n == 0) return p;
        double[] fs = new double[n], h2 = new double[n], px = new double[n], py = new double[n];
        int side = 0;
        for (int i = 0; i < n; i++) {
            Win w = p.span.get(i);
            fs[i] = w.f; h2[i] = w.h2; px[i] = w.px; py[i] = w.py;
            if (w.px > FIX_LIMIT || w.py > FIX_LIMIT) side++;
        }
        p.sideBadFrac = (double) side / n;
        p.medPx = median(px); p.medPy = median(py);
        p.fdom = median(fs);
        double h2m = median(h2);
        List<Double> pv = new ArrayList<>();
        for (Win w : p.span) {
            w.pass = Math.abs(w.f - p.fdom) <= FREQ_TOL && w.px <= FIX_LIMIT && w.py <= FIX_LIMIT
                    && w.h2 <= 2 * h2m + 10 && w.prom >= MIN_PROM;
            if (w.pass) pv.add(w.v);
        }
        p.passN = pv.size();
        if (p.passN == 0) return p;
        double[] pa = new double[pv.size()];
        for (int i = 0; i < pa.length; i++) pa[i] = pv.get(i);
        double vmed = median(pa);
        // 세기 변화: 통과 창 처음 3개 평균 vs 마지막 3개 평균
        if (pa.length >= 6) {
            double a1 = (pa[0] + pa[1] + pa[2]) / 3, a2 = (pa[pa.length - 1] + pa[pa.length - 2] + pa[pa.length - 3]) / 3;
            p.trend = (a2 - a1) / a1;
        }
        // 연속 5개가 모두 후보(통과 + 중앙값 ±10%)인 곳 중 변동 최소
        for (int i = 0; i + RUN <= n; i++) {
            boolean ok = true;
            double sum = 0;
            for (int k = i; k < i + RUN; k++) {
                Win w = p.span.get(k);
                if (!w.pass || Math.abs(w.v - vmed) > CAND_TOL * vmed) { ok = false; break; }
                sum += w.v;
            }
            if (!ok) continue;
            double mean = sum / RUN, ss = 0;
            for (int k = i; k < i + RUN; k++) { double d = p.span.get(k).v - mean; ss += d * d; }
            double cv = Math.sqrt(ss / RUN) / mean;
            if (p.best == null || cv < p.bestCv) { p.bestCv = cv; p.best = new ArrayList<>(p.span.subList(i, i + RUN)); }
        }
        return p;
    }

    private void evaluate(long nowNs) {
        double elapsed = (nowNs - beginNs) / 1e9;
        Pick p = pick(nowNs);
        double stableSec = Math.min(NEED_SEC, p.passN * STEP_MS / 1000.0);
        prog.setProgress((int) (1000 * stableSec / NEED_SEC));
        tvProg.setText(String.format(Locale.US, "안정 구간 %.1f / %.0f초", stableSec, NEED_SEC));

        Win lastW = p.span.isEmpty() ? null : p.span.get(p.span.size() - 1);
        if (lastW == null) {
            for (TextView c : checks) setCheck(c, 0, null);
        } else {
            setCheck(checks[0], Math.abs(lastW.f - p.fdom) <= FREQ_TOL ? 1 : 2, null);
            double side = Math.max(lastW.px, lastW.py);
            setCheck(checks[1], side <= FIX_LIMIT ? 1 : 2, String.format(Locale.US, "%.0f%%", side));
            setCheck(checks[2], p.best == null ? 0 : (p.bestCv <= CV_LIMIT ? 1 : 2),
                    p.best == null ? null : String.format(Locale.US, "%.1f%%", p.bestCv * 100));
            setCheck(checks[3], Double.isNaN(p.trend) ? 0 : (Math.abs(p.trend) <= TREND_LIMIT ? 1 : 2),
                    Double.isNaN(p.trend) ? null : String.format(Locale.US, "%+.0f%%", p.trend * 100));
        }
        if (lastW != null && Math.max(lastW.px, lastW.py) > FIX_LIMIT) {
            setStatus("옆 흔들림 큼 · 더 꽉 누르세요", U.BAD);
            tvGuide.setText("폰 뒷면이 설비에 딱 붙게 더 꽉 눌러 주세요");
        } else {
            setStatus("자동 시작 · 측정 중", U.WARN);
            tvGuide.setText("그대로 누르고 계세요");
        }

        boolean enough = p.passN * STEP_MS / 1000.0 >= NEED_SEC;
        boolean trendOk = !Double.isNaN(p.trend) && Math.abs(p.trend) <= TREND_LIMIT;
        if (enough && p.best != null && p.bestCv <= CV_LIMIT && trendOk) { complete(p); return; }
        if (elapsed >= TIMEOUT_SEC) fail(p, elapsed);
    }

    // ---------------- 완료 / 실패 ----------------
    private Store.Record recordFrom(List<Win> ws) {
        Store.Machine m = Store.machine(a, unit);
        Store.Record r = new Store.Record();
        r.time = System.currentTimeMillis();
        r.unit = unit;
        r.spec = m.spec;
        r.setHz = m.setHz;
        double v = 0, f = 0, z = 0, pk = 0, px = 0, py = 0, fs = 0;
        for (Win w : ws) { v += w.v; f += w.f; z += w.z; pk += w.peak; px += w.px; py += w.py; fs += w.fs; }
        int k = ws.size();
        r.vRms = v / k; r.freq = f / k; r.zRms = z / k; r.vPeak = pk / k;
        r.pctX = px / k; r.pctY = py / k; r.fs = fs / k;
        r.memo = etMemo.getText().toString().trim();
        return r;
    }

    private void complete(Pick p) {
        Store.Record r = recordFrom(p.best);
        Store.addRecord(a, r);
        etMemo.setText("");
        double selSec = (p.best.get(RUN - 1).end - p.best.get(0).end) / 1e9 + WINDOW_SEC;
        exportRaw(r, "완료", p);
        state = LIFT; offCount = 0;
        lockLayer.setVisibility(View.GONE);
        prog.setProgress(1000);
        setStatus("측정 완료 · 저장됨", U.OK);
        tvGuide.setText("폰을 떼고 다음 호기를 고르세요");
        showDone(r, p, selSec);
    }

    private void fail(Pick p, double elapsed) {
        String title, fix;
        if (p.sideBadFrac >= 0.3) {
            boolean y = p.medPy >= p.medPx;
            title = String.format(Locale.US, "옆 흔들림이 커요 (%s %.0f%%)", y ? "Y" : "X", y ? p.medPy : p.medPx);
            fix = "폰 뒷면이 설비에 딱 붙게 더 꽉 눌러 주세요";
        } else if (!Double.isNaN(p.trend) && Math.abs(p.trend) > TREND_LIMIT) {
            title = String.format(Locale.US, "세기가 변하는 중이에요 (%+.0f%%)", p.trend * 100);
            fix = "설비가 안정된 뒤 다시 측정해 주세요";
        } else {
            title = "진동이 약하거나 주파수가 불안정해요";
            fix = "폰 뒷면을 설비 중앙에 대고 다시 측정해 주세요";
        }
        List<Win> src = p.span.isEmpty() ? wins : p.span;
        if (!src.isEmpty()) exportRaw(recordFrom(src), "실패 · " + title, p);
        state = LIFT; offCount = 0;
        lockLayer.setVisibility(View.GONE);
        setStatus("측정 실패 · 저장 안 함", U.BAD);
        tvGuide.setText(fix);
        showFail(title, fix, elapsed);
    }

    private void exportRaw(Store.Record r, String verdict, Pick p) {
        long[] t; float[] x, y, z; int n;
        synchronized (lock) {
            int st = (rHead - rCount + RAW_CAP) % RAW_CAP;
            int skip = 0;
            while (skip < rCount && rt[(st + skip) % RAW_CAP] < rawFromNs) skip++;
            n = rCount - skip;
            t = new long[n]; x = new float[n]; y = new float[n]; z = new float[n];
            for (int i = 0; i < n; i++) {
                int k = (st + skip + i) % RAW_CAP;
                t[i] = rt[k]; x[i] = rx[k]; y[i] = ry[k]; z[i] = rz[k];
            }
        }
        if (n < 2) return;
        long wall0 = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - t[0]) / 1_000_000L;
        List<String[]> ex = new ArrayList<>();
        ex.add(new String[]{"측정 방식", "자동"});
        ex.add(new String[]{"대표 주파수 Hz", Double.isNaN(p.fdom) ? "" : String.format(Locale.US, "%.3f", p.fdom)});
        ex.add(new String[]{"통과 창 수", p.passN + " / " + p.span.size()});
        ex.add(new String[]{"연속5 변동 %", Double.isNaN(p.bestCv) ? "" : String.format(Locale.US, "%.2f", p.bestCv * 100)});
        ex.add(new String[]{"세기 변화 %", Double.isNaN(p.trend) ? "" : String.format(Locale.US, "%+.1f", p.trend * 100)});
        if (p.best != null) {
            double s = (p.best.get(0).end - t[0]) / 1e9 - WINDOW_SEC, e = (p.best.get(RUN - 1).end - t[0]) / 1e9;
            ex.add(new String[]{"선택 구간 s", String.format(Locale.US, "%.2f ~ %.2f", s, e)});
        }
        StringBuilder wl = new StringBuilder();
        for (Win w : p.span) wl.append(String.format(Locale.US, "%.2f:%.2f%s ", (w.end - t[0]) / 1e9, w.v, w.pass ? "" : "x"));
        ex.add(new String[]{"창 값 (끝시각s:mm/s · x=불통과)", wl.toString().trim()});
        RawExport.saveAsync(a, r, verdict, ex.toArray(new String[0][]), t, x, y, z, n, wall0, acc);
    }

    // ---------------- 메시지 창 ----------------
    private LinearLayout modalCard() {
        if (autoClose != null) h.removeCallbacks(autoClose);
        modalLayer.removeAllViews();
        LinearLayout card = U.col(a);
        card.setBackground(U.box(a, U.PLATE, 14, U.RULE, 1));
        card.setPadding(U.dp(a, 20), U.dp(a, 18), U.dp(a, 20), U.dp(a, 16));
        card.setElevation(U.dp(a, 8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.leftMargin = lp.rightMargin = U.dp(a, 22);
        modalLayer.addView(card, lp);
        modalLayer.setVisibility(View.VISIBLE);
        return card;
    }

    private void closeModal() {
        if (autoClose != null) h.removeCallbacks(autoClose);
        autoClose = null;
        modalLayer.setVisibility(View.GONE);
        if (state == IDLE) showIdle();
    }

    private void showDone(Store.Record r, Pick p, double selSec) {
        LinearLayout c = modalCard();
        c.addView(U.tv(a, "✓ 측정 완료 · 저장됨", 19, U.OK, U.BOLD));
        c.addView(U.tv(a, unit + "호기 · " + (r.spec.isEmpty() ? "스펙 없음" : r.spec), 14.5f, U.SUB, U.BOLD),
                U.margins(U.match(), a, 0, 6, 0, 0));
        LinearLayout vr = U.row(a);
        vr.setGravity(Gravity.BOTTOM);
        vr.addView(U.tv(a, U.f1(r.vRms), 46, U.INK, U.MONO_B));
        vr.addView(U.tv(a, " mm/s", 16, U.SUB, U.MONO));
        c.addView(vr, U.margins(U.match(), a, 0, 4, 0, 0));
        Store.Spec s = Store.spec(a, r.spec);
        int j = U.judge(s, r.vRms);
        if (j >= 0) {
            TextView tg = U.tag(a, U.judgeText(j), j);
            tg.setTextSize(13);
            LinearLayout tr = U.row(a);
            tr.addView(tg);
            c.addView(tr, U.margins(U.match(), a, 0, 2, 0, 0));
        }
        c.addView(U.tv(a, String.format(Locale.US, "%.2f Hz · 안정 구간 %.1f초 · 변동 %.1f%%", r.freq, selSec, p.bestCv * 100),
                12.5f, U.SUB, U.MONO), U.margins(U.match(), a, 0, 10, 0, 0));
        LinearLayout br = U.row(a);
        final long key = r.time;
        final int keyUnit = r.unit;
        TextView undo = U.button(a, "되돌리기", 1, v -> {
            List<Store.Record> l = Store.records(a);
            l.removeIf(x -> x.time == key && x.unit == keyUnit);
            Store.saveRecords(a, l);
            Toast.makeText(a, "방금 기록을 지웠습니다", Toast.LENGTH_SHORT).show();
            closeModal();
        });
        br.addView(undo, new LinearLayout.LayoutParams(0, U.dp(a, 52), 1));
        TextView ok = U.button(a, "확인", 0, v -> closeModal());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, U.dp(a, 52), 1.4f);
        ol.leftMargin = U.dp(a, 8);
        br.addView(ok, ol);
        c.addView(br, U.margins(U.match(), a, 0, 14, 0, 0));
        TextView note = U.tv(a, "5초 뒤 자동으로 닫힘", 11.5f, U.SUB, null);
        note.setGravity(Gravity.CENTER);
        c.addView(note, U.margins(U.match(), a, 0, 8, 0, 0));
        autoClose = this::closeModal;
        h.postDelayed(autoClose, DONE_AUTOCLOSE_MS);
    }

    private void showFail(String title, String fix, double elapsed) {
        LinearLayout c = modalCard();
        c.addView(U.tv(a, "✕ 측정 실패 · 저장 안 함", 19, U.BAD, U.BOLD));
        LinearLayout box = U.col(a);
        int bg = Color.rgb(0xf3, 0xd2, 0xcc);
        box.setBackground(U.box(a, bg, 8, bg, 0));
        box.setPadding(U.dp(a, 12), U.dp(a, 10), U.dp(a, 12), U.dp(a, 10));
        box.addView(U.tv(a, "⚠ " + title, 15, U.BAD, U.BOLD));
        box.addView(U.tv(a, fix, 14, U.INK, null), U.margins(U.match(), a, 0, 4, 0, 0));
        c.addView(box, U.margins(U.match(), a, 0, 10, 0, 0));
        c.addView(U.tv(a, String.format(Locale.US, "%.0f초 동안 안정 구간을 찾지 못했습니다", elapsed), 12.5f, U.SUB, null),
                U.margins(U.match(), a, 0, 10, 0, 0));
        TextView again = U.button(a, "다시 측정", 0, v -> {
            state = IDLE; onCount = 0; wins.clear();
            closeModal();
        });
        c.addView(again, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 52)), a, 0, 14, 0, 0));
        TextView close = U.button(a, "닫기", 1, v -> closeModal());
        c.addView(close, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, U.dp(a, 48)), a, 0, 8, 0, 0));
    }
}
