package com.hg.vibmeter;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 진동 속도 측정 앱 (작성 H.G 박동원)
 * - 폰을 십자 가운데에 고정하고 [측정 시작]
 * - 최근 3초 데이터로 0.5초마다 진동 속도 RMS(mm/s), 실측 주파수, 고정 상태(X·Y/Z)를 표시
 * - 값이 안정되면 호기·스펙을 적고 [기록 저장] → [엑셀로 내보내기]
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final double WINDOW_SEC = 3.0;
    private static final int CAP = 8192;
    private static final double FIX_LIMIT = 20.0;     // X·Y 가 Z 의 20% 이하이면 고정 양호
    private static final double STABLE_LIMIT = 0.05;  // 최근 값들이 ±5% 안이면 안정
    private static final double MIN_ZRMS = 3.0;       // 이보다 약하면 설비 진동이 아님 (실측 최저 약 11 m/s²)
    private static final double MIN_PROM = 8.0;       // FFT 봉우리가 주변 평균의 8배 이상이어야 뚜렷한 진동
    private static final String HEADER =
            "일시,호기,스펙,설정주파수(Hz),실측주파수(Hz),진동속도RMS(mm/s),피크(cm/s),Z RMS(m/s2),X/Z(%),Y/Z(%),샘플속도(Hz),메모\n";

    // 센서 데이터 링 버퍼
    private final long[] bt = new long[CAP];
    private final float[] bx = new float[CAP], by = new float[CAP], bz = new float[CAP];
    private int head = 0, count = 0;
    private final Object lock = new Object();

    private SensorManager sm;
    private Sensor acc;
    private boolean running = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Double> recent = new ArrayDeque<>();
    private Analyzer.Result last;

    // 화면
    private TextView tvVel, tvStatus, tvFreq, tvZ, tvPeak, tvFs, tvFix, tvRecords;
    private Button btnRun;
    private EditText etUnit, etSpec, etSet, etMemo;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        buildUi();
        refreshRecords();
    }

    // ---------------- 화면 구성 ----------------
    private TextView text(LinearLayout p, String t, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        p.addView(v);
        return v;
    }

    private EditText field(LinearLayout p, String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        p.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(LinearLayout p, String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(8);
        p.addView(b, lp);
        return b;
    }

    private void buildUi() {
        int fg = Color.rgb(230, 236, 233), mute = Color.rgb(154, 167, 161), acc = Color.rgb(92, 198, 158);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(18, 23, 21));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(28));
        sv.addView(c);

        text(c, "진동 속도 측정 · H.G", 14, mute, false);

        tvVel = text(c, "--", 72, fg, true);
        tvVel.setGravity(Gravity.CENTER);
        TextView unit = text(c, "진동 속도 RMS (mm/s)", 15, mute, false);
        unit.setGravity(Gravity.CENTER);

        tvStatus = text(c, "측정 시작을 누르세요", 18, mute, true);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, dp(10), 0, dp(10));

        tvFreq = text(c, "실측 주파수  -- Hz", 17, fg, false);
        tvZ = text(c, "Z RMS 가속도  -- m/s²", 15, mute, false);
        tvPeak = text(c, "참고 피크  -- cm/s", 15, mute, false);
        tvFix = text(c, "고정 상태  X/Z --%  Y/Z --%", 15, mute, false);
        tvFs = text(c, "샘플 속도  -- Hz", 13, mute, false);

        btnRun = button(c, "측정 시작", v -> toggle());

        TextView h = text(c, "기록", 16, acc, true);
        h.setPadding(0, dp(22), 0, dp(4));
        etUnit = field(c, "호기 (예: 8)", InputType.TYPE_CLASS_NUMBER);
        etSpec = field(c, "스펙 (예: 90-30 R)", InputType.TYPE_CLASS_TEXT);
        etSet = field(c, "설정 주파수 Hz (예: 69.4)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etMemo = field(c, "메모 (예: 핀 전량 투입)", InputType.TYPE_CLASS_TEXT);
        button(c, "기록 저장", v -> save());
        button(c, "엑셀로 내보내기 (CSV)", v -> export());

        TextView h2 = text(c, "최근 기록", 16, acc, true);
        h2.setPadding(0, dp(22), 0, dp(4));
        tvRecords = text(c, "", 13, fg, false);
        tvRecords.setTypeface(Typeface.MONOSPACE);

        Button clear = button(c, "기록 전체 삭제 (길게 누르기)", null);
        clear.setOnClickListener(v -> Toast.makeText(this, "지우려면 길게 누르세요", Toast.LENGTH_SHORT).show());
        clear.setOnLongClickListener(v -> { clearRecords(); return true; });

        setContentView(sv);
    }

    // ---------------- 측정 ----------------
    private void toggle() {
        if (running) stop(); else start();
    }

    private void start() {
        if (acc == null) { Toast.makeText(this, "가속도 센서를 찾지 못했습니다", Toast.LENGTH_LONG).show(); return; }
        synchronized (lock) { head = 0; count = 0; }
        recent.clear();
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
        running = true;
        btnRun.setText("측정 멈춤");
        tvStatus.setText("측정 중… 값이 안정될 때까지 기다리세요");
        ui.postDelayed(tick, 500);
    }

    private void stop() {
        sm.unregisterListener(this);
        running = false;
        ui.removeCallbacks(tick);
        btnRun.setText("측정 시작");
        if (last != null && last.valid) tvStatus.setText("멈춤 (마지막 값 유지)");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (running) stop();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        synchronized (lock) {
            bt[head] = e.timestamp;
            bx[head] = e.values[0];
            by[head] = e.values[1];
            bz[head] = e.values[2];
            head = (head + 1) % CAP;
            if (count < CAP) count++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            analyzeNow();
            if (running) ui.postDelayed(this, 500);
        }
    };

    private void analyzeNow() {
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
        last = r;
        if (!r.valid) return;

        boolean vib = r.rmsZ >= MIN_ZRMS && r.prominence >= MIN_PROM;
        if (!vib) {
            last = null;  // 저장 막기
            recent.clear();
            tvVel.setText("--");
            tvFreq.setText("실측 주파수  -- Hz");
            tvZ.setText(String.format(Locale.US, "Z RMS 가속도  %.2f m/s²", r.rmsZ));
            tvPeak.setText("참고 피크  -- cm/s");
            tvFix.setText("고정 상태  -- (진동이 있어야 판단)");
            tvFix.setTextColor(Color.rgb(154, 167, 161));
            tvFs.setText(String.format(Locale.US, "샘플 속도  %.0f Hz (최근 %.1f초)", r.fs, have));
            tvStatus.setText("설비 진동이 감지되지 않습니다\n진동기 위 십자 가운데에 고정하세요");
            tvStatus.setTextColor(Color.rgb(154, 167, 161));
            return;
        }

        tvVel.setText(String.format(Locale.US, "%.1f", r.vRms));
        tvFreq.setText(String.format(Locale.US, "실측 주파수  %.2f Hz", r.freq));
        tvZ.setText(String.format(Locale.US, "Z RMS 가속도  %.2f m/s²", r.rmsZ));
        tvPeak.setText(String.format(Locale.US, "참고 피크  %.2f cm/s", r.vPeak));
        boolean fixOk = r.pctX <= FIX_LIMIT && r.pctY <= FIX_LIMIT;
        tvFix.setText(String.format(Locale.US, "고정 상태  X/Z %.0f%%  Y/Z %.0f%%  %s", r.pctX, r.pctY, fixOk ? "양호" : "불량"));
        tvFix.setTextColor(fixOk ? Color.rgb(154, 167, 161) : Color.rgb(240, 110, 90));
        tvFs.setText(String.format(Locale.US, "샘플 속도  %.0f Hz (최근 %.1f초)", r.fs, have));

        recent.addLast(r.vRms);
        while (recent.size() > 6) recent.removeFirst();
        boolean stable = false;
        if (recent.size() >= 6 && have >= WINDOW_SEC * 0.9) {
            double mn = Double.MAX_VALUE, mx = 0, sum = 0;
            for (double v : recent) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; }
            double mean = sum / recent.size();
            stable = (mx - mn) / 2 <= STABLE_LIMIT * mean;
        }
        if (!fixOk) {
            tvStatus.setText("폰 고정 불량 — 다시 고정하세요");
            tvStatus.setTextColor(Color.rgb(240, 110, 90));
        } else if (stable) {
            tvStatus.setText("안정 — 기록해도 됩니다");
            tvStatus.setTextColor(Color.rgb(92, 198, 158));
        } else {
            tvStatus.setText("측정 중… 값이 흔들리고 있습니다");
            tvStatus.setTextColor(Color.rgb(230, 190, 90));
        }
        if (r.fs < 2.5 * r.freq) {
            tvFs.setTextColor(Color.rgb(240, 110, 90));
        }
    }

    // ---------------- 기록 ----------------
    private File recordFile() {
        return new File(getFilesDir(), "records.csv");
    }

    private static String csv(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.contains(",") || s.contains("\"")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private void save() {
        Analyzer.Result r = last;
        if (r == null || !r.valid) { Toast.makeText(this, "먼저 측정하세요", Toast.LENGTH_SHORT).show(); return; }
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        String line = String.format(Locale.US, "%s,%s,%s,%s,%.2f,%.1f,%.2f,%.2f,%.0f,%.0f,%.0f,%s\n",
                when, csv(etUnit.getText().toString()), csv(etSpec.getText().toString()),
                csv(etSet.getText().toString()), r.freq, r.vRms, r.vPeak, r.rmsZ, r.pctX, r.pctY, r.fs,
                csv(etMemo.getText().toString()));
        try {
            File f = recordFile();
            boolean isNew = !f.exists();
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                if (isNew) o.write(HEADER.getBytes(StandardCharsets.UTF_8));
                o.write(line.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, String.format(Locale.US, "저장: %.1f mm/s", r.vRms), Toast.LENGTH_SHORT).show();
            refreshRecords();
        } catch (Exception e) {
            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshRecords() {
        File f = recordFile();
        if (!f.exists()) { tvRecords.setText("아직 기록이 없습니다"); return; }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 1 && out.size() < 20; i--) {
                String[] p = lines.get(i).split(",", -1);
                if (p.length < 7) continue;
                String time = p[0].length() >= 16 ? p[0].substring(5, 16) : p[0];
                out.add(String.format(Locale.KOREA, "%s  %s호기 %s  %sHz → %s mm/s", time, p[1], p[2], p[4], p[5]));
            }
            tvRecords.setText(out.isEmpty() ? "아직 기록이 없습니다" : String.join("\n", out));
        } catch (Exception e) {
            tvRecords.setText("기록을 읽지 못했습니다");
        }
    }

    private void clearRecords() {
        File f = recordFile();
        if (f.exists() && f.delete()) Toast.makeText(this, "기록을 모두 지웠습니다", Toast.LENGTH_SHORT).show();
        refreshRecords();
    }

    private void export() {
        File f = recordFile();
        if (!f.exists()) { Toast.makeText(this, "내보낼 기록이 없습니다", Toast.LENGTH_SHORT).show(); return; }
        String name = "진동속도_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(new Date()) + ".csv";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VibMeter");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("파일을 만들 수 없습니다");
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // 엑셀 한글 깨짐 방지
                o.write(Files.readAllBytes(f.toPath()));
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "다운로드/VibMeter 에 저장됨 · 보내기"));
        } catch (Exception e) {
            Toast.makeText(this, "내보내기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
