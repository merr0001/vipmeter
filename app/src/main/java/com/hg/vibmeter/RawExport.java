package com.hg.vibmeter;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.hardware.Sensor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 측정 원본을 Phyphox 내보내기와 같은 형식의 zip으로 저장 (다운로드/진동지기) */
final class RawExport {
    private RawExport() {}

    static final String FOLDER = "진동지기";

    static void saveAsync(Activity a, Store.Record r, boolean stable,
                          long[] t, float[] x, float[] y, float[] z, int n, long wall0, Sensor acc) {
        if (n < 2) {
            Toast.makeText(a, "원본 데이터가 없어 zip은 만들지 않았습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                String name = save(a, r, stable, t, x, y, z, n, wall0, acc);
                msg = "원본 저장됨 · 다운로드/" + FOLDER + "/" + name;
            } catch (Exception e) {
                msg = "원본 저장 실패: " + e.getMessage();
            }
            final String m = msg;
            a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private static String save(Activity a, Store.Record r, boolean stable,
                               long[] t, float[] x, float[] y, float[] z, int n, long wall0, Sensor acc) throws Exception {
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(r.time));
        String name = "진동지기 " + r.unit + "호기 " + stamp + ".zip";

        ContentResolver cr = a.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new Exception("파일을 만들 수 없음");

        long t0 = t[0];
        double dur = (t[n - 1] - t0) / 1e9;
        // 첫 샘플 시각을 실험 시작(0초)으로 맞춤
        long startWall = wall0;

        try (OutputStream os = cr.openOutputStream(uri); ZipOutputStream zip = new ZipOutputStream(os)) {
            // Raw data.csv (Phyphox "가속도 (g 포함)"과 같은 머리글)
            zip.putNextEntry(new ZipEntry("Raw data.csv"));
            StringBuilder sb = new StringBuilder(n * 64);
            sb.append("\"Time (s)\",\"Acceleration x (m/s²)\",\"Acceleration y (m/s²)\",\"Acceleration z (m/s²)\"\n");
            for (int i = 0; i < n; i++) {
                sb.append(e9((t[i] - t0) / 1e9)).append(',')
                  .append(e9(x[i])).append(',')
                  .append(e9(y[i])).append(',')
                  .append(e9(z[i])).append('\n');
                if (sb.length() > 1 << 20) { zip.write(sb.toString().getBytes(StandardCharsets.UTF_8)); sb.setLength(0); }
            }
            zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // meta/time.csv
            zip.putNextEntry(new ZipEntry("meta/time.csv"));
            SimpleDateFormat tf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'XXX", Locale.US);
            String tm = "\"event\",\"experiment time\",\"system time\",\"system time text\"\n"
                    + "\"START\"," + e9(0) + "," + String.format(Locale.US, "%.3f", startWall / 1000.0) + ",\"" + tf.format(new Date(startWall)) + "\"\n"
                    + "\"PAUSE\"," + e9(dur) + "," + String.format(Locale.US, "%.3f", r.time / 1000.0) + ",\"" + tf.format(new Date(r.time)) + "\"\n";
            zip.write(tm.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // meta/device.csv
            zip.putNextEntry(new ZipEntry("meta/device.csv"));
            StringBuilder d = new StringBuilder("\"property\",\"value\"\n");
            kv(d, "app", "진동지기 " + ver(a));
            kv(d, "deviceModel", Build.MODEL);
            kv(d, "deviceBrand", Build.BRAND);
            kv(d, "deviceRelease", Build.VERSION.RELEASE);
            if (acc != null) {
                kv(d, "accelerometer Name", acc.getName());
                kv(d, "accelerometer Vendor", acc.getVendor());
                kv(d, "accelerometer Range", String.valueOf(acc.getMaximumRange()));
                kv(d, "accelerometer Resolution", String.valueOf(acc.getResolution()));
                kv(d, "accelerometer MinDelay", String.valueOf(acc.getMinDelay()));
            }
            zip.write(d.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // 진동지기 결과.csv : 앱이 저장한 값
            zip.putNextEntry(new ZipEntry("진동지기 결과.csv"));
            StringBuilder q = new StringBuilder("\"property\",\"value\"\n");
            kv(q, "호기", String.valueOf(r.unit));
            kv(q, "스펙", r.spec);
            kv(q, "설정 주파수 Hz", Double.isNaN(r.setHz) ? "" : String.format(Locale.US, "%.2f", r.setHz));
            kv(q, "진동 속도 RMS mm/s", String.format(Locale.US, "%.3f", r.vRms));
            kv(q, "측정 주파수 Hz", String.format(Locale.US, "%.3f", r.freq));
            kv(q, "Z RMS m/s²", String.format(Locale.US, "%.4f", r.zRms));
            kv(q, "피크 cm/s", String.format(Locale.US, "%.3f", r.vPeak));
            kv(q, "X/Z %", String.format(Locale.US, "%.1f", r.pctX));
            kv(q, "Y/Z %", String.format(Locale.US, "%.1f", r.pctY));
            kv(q, "샘플 Hz", String.format(Locale.US, "%.1f", r.fs));
            kv(q, "안정 판정", stable ? "안정" : "불안정");
            kv(q, "원본 길이 s", String.format(Locale.US, "%.2f", dur));
            kv(q, "원본 샘플 수", String.valueOf(n));
            kv(q, "기록 시각", tf.format(new Date(r.time)));
            kv(q, "메모", r.memo);
            zip.write(q.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return name;
    }

    private static String ver(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private static void kv(StringBuilder b, String k, String v) {
        b.append('"').append(k).append("\",\"").append(v == null ? "" : v.replace("\"", "'")).append("\"\n");
    }

    /** Phyphox 형식 숫자: 1.810522457E1 */
    private static String e9(double v) {
        String s = String.format(Locale.US, "%.9E", v);
        int k = s.indexOf('E');
        String mant = s.substring(0, k);
        int exp = Integer.parseInt(s.substring(k + 1));
        return mant + "E" + exp;
    }
}
