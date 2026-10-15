package com.hg.vibmeter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 앱 데이터 저장소. 호기 상태는 SharedPreferences, 기록·결과는 JSON 파일. */
public final class Store {

    public static final int UNITS = 17;
    private static final String PREF = "vib";

    // ---------------- 모델 ----------------
    public static final class Spec {
        public String name;
        public double target;
        public double tol = 3;
        public Spec(String n, double t) { name = n; target = t; }
        /** "48-20" → 20 (전장 기준값) */
        public double nominalLength() {
            try {
                String s = name.split("-")[1].replaceAll("[^0-9.].*$", "");
                return Double.parseDouble(s.trim());
            } catch (Exception e) { return Double.NaN; }
        }
    }

    public static final class Record {
        public long time;
        public int unit;
        public String spec = "";
        public double setHz = Double.NaN;
        public double freq, vRms, vPeak, zRms, pctX, pctY, fs;
        public String memo = "";
        public double hz() { return Double.isNaN(setHz) ? freq : setHz; }
    }

    public static final class Result {
        public long time;
        public int unit;
        public String spec = "";
        public double setHz = Double.NaN, vRms = Double.NaN;
        public int rounds, minutes;
        public double angle = Double.NaN, length = Double.NaN;
        public String rShape = "";
        public String memo = "";
    }

    public static final class Machine {
        public int unit;
        public String spec = "";
        public double setHz = Double.NaN;
        // 타이머
        public long endAt;          // 0 = 멈춤
        public int durMin;
        public boolean round;       // 회차 타이머인지
        public boolean finished;    // 끝났는데 아직 확인 안 함
        public String finishMsg = "";
        // 가공 계획
        public boolean planActive;
        public int planA = 3, planB = 2, roundMin = 20;
        public int doneRounds;
        public boolean swapped;
        public long planStart;
        public List<String> log = new ArrayList<>();

        public boolean running(long now) { return endAt > 0 && endAt > now; }
        public int planTotal() { return planA + planB; }
        public boolean swapDue() { return planActive && !swapped && planB > 0 && doneRounds >= planA; }
        public boolean planDone() { return planActive && doneRounds >= planTotal(); }
    }

    // ---------------- 호기 ----------------
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public static synchronized Machine machine(Context c, int unit) {
        Machine m = new Machine();
        m.unit = unit;
        String s = sp(c).getString("m" + unit, null);
        if (s == null) return m;
        try {
            JSONObject o = new JSONObject(s);
            m.spec = o.optString("spec", "");
            m.setHz = o.has("setHz") ? o.optDouble("setHz", Double.NaN) : Double.NaN;
            m.endAt = o.optLong("endAt", 0);
            m.durMin = o.optInt("durMin", 0);
            m.round = o.optBoolean("round", false);
            m.finished = o.optBoolean("finished", false);
            m.finishMsg = o.optString("finishMsg", "");
            m.planActive = o.optBoolean("planActive", false);
            m.planA = o.optInt("planA", 3);
            m.planB = o.optInt("planB", 2);
            m.roundMin = o.optInt("roundMin", 20);
            m.doneRounds = o.optInt("doneRounds", 0);
            m.swapped = o.optBoolean("swapped", false);
            m.planStart = o.optLong("planStart", 0);
            JSONArray a = o.optJSONArray("log");
            if (a != null) for (int i = 0; i < a.length(); i++) m.log.add(a.optString(i));
        } catch (Exception ignored) {}
        return m;
    }

    public static synchronized void save(Context c, Machine m) {
        try {
            JSONObject o = new JSONObject();
            o.put("spec", m.spec);
            if (!Double.isNaN(m.setHz)) o.put("setHz", m.setHz);
            o.put("endAt", m.endAt);
            o.put("durMin", m.durMin);
            o.put("round", m.round);
            o.put("finished", m.finished);
            o.put("finishMsg", m.finishMsg);
            o.put("planActive", m.planActive);
            o.put("planA", m.planA);
            o.put("planB", m.planB);
            o.put("roundMin", m.roundMin);
            o.put("doneRounds", m.doneRounds);
            o.put("swapped", m.swapped);
            o.put("planStart", m.planStart);
            o.put("log", new JSONArray(m.log));
            sp(c).edit().putString("m" + m.unit, o.toString()).commit();
        } catch (Exception ignored) {}
    }

    public static List<Machine> machines(Context c) {
        List<Machine> l = new ArrayList<>();
        for (int u = 1; u <= UNITS; u++) l.add(machine(c, u));
        return l;
    }

    public static int getInt(Context c, String k, int d) { return sp(c).getInt(k, d); }
    public static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    public static String getStr(Context c, String k, String d) { return sp(c).getString(k, d); }
    public static void putStr(Context c, String k, String v) { sp(c).edit().putString(k, v).apply(); }

