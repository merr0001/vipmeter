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
class MeasureScreen extends MainActivity.Screen implements SensorEventListener, CapsuleLink.Listener {

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
    private TextView tvMode;
    // 측정 방식: 폰 센서 / 캡슐
    private boolean useCap;
    private CapsuleLink cap;
    private TextView tvSrcPhone, tvSrcCap, tvCap;
    private long lastCapT = Long.MIN_VALUE;
    private TextView tvRock;

    // 주파수 조정 모드: 설정 주파수를 고치면 3회 측정 평균
    private static final int ADJ_N = 3, ADJ_MAX = 5;
    private static final double ADJ_OUT = 0.08;     // 중앙값에서 8% 넘게 튀면 제외
    private int adjUnit = -1;
    private double adjPrevHz = Double.NaN, adjPrevV = Double.NaN;
    private final List<Store.Record> adjVals = new ArrayList<>();
    private double loadedHz = Double.NaN;
    // 경계 근처: 한 번 더 재면 2회 평균
    private static final double EDGE = 1.5;
    private static final long PEND_MS = 3 * 60 * 1000L;
    private long pendKey = -1, pendAt;
    private int pendUnit = -1;
    private Store.Record pendFirst;

    MeasureScreen(MainActivity a) {
        super(a);
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        unit = Store.getInt(a, "measureUnit", 1);
        useCap = Store.getInt(a, "measureSrc", 0) == 1;
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

        // 측정 방식 [폰] [캡슐] + 캡슐 상태
        LinearLayout src = U.row(c);
        src.setGravity(Gravity.CENTER_VERTICAL);
        tvSrcPhone = U.tv(c, "📱 폰", 14, U.INK, U.BOLD);
        tvSrcCap = U.tv(c, "🔵 캡슐", 14, U.INK, U.BOLD);
        for (TextView t : new TextView[]{tvSrcPhone, tvSrcCap}) { t.setGravity(Gravity.CENTER); t.setPadding(U.dp(c, 14), U.dp(c, 7), U.dp(c, 14), U.dp(c, 7)); }
        tvSrcPhone.setOnClickListener(v -> setSource(false));
        tvSrcCap.setOnClickListener(v -> setSource(true));
        src.addView(tvSrcPhone);
        src.addView(tvSrcCap, U.margins(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), c, 6, 0, 0, 0));
        tvCap = U.tv(c, "", 12.5f, U.SUB, null);
        tvCap.setPadding(U.dp(c, 10), 0, 0, 0);
        tvCap.setOnClickListener(v -> capMenu());
        src.addView(tvCap, U.lpW(1));