    // ---------------- 스펙 ----------------
    public static synchronized List<Spec> specs(Context c) {
        List<Spec> l = new ArrayList<>();
        String s = sp(c).getString("specs", null);
        if (s == null) {
            l.add(new Spec("28-20", 28));
            l.add(new Spec("23-10", 32));
            l.add(new Spec("48-20", 35));
            l.add(new Spec("69-30", 40));
            l.add(new Spec("90-30 R", 50));
            l.add(new Spec("67-30", 56));
            saveSpecs(c, l);
            return l;
        }
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Spec sp = new Spec(o.getString("name"), o.getDouble("target"));
                sp.tol = o.optDouble("tol", 3);
                l.add(sp);
            }
        } catch (Exception ignored) {}
        return l;
    }

    public static synchronized void saveSpecs(Context c, List<Spec> l) {
        try {
            JSONArray a = new JSONArray();
            for (Spec s : l) {
                JSONObject o = new JSONObject();
                o.put("name", s.name); o.put("target", s.target); o.put("tol", s.tol);
                a.put(o);
            }
            sp(c).edit().putString("specs", a.toString()).commit();
        } catch (Exception ignored) {}
    }

    public static Spec spec(Context c, String name) {
        for (Spec s : specs(c)) if (s.name.equals(name)) return s;
        return null;
    }

    // ---------------- 기록 / 결과 ----------------
    private static JSONArray readArr(Context c, String file) {
        try {
            File f = new File(c.getFilesDir(), file);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONArray(); }
    }

    private static void writeArr(Context c, String file, JSONArray a) {
        try {
            Files.write(new File(c.getFilesDir(), file).toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static void putD(JSONObject o, String k, double v) throws Exception { if (!Double.isNaN(v)) o.put(k, v); }

    public static synchronized List<Record> records(Context c) {
        JSONArray a = readArr(c, "records.json");
        List<Record> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Record r = new Record();
            r.time = o.optLong("time");
            r.unit = o.optInt("unit");
            r.spec = o.optString("spec", "");
            r.setHz = o.has("setHz") ? o.optDouble("setHz") : Double.NaN;
            r.freq = o.optDouble("freq", 0);
            r.vRms = o.optDouble("vRms", 0);
            r.vPeak = o.optDouble("vPeak", 0);
            r.zRms = o.optDouble("zRms", 0);
            r.pctX = o.optDouble("pctX", 0);
            r.pctY = o.optDouble("pctY", 0);
            r.fs = o.optDouble("fs", 0);
            r.memo = o.optString("memo", "");
            l.add(r);
        }
        return l;
    }

    public static synchronized void saveRecords(Context c, List<Record> l) {
        JSONArray a = new JSONArray();
        try {
            for (Record r : l) {
                JSONObject o = new JSONObject();
                o.put("time", r.time); o.put("unit", r.unit); o.put("spec", r.spec);
                putD(o, "setHz", r.setHz);
                o.put("freq", r.freq); o.put("vRms", r.vRms); o.put("vPeak", r.vPeak); o.put("zRms", r.zRms);
                o.put("pctX", r.pctX); o.put("pctY", r.pctY); o.put("fs", r.fs); o.put("memo", r.memo);
                a.put(o);
            }
        } catch (Exception ignored) {}
        writeArr(c, "records.json", a);
    }

    public static void addRecord(Context c, Record r) {
        List<Record> l = records(c);
        l.add(r);
        saveRecords(c, l);
    }

    public static synchronized List<Result> results(Context c) {
        JSONArray a = readArr(c, "results.json");
        List<Result> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Result r = new Result();
            r.time = o.optLong("time");
            r.unit = o.optInt("unit");
            r.spec = o.optString("spec", "");
            r.setHz = o.has("setHz") ? o.optDouble("setHz") : Double.NaN;
            r.vRms = o.has("vRms") ? o.optDouble("vRms") : Double.NaN;
            r.rounds = o.optInt("rounds");
            r.minutes = o.optInt("minutes");
            r.angle = o.has("angle") ? o.optDouble("angle") : Double.NaN;
            r.length = o.has("length") ? o.optDouble("length") : Double.NaN;
            r.rShape = o.optString("rShape", "");
            r.memo = o.optString("memo", "");
            l.add(r);
        }
        return l;
    }

    public static synchronized void saveResults(Context c, List<Result> l) {
        JSONArray a = new JSONArray();
        try {
            for (Result r : l) {
                JSONObject o = new JSONObject();
                o.put("time", r.time); o.put("unit", r.unit); o.put("spec", r.spec);
                putD(o, "setHz", r.setHz); putD(o, "vRms", r.vRms);
                o.put("rounds", r.rounds); o.put("minutes", r.minutes);
                putD(o, "angle", r.angle); putD(o, "length", r.length);
                o.put("rShape", r.rShape); o.put("memo", r.memo);
                a.put(o);
            }
        } catch (Exception ignored) {}
        writeArr(c, "results.json", a);
    }

    public static void addResult(Context c, Result r) {
        List<Result> l = results(c);
        l.add(r);
        saveResults(c, l);
    }

    /** 이 호기·스펙의 가장 최근 측정 */
    public static Record lastRecord(List<Record> all, int unit, String spec) {
        Record best = null;
        for (Record r : all)
            if (r.unit == unit && (spec == null || spec.equals(r.spec)) && (best == null || r.time > best.time)) best = r;
        return best;
    }

    // ---------------- 처음 실행 시 실제 측정 기록 넣기 ----------------
    private static long t(int month, int day, int h, int min) {
        Calendar c = Calendar.getInstance();
        c.set(2026, month - 1, day, h, min, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static Record rec(long time, int unit, String spec, double hz, double v, String memo) {
        Record r = new Record();
        r.time = time; r.unit = unit; r.spec = spec; r.setHz = hz; r.freq = hz; r.vRms = v;
        r.zRms = v * hz / Analyzer.K_RMS_MM;
        r.vPeak = Analyzer.K_PEAK_CM * r.zRms / hz;
        r.fs = 500; r.memo = memo;
        return r;
    }

    public static void seedIfFirstRun(Context c) {
        if (sp(c).getBoolean("seeded", false)) return;
        String s = "초기 데이터";
        List<Record> l = new ArrayList<>();
        l.add(rec(t(10, 14, 20, 0), 3, "23-10", 89.5, 31.8, s));
        l.add(rec(t(10, 14, 20, 30), 13, "48-20", 83.0, 33.9, s + " · 각도 44"));
        l.add(rec(t(10, 14, 21, 0), 14, "48-20", 78.3, 36.8, s + " · 각도 42"));
        l.add(rec(t(10, 14, 21, 30), 14, "48-20", 77.5, 50.9, s));
        l.add(rec(t(10, 14, 22, 0), 14, "48-20", 77.8, 40.3, s));
        l.add(rec(t(10, 14, 22, 30), 15, "48-20", 90.8, 35.4, s + " · 각도 46"));
        l.add(rec(t(10, 14, 23, 0), 8, "67-30", 70.9, 55.9, s));
        l.add(rec(t(10, 14, 23, 30), 5, "69-30", 82.3, 40.3, s));
        l.add(rec(t(10, 15, 8, 31), 14, "23-10", 79.5, 31.1, s + " · 설비 이전 시험"));
        l.add(rec(t(10, 15, 9, 8), 3, "28-20", 87.0, 25.5, s + " · 핀 절반 반출"));
        l.add(rec(t(10, 15, 13, 57), 1, "90-30 R", 79.5, 68.6, s));
        l.add(rec(t(10, 15, 14, 12), 8, "90-30 R", 69.8, 40.3, s));
        l.add(rec(t(10, 15, 14, 19), 8, "90-30 R", 69.5, 49.5, s));
        l.add(rec(t(10, 15, 15, 23), 8, "90-30 R", 69.4, 48.8, s));
        l.add(rec(t(10, 15, 15, 25), 1, "90-30 R", 79.8, 50.2, s));
        saveRecords(c, l);

        List<Result> rs = new ArrayList<>();
        Result a = new Result();
        a.time = t(10, 14, 23, 50); a.unit = 14; a.spec = "48-20"; a.setHz = 77.8; a.vRms = 40.3;
        a.angle = 44; a.length = 19.995; a.rShape = "양호"; a.memo = s + " · 42→44° 진행 중 반출";
        rs.add(a);
        Result b = new Result();
        b.time = t(10, 15, 16, 12); b.unit = 8; b.spec = "90-30 R"; b.setHz = 69.4; b.vRms = 48.8;
        b.rounds = 5; b.minutes = 100; b.rShape = "양호"; b.memo = s + " · 3회+좌우 2회";
        rs.add(b);
        Result d = new Result();
        d.time = t(10, 15, 16, 14); d.unit = 1; d.spec = "90-30 R"; d.setHz = 79.8; d.vRms = 50.2;
        d.rounds = 4; d.minutes = 95; d.rShape = "양호"; d.memo = s + " · 3회+좌우 1회+15분";
        rs.add(d);
        saveResults(c, rs);

        Object[][] ms = {{1, "90-30 R", 79.8}, {3, "23-10", 89.5}, {5, "69-30", 82.3}, {8, "90-30 R", 69.4},
                {13, "48-20", 83.0}, {14, "48-20", 77.8}, {15, "48-20", 90.8}};
        for (Object[] o : ms) {
            Machine m = machine(c, (Integer) o[0]);
            m.spec = (String) o[1];
            m.setHz = (Double) o[2];
            save(c, m);
        }
        sp(c).edit().putBoolean("seeded", true).commit();
    }
}