        col.addView(src, U.margins(U.match(), c, 0, 8, 0, 0));

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
                if (!Double.isNaN(m.setHz) && (Double.isNaN(loadedHz) || Math.abs(m.setHz - loadedHz) > 1e-6)) startAdjust();
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
        tvStatus.setOnClickListener(v -> { if (useCap && state == LIFT && modalLayer.getVisibility() != View.VISIBLE) { state = IDLE; onCount = 0; offCount = 0; wins.clear(); showIdle(); } });
        tvStatus.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        verdict.addView(tvStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
        tvTarget = U.tv(c, "", 13.5f, U.INK, null);
        tvTarget.setBackground(U.box(c, U.PLATE, 6, U.RULE, 1));
        tvTarget.setPadding(U.dp(c, 12), U.dp(c, 10), U.dp(c, 12), U.dp(c, 10));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.leftMargin = U.dp(c, 8);
        verdict.addView(tvTarget, tl);
        col.addView(verdict, U.match());

        // 주파수 조정 모드 띠
        tvMode = U.tv(c, "", 13.5f, Color.WHITE, U.BOLD);
        tvMode.setBackground(U.box(c, Color.rgb(0x2f, 0x5f, 0x8a), 6, Color.rgb(0x2f, 0x5f, 0x8a), 0));
        tvMode.setPadding(U.dp(c, 12), U.dp(c, 9), U.dp(c, 12), U.dp(c, 9));
        tvMode.setVisibility(View.GONE);
        tvMode.setOnClickListener(v -> new android.app.AlertDialog.Builder(a)
                .setTitle("주파수 조정 모드를 끌까요?")
                .setMessage("지금까지 잰 조정 측정은 저장하지 않습니다.")
                .setPositiveButton("끄기", (d, w) -> { stopAdjust(); })
                .setNegativeButton("계속", null).show());
        col.addView(tvMode, U.margins(U.match(), c, 0, 8, 0, 0));

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
        tvRock = U.tv(c, "", 12, U.PHOS, U.MONO);
        tvRock.setPadding(0, U.dp(c, 4), 0, 0);
        win.addView(tvRock);
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
        loadedHz = m.setHz;
        if (adjUnit != -1 && adjUnit != unit) { adjUnit = -1; adjVals.clear(); }
        if (pendUnit != unit) { pendKey = -1; pendUnit = -1; pendFirst = null; }
        updateMode();
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

    @Override void onShow() { loadMachine(); tick(); paintSource(); startSensor(); }
    @Override void onHide() { stopMeasuring(); }

    // ---------------- 센서 ----------------
    private void startSensor() {
        if (listening) return;
        if (!useCap && acc == null) { setStatus("가속도 센서를 찾지 못했습니다", U.BAD); return; }
        synchronized (lock) { head = 0; count = 0; rHead = 0; rCount = 0; lastCapT = Long.MIN_VALUE; }
        state = IDLE; onCount = 0; offCount = 0; wins.clear();
        if (useCap) {
            if (cap == null) cap = new CapsuleLink(a, this);
            cap.resetAxes();
            cap.start();
        } else sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
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
        if (cap != null) cap.stop();
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

    // ---------------- 캡슐 ----------------
    @Override public void onCapsuleSamples(long[] t, float[] x, float[] y, float[] z, int n) {
        if (!useCap || !listening) return;
        synchronized (lock) {
            if (t[0] < lastCapT) { head = 0; count = 0; rHead = 0; rCount = 0; }   // 캡슐이 다시 시작됨
            for (int i = 0; i < n; i++) {
                bt[head] = t[i]; bx[head] = x[i]; by[head] = y[i]; bz[head] = z[i];
                head = (head + 1) % CAP;
                if (count < CAP) count++;
                rt[rHead] = t[i]; rx[rHead] = x[i]; ry[rHead] = y[i]; rz[rHead] = z[i];
                rHead = (rHead + 1) % RAW_CAP;
                if (rCount < RAW_CAP) rCount++;
            }
            lastCapT = t[n - 1];
        }
    }

    @Override public void onCapsuleState(int st, String msg) {
        if (!useCap || tvCap == null) return;
        paintCap();
        if (st != CapsuleLink.ON && state == MEAS) cancel();
        if (state == IDLE && modalLayer.getVisibility() != View.VISIBLE) showIdle();
    }

    private void paintCap() {
        if (!useCap || cap == null) { tvCap.setText(""); return; }
        String s;
        int col = U.SUB;
        switch (cap.state) {
            case CapsuleLink.ON:
                s = "● " + cap.name + (cap.battery >= 0 ? " · 🔋" + cap.battery + "%" : "") + (Double.isNaN(cap.fs) ? "" : String.format(Locale.US, " · %.0fHz", cap.fs));
                col = cap.battery >= 0 && cap.battery < 20 ? U.BAD : U.OK; break;
            case CapsuleLink.NOPERM: s = "⚠ 블루투스 권한 필요 · 눌러서 허용"; col = U.BAD; break;
            case CapsuleLink.NOBT: s = "⚠ 폰 블루투스를 켜 주세요"; col = U.BAD; break;
            case CapsuleLink.OFF: s = "연결 안 됨"; break;
            default: s = "◌ 캡슐 연결 중…"; col = U.WARN;
        }
        tvCap.setText(s);
        tvCap.setTextColor(col);
    }

    private void setSource(boolean c) {
        if (c == useCap) { if (c) capMenu(); return; }
        if (state == MEAS) return;
        stopMeasuring();
        useCap = c;
        Store.putInt(a, "measureSrc", c ? 1 : 0);
        paintSource();
        startSensor();
    }

    private void paintSource() {
        int on = U.INK, offBg = U.PLATE;
        tvSrcPhone.setBackground(U.box(a, useCap ? offBg : on, 18, U.RULE, 1));
        tvSrcPhone.setTextColor(useCap ? U.SUB : Color.WHITE);
        tvSrcCap.setBackground(U.box(a, useCap ? Color.rgb(0x2f, 0x6f, 0xe4) : offBg, 18, U.RULE, 1));
        tvSrcCap.setTextColor(useCap ? Color.WHITE : U.SUB);
        paintCap();
    }

    private void capMenu() {
        if (!useCap) return;
        if (cap != null && cap.state == CapsuleLink.NOPERM) { a.requestPermissions(CapsuleLink.perms(), 41); return; }
        String info = cap == null ? "" : (cap.name.isEmpty() ? "연결된 캡슐 없음" : cap.name) + (cap.battery >= 0 ? "\n배터리 " + cap.battery + "%" : "")
                + (Double.isNaN(cap.fs) ? "" : String.format(Locale.US, "\n샘플 %.2f Hz", cap.fs)) + "\n중력 방향 " + cap.axisName()
                + (cap.packets > 0 ? String.format(Locale.US, "\n받은 묶음 %d · 빠짐 %d", cap.packets, cap.lost) : "");
        new android.app.AlertDialog.Builder(a).setTitle("진동 캡슐").setMessage(info)
                .setPositiveButton("삐 소리", (d, w) -> { if (cap != null) cap.beep(); })
                .setNeutralButton("다른 캡슐 찾기", (d, w) -> { if (cap != null) cap.forget(); })
                .setNegativeButton("닫기", null).show();
    }

    /** {X둘레 각도 ±°, Y둘레 ±°, 100mm당 높이차 ±µm, 위아래 흔들림 대비 %} */
    static double[] rockMetrics(double[] g, double f, double vRms) {
        double w = 2 * Math.PI * f;
        double ax = g[0] * Math.sqrt(2) / w, ay = g[1] * Math.sqrt(2) / w;          // 각도 진폭 (°)
        double dh = Math.toRadians(Math.max(ax, ay)) * 100.0 * 1000.0;               // 100mm 거리에서 높이차 (µm)
        double zAmp = vRms * Math.sqrt(2) / w * 1000.0;                              // 위아래 진폭 (µm)
        return new double[]{ax, ay, dh, zAmp > 0 ? dh / zAmp * 100 : Double.NaN};
    }

    private String rockText(double f, double v) {
        if (cap == null) return "";
        double[] g = cap.rocking();
        if (g == null) return "기울기 흔들림 —";
        if (Double.isNaN(f) || f < 20) return String.format(Locale.US, "회전 %.2f · %.2f °/s", g[0], g[1]);
        double[] m = rockMetrics(g, f, v);
        return String.format(Locale.US, "기울기 흔들림 ±%.3f° · ±%.3f°  (100mm당 ±%.0fµm, 위아래의 %.0f%%%s)",
                m[0], m[1], m[2], m[3], m[3] > 30 ? " ⚠" : "");
    }

    /** 권한 허용 후 다시 시작 */
    void onPermResult() { if (useCap && listening && cap != null) cap.start(); }

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
        tvRock.setText(useCap ? rockText(vib ? r.freq : Double.NaN, vib ? r.vRms : Double.NaN) : "");
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
        if (useCap && state == LIFT) { setStatus("측정 완료 · 다시 재려면 누르세요", U.OK); tvGuide.setText("다른 호기로 옮기려면 캡슐을 떼서 붙이세요"); return; }
        tvGuide.setText(useCap ? (cap != null && cap.state == CapsuleLink.ON ? "캡슐을 설비 중앙에 자석으로 붙이세요 · 진동이 잡히면 자동으로 잽니다" : "캡슐 전원을 켜면 자동으로 연결됩니다")
                : "폰 뒷면을 설비 중앙에 대고 손가락으로 누르세요");
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
        tvGuide.setText(useCap ? "측정 중 · 캡슐을 건드리지 마세요" : "그대로 누르고 계세요");
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
        setStatus(useCap ? "측정 취소 · 진동이 멈췄습니다" : "측정 취소 · 폰을 뗐습니다", U.SUB);
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
        if (useCap) r.memo = r.memo.isEmpty() ? "[캡슐]" : "[캡슐] " + r.memo;
        return r;
    }

    private void complete(Pick p) {
        Store.Record r = recordFrom(p.best);
        double selSec = (p.best.get(RUN - 1).end - p.best.get(0).end) / 1e9 + WINDOW_SEC;
        String sub = String.format(Locale.US, "%.2f Hz · 안정 구간 %.1f초 · 변동 %.1f%%", r.freq, selSec, p.bestCv * 100);
        state = LIFT; offCount = 0;
        lockLayer.setVisibility(View.GONE);
        prog.setProgress(1000);
        tvGuide.setText(useCap ? "완료 · 다시 재려면 위 상태 칸을 누르세요" : "폰을 떼고 다음 호기를 고르세요");

        if (adjUnit == unit) {                       // 주파수 조정 모드
            adjVals.add(r);
            exportRaw(r, "완료 · 조정 " + adjVals.size() + "회째", p);
            adjustStep(sub);
            return;
        }
        long now = System.currentTimeMillis();
        if (pendKey != -1 && pendUnit == unit && now - pendAt <= PEND_MS) {   // 경계 근처 → 2회 평균
            exportRaw(r, "완료 · 2회째 (평균)", p);
            averageWithPending(r);
            return;
        }
        pendKey = -1; pendFirst = null;
        Store.addRecord(a, r);
        etMemo.setText("");
        exportRaw(r, "완료", p);
        setStatus("측정 완료 · 저장됨", U.OK);
        String note = null;
        Store.Spec s = Store.spec(a, r.spec);
        if (s != null && !Double.isNaN(s.target)) {
            double lo = s.target - s.tol, hi = s.target + s.tol;
            if (Math.abs(r.vRms - lo) <= EDGE || Math.abs(r.vRms - hi) <= EDGE) {
                note = "경계 근처예요 · 폰을 뗐다가 다시 대면 2회 평균으로 저장합니다 (3분 안)";
                pendKey = r.time; pendUnit = unit; pendAt = now; pendFirst = copy(r);
            }
        }
        final long key = r.time; final int ku = r.unit;
        showResult("✓ 측정 완료 · 저장됨", U.OK, r, sub, note, () -> {
            removeRecord(key, ku);
            pendKey = -1; pendFirst = null;
            Toast.makeText(a, "방금 기록을 지웠습니다", Toast.LENGTH_SHORT).show();
        }, note == null);
    }

    // ---------------- 주파수 조정 모드 ----------------
    private void startAdjust() {
        if (adjUnit != unit) {
            Store.Record last = null;
            for (Store.Record x : Store.records(a)) if (x.unit == unit && (last == null || x.time > last.time)) last = x;
            adjPrevHz = last == null ? Double.NaN : last.hz();
            adjPrevV = last == null ? Double.NaN : last.vRms;
        }
        adjUnit = unit;
        adjVals.clear();
        pendKey = -1; pendFirst = null;
        updateMode();
    }

    private void stopAdjust() {
        adjUnit = -1;
        adjVals.clear();
        loadedHz = Store.machine(a, unit).setHz;
        updateMode();
    }

    private void updateMode() {
        if (tvMode == null) return;
        if (adjUnit == unit) {
            tvMode.setVisibility(View.VISIBLE);
            tvMode.setText(String.format(Locale.US, "주파수 조정 모드 · 3회 평균 (%d/%d)  ·  눌러서 끄기",
                    Math.min(adjVals.size(), ADJ_N), ADJ_N));
        } else tvMode.setVisibility(View.GONE);
    }

    private static double medianOf(List<Store.Record> l) {
        double[] v = new double[l.size()];
        for (int i = 0; i < v.length; i++) v[i] = l.get(i).vRms;
        return median(v);
    }

    /** 중앙값에서 8% 안의 값만 */
    private List<Store.Record> inliers(List<Store.Record> l) {
        double m = medianOf(l);
        List<Store.Record> k = new ArrayList<>();
        for (Store.Record x : l) if (Math.abs(x.vRms - m) <= ADJ_OUT * m) k.add(x);
        return k;
    }

    private void adjustStep(String sub) {
        int n = adjVals.size();
        Store.Record r = adjVals.get(n - 1);
        List<Store.Record> keep = inliers(adjVals);
        boolean done = (n >= ADJ_N && keep.size() >= ADJ_N) || n >= ADJ_MAX;
        updateMode();
        if (!done) {
            setStatus(String.format(Locale.US, "조정 측정 %d/%d 완료", Math.min(n, ADJ_N), ADJ_N), U.OK);
            String note;
            if (n >= ADJ_N) {
                Store.Record out = null; double far = -1, m = medianOf(adjVals);
                for (Store.Record x : adjVals) if (Math.abs(x.vRms - m) > far) { far = Math.abs(x.vRms - m); out = x; }
                note = String.format(Locale.US, "값 하나가 튀어요 (%.1f) · 폰을 뗐다가 한 번 더 재 주세요", out.vRms);
            } else note = "폰을 뗐다가 다시 대 주세요 · 다음 측정 " + (n + 1) + "/" + ADJ_N;
            showResult(String.format(Locale.US, "조정 측정 %d/%d", Math.min(n, ADJ_N), ADJ_N), U.OK, r, sub, note, () -> {
                adjVals.remove(r);
                updateMode();
                Toast.makeText(a, "방금 측정을 뺐습니다", Toast.LENGTH_SHORT).show();
            }, true);
            return;
        }
        List<Store.Record> use = keep.size() >= 2 ? keep : adjVals;
        Store.Record avg = average(use);
        double sd = 0;
        for (Store.Record x : use) sd += (x.vRms - avg.vRms) * (x.vRms - avg.vRms);
        sd = use.size() > 1 ? Math.sqrt(sd / (use.size() - 1)) : 0;
        StringBuilder vals = new StringBuilder();
        for (Store.Record x : use) vals.append(vals.length() > 0 ? ", " : "").append(U.f1(x.vRms));
        avg.memo = join(etMemo.getText().toString().trim(), String.format(Locale.US, "조정 %d회 평균 ±%.1f", use.size(), sd)
                + (keep.size() < ADJ_N ? " · 편차 큼" : ""));
        Store.addRecord(a, avg);
        etMemo.setText("");
        adjUnit = -1;
        adjVals.clear();
        loadedHz = avg.setHz;
        updateMode();
        setStatus("조정 측정 완료 · 평균 저장됨", U.OK);
        String cmp = null;
        if (!Double.isNaN(adjPrevV) && adjPrevV > 0) {
            cmp = String.format(Locale.US, "이전 %s Hz · %.1f  →  지금 %s Hz · %.1f  (%+.0f%%)",
                    Double.isNaN(adjPrevHz) ? "?" : U.f1(adjPrevHz), adjPrevV,
                    Double.isNaN(avg.setHz) ? "?" : U.f1(avg.setHz), avg.vRms, 100 * (avg.vRms - adjPrevV) / adjPrevV);
        }
        String sub2 = String.format(Locale.US, "±%.1f · %d회 (%s) · %.2f Hz", sd, use.size(), vals, avg.freq);
        String note = keep.size() < ADJ_N ? "측정마다 차이가 커요 · 고정 상태를 확인해 주세요" : null;
        final long key = avg.time; final int ku = avg.unit;
        showResult("✓ 주파수 조정 측정 완료 · 저장됨", U.OK, avg, sub2, join2(cmp, note), () -> {
            removeRecord(key, ku);
            Toast.makeText(a, "방금 기록을 지웠습니다", Toast.LENGTH_SHORT).show();
        }, false);
    }

    // ---------------- 경계 근처 2회 평균 ----------------
    private void averageWithPending(Store.Record second) {
        final Store.Record first = pendFirst;
        final long key = pendKey; final int ku = pendUnit;
        pendKey = -1; pendFirst = null;
        List<Store.Record> two = new ArrayList<>();
        two.add(first); two.add(second);
        Store.Record avg = average(two);
        List<Store.Record> l = Store.records(a);
        for (Store.Record x : l) if (x.time == key && x.unit == ku) {
            copyValues(avg, x);
            x.memo = join(first.memo, String.format(Locale.US, "2회 평균 (%s, %s)", U.f1(first.vRms), U.f1(second.vRms)));
            break;
        }
        Store.saveRecords(a, l);
        etMemo.setText("");
        setStatus("2회 평균으로 저장됨", U.OK);
        String sub = String.format(Locale.US, "%s · %s 평균 · %.2f Hz", U.f1(first.vRms), U.f1(second.vRms), avg.freq);
        showResult("✓ 2회 평균으로 저장됨", U.OK, avg, sub, null, () -> {
            List<Store.Record> l2 = Store.records(a);
            for (Store.Record x : l2) if (x.time == key && x.unit == ku) { copyValues(first, x); x.memo = first.memo; break; }
            Store.saveRecords(a, l2);
            Toast.makeText(a, "첫 번째 값으로 되돌렸습니다", Toast.LENGTH_SHORT).show();
        }, true);
    }

    // ---------------- 기록 도우미 ----------------
    private Store.Record average(List<Store.Record> l) {
        Store.Record r = new Store.Record();
        Store.Record f = l.get(l.size() - 1);
        r.time = System.currentTimeMillis();
        r.unit = f.unit; r.spec = f.spec; r.setHz = f.setHz; r.memo = f.memo;
        int k = l.size();
        for (Store.Record x : l) {
            r.vRms += x.vRms / k; r.freq += x.freq / k; r.zRms += x.zRms / k; r.vPeak += x.vPeak / k;
            r.pctX += x.pctX / k; r.pctY += x.pctY / k; r.fs += x.fs / k;
        }
        return r;
    }

    private static void copyValues(Store.Record s, Store.Record d) {
        d.vRms = s.vRms; d.freq = s.freq; d.zRms = s.zRms; d.vPeak = s.vPeak;
        d.pctX = s.pctX; d.pctY = s.pctY; d.fs = s.fs;
    }

    private static Store.Record copy(Store.Record s) {
        Store.Record d = new Store.Record();
        d.time = s.time; d.unit = s.unit; d.spec = s.spec; d.setHz = s.setHz; d.memo = s.memo;
        copyValues(s, d);
        return d;
    }

    private void removeRecord(long key, int unit) {
        List<Store.Record> l = Store.records(a);
        l.removeIf(x -> x.time == key && x.unit == unit);
        Store.saveRecords(a, l);
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a + " · " + b;
    }

    private static String join2(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "\n" + b;
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
        if (useCap) fix = fix.replace("폰 뒷면을 설비 중앙에 대고", "캡슐이 설비 중앙에 단단히 붙었는지 확인하고").replace("폰을", "캡슐을").replace("손가락으로 꾹 누르고", "").replace("누르는 힘", "자석 고정");
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
        ex.add(new String[]{"측정 방식", useCap ? "자동 · 캡슐" : "자동 · 폰"});
        if (useCap && cap != null) {
            ex.add(new String[]{"캡슐", cap.name});
            ex.add(new String[]{"캡슐 샘플 Hz", Double.isNaN(cap.fs) ? "" : String.format(Locale.US, "%.3f", cap.fs)});
            ex.add(new String[]{"캡슐 배터리 %", cap.battery >= 0 ? String.valueOf(cap.battery) : ""});
            ex.add(new String[]{"캡슐 중력 축", cap.axisName()});
            double[] rk = cap.rocking();
            if (rk != null) {
                ex.add(new String[]{"회전 흔들림 °/s (X둘레·Y둘레·비틀림)", String.format(Locale.US, "%.3f / %.3f / %.3f", rk[0], rk[1], rk[2])});
                double[] m = rockMetrics(rk, r.freq, r.vRms);
                ex.add(new String[]{"기울기 흔들림 ° (X·Y)", String.format(Locale.US, "±%.4f / ±%.4f", m[0], m[1])});
                ex.add(new String[]{"100mm당 높이차 µm", String.format(Locale.US, "±%.1f", m[2])});
                ex.add(new String[]{"위아래 대비 기울기 %", String.format(Locale.US, "%.0f", m[3])});
            }
        }
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
        RawExport.saveAsync(a, r, verdict, ex.toArray(new String[0][]), t, x, y, z, n, wall0, useCap ? null : acc);
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
        if (state == IDLE || (useCap && state == LIFT)) showIdle();
    }

    private void showResult(String title, int color, Store.Record r, String sub, String note, Runnable undo, boolean autoCloseOn) {
        LinearLayout c = modalCard();
        c.addView(U.tv(a, title, 19, color, U.BOLD));
        c.addView(U.tv(a, r.unit + "호기 · " + (r.spec.isEmpty() ? "스펙 없음" : r.spec)
                        + (Double.isNaN(r.setHz) ? "" : " · 설정 " + U.f1(r.setHz) + " Hz"), 14.5f, U.SUB, U.BOLD),
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
        c.addView(U.tv(a, sub, 12.5f, U.SUB, U.MONO), U.margins(U.match(), a, 0, 10, 0, 0));
        if (note != null) {
            int bg = Color.rgb(0xf6, 0xe6, 0xc4);
            TextView nt = U.tv(a, note, 14, Color.rgb(0x6a, 0x48, 0x08), U.BOLD);
            nt.setBackground(U.box(a, bg, 8, bg, 0));
            nt.setPadding(U.dp(a, 12), U.dp(a, 9), U.dp(a, 12), U.dp(a, 9));
            c.addView(nt, U.margins(U.match(), a, 0, 10, 0, 0));
        }
        LinearLayout br = U.row(a);
        TextView ub = U.button(a, "되돌리기", 1, v -> { undo.run(); closeModal(); });
        br.addView(ub, new LinearLayout.LayoutParams(0, U.dp(a, 52), 1));
        TextView ok = U.button(a, "확인", 0, v -> closeModal());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, U.dp(a, 52), 1.4f);
        ol.leftMargin = U.dp(a, 8);
        br.addView(ok, ol);
        c.addView(br, U.margins(U.match(), a, 0, 14, 0, 0));
        if (autoCloseOn) {
            TextView nb = U.tv(a, "5초 뒤 자동으로 닫힘", 11.5f, U.SUB, null);
            nb.setGravity(Gravity.CENTER);
            c.addView(nb, U.margins(U.match(), a, 0, 8, 0, 0));
            autoClose = this::closeModal;
            h.postDelayed(autoClose, DONE_AUTOCLOSE_MS);
        }
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
