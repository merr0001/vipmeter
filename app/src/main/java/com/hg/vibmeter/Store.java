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
        public boolean edited;
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
        public boolean edited;
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
        public int planA = 5, roundMin = 20;   // 가공 횟수, 1회 시간
        public int doneRounds;
        public long planStart;
        public long lastEnd;        // 마지막 회차가 끝난 시각 (대기 시작)
        public List<String> log = new ArrayList<>();

        // 일시정지
        public long pausedLeft;     // >0 이면 일시정지 중 (남은 ms)
        public long pauseAt;        // 일시정지 누른 시각
        public long pausedMs;       // 이번 타이머에서 멈춰 있던 합계
        public boolean paused() { return pausedLeft > 0; }
        public boolean running(long now) { return endAt > 0 && endAt > now; }
        public int planTotal() { return planA; }
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
            m.planA = o.optInt("planA", 5);
            m.roundMin = o.optInt("roundMin", 20);
            m.doneRounds = o.optInt("doneRounds", 0);
            m.planStart = o.optLong("planStart", 0);
            m.lastEnd = o.optLong("lastEnd", 0);
            m.pausedLeft = o.optLong("pausedLeft", 0);
            m.pauseAt = o.optLong("pauseAt", 0);
            m.pausedMs = o.optLong("pausedMs", 0);
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
            o.put("roundMin", m.roundMin);
            o.put("doneRounds", m.doneRounds);
            o.put("planStart", m.planStart);
            o.put("lastEnd", m.lastEnd);
            o.put("pausedLeft", m.pausedLeft);
            o.put("pauseAt", m.pauseAt);
            o.put("pausedMs", m.pausedMs);
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
                Spec sp = new Spec(o.getString("name"), o.optDouble("target", Double.NaN));
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
                o.put("name", s.name); if (!Double.isNaN(s.target)) o.put("target", s.target); o.put("tol", s.tol);
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
            r.edited = o.optBoolean("edited", false);
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
                if (r.edited) o.put("edited", true);
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
            r.edited = o.optBoolean("edited", false);
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
                if (r.edited) o.put("edited", true);
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

    // ---------------- 회차 기록 (인수인계 자동 채움용) ----------------
    public static final class Round { public int unit; public String spec = ""; public long start, end; }

    public static synchronized List<Round> rounds(Context c) {
        JSONArray a = readArr(c, "rounds.json");
        List<Round> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Round r = new Round();
            r.unit = o.optInt("unit"); r.spec = o.optString("spec", ""); r.start = o.optLong("start"); r.end = o.optLong("end");
            l.add(r);
        }
        return l;
    }

    public static synchronized void addRound(Context c, int unit, String spec, long start, long end) {
        JSONArray a = readArr(c, "rounds.json");
        try {
            JSONObject o = new JSONObject();
            o.put("unit", unit); o.put("spec", spec == null ? "" : spec); o.put("start", start); o.put("end", end);
            a.put(o);
        } catch (Exception ignored) {}
        writeArr(c, "rounds.json", a);
    }

    // ---------------- 인수인계 ----------------
    public static final class HandRow {
        public String unit = "", spec = "", count = "", state = "", note = "";
        public boolean live;
        /** 가공 횟수 숫자 */
        public int rounds() {
            String s = count.replaceAll("[^0-9].*$", "");
            try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
        }
        public static final String VISUAL = "외관확인";
        public boolean visual() { return VISUAL.equals(count); }
        /** 보낼 때 글자: 3회 · 외관확인 · — */
        public String todo() { return visual() ? VISUAL : rounds() > 0 ? rounds() + "회" : "—"; }
        public String specLabel() { return state.isEmpty() ? spec : spec + " " + state; }
    }

    public static final class Handover {
        public long id;            // 처음 저장한 시각 (고유 번호)
        public String date = "";   // 근무 시작 날짜 yyyy-MM-dd
        public String shift = "야간";
        public long savedAt;
        public String author = "";
        public List<HandRow> rows = new ArrayList<>();
        public String memo = "";
    }

    public static synchronized List<Handover> handovers(Context c) {
        JSONArray a = readArr(c, "handovers.json");
        List<Handover> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Handover h = new Handover();
            h.id = o.optLong("id"); h.date = o.optString("date"); h.shift = o.optString("shift", "야간");
            h.savedAt = o.optLong("savedAt"); h.author = o.optString("author", ""); h.memo = o.optString("memo", "");
            JSONArray rs = o.optJSONArray("rows");
            if (rs != null) for (int k = 0; k < rs.length(); k++) {
                JSONObject ro = rs.optJSONObject(k);
                if (ro == null) continue;
                HandRow r = new HandRow();
                r.unit = ro.optString("unit"); r.spec = ro.optString("spec"); r.count = ro.optString("count");
                r.state = ro.optString("state", ""); r.note = ro.optString("note", ""); r.live = ro.optBoolean("live", false);
                if (r.state.isEmpty() && r.spec.trim().contains(" ")) { String[] sk = Kind.split(r.spec); r.spec = sk[0]; r.state = sk[1]; }
                if (r.count.contains("진행")) r.live = true;
                if (!r.count.isEmpty() && !r.visual()) r.count = String.valueOf(r.rounds());
                h.rows.add(r);
            }
            l.add(h);
        }
        return l;
    }

    public static synchronized void saveHandovers(Context c, List<Handover> l) {
        JSONArray a = new JSONArray();
        try {
            for (Handover h : l) {
                JSONObject o = new JSONObject();
                o.put("id", h.id); o.put("date", h.date); o.put("shift", h.shift);
                o.put("savedAt", h.savedAt); o.put("author", h.author); o.put("memo", h.memo);
                JSONArray rs = new JSONArray();
                for (HandRow r : h.rows) {
                    JSONObject ro = new JSONObject();
                    ro.put("unit", r.unit); ro.put("spec", r.spec); ro.put("count", r.count);
                    ro.put("state", r.state); ro.put("note", r.note); ro.put("live", r.live);
                    rs.put(ro);
                }
                o.put("rows", rs);
                a.put(o);
            }
        } catch (Exception ignored) {}
        writeArr(c, "handovers.json", a);
    }

    /** 같은 id 가 있으면 바꾸고 없으면 추가 */
    public static void putHandover(Context c, Handover h) {
        List<Handover> l = handovers(c);
        boolean done = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id == h.id) { l.set(i, h); done = true; }
        if (!done) l.add(h);
        saveHandovers(c, l);
    }

    public static void deleteHandover(Context c, long id) {
        List<Handover> l = handovers(c);
        l.removeIf(h -> h.id == id);
        saveHandovers(c, l);
    }

    // ---------------- 스펙 이름 바꾸기 (지난 기록 함께) ----------------
    public static int countSpec(Context c, String name) {
        int n = 0;
        for (Record r : records(c)) if (name.equals(r.spec)) n++;
        for (Result r : results(c)) if (name.equals(r.spec)) n++;
        return n;
    }

    public static synchronized void renameSpec(Context c, String old, String nw) {
        if (old == null || old.equals(nw)) return;
        List<Record> rl = records(c);
        for (Record r : rl) if (old.equals(r.spec)) r.spec = nw;
        saveRecords(c, rl);
        List<Result> sl = results(c);
        for (Result r : sl) if (old.equals(r.spec)) r.spec = nw;
        saveResults(c, sl);
        JSONArray ra = readArr(c, "rounds.json");
        for (int i = 0; i < ra.length(); i++) {
            JSONObject o = ra.optJSONObject(i);
            try { if (o != null && old.equals(o.optString("spec"))) o.put("spec", nw); } catch (Exception ignored) {}
        }
        writeArr(c, "rounds.json", ra);
        List<Handover> hl = handovers(c);
        String[] nk = Kind.split(nw);
        for (Handover h : hl) for (HandRow r : h.rows) if (old.equals(r.specLabel().trim())) { r.spec = nk[0]; r.state = nk[1]; }
        saveHandovers(c, hl);
        for (int u = 1; u <= UNITS; u++) {
            Machine m = machine(c, u);
            if (old.equals(m.spec)) { m.spec = nw; save(c, m); }
        }
    }

    /** v2.3 한 번만: 목표 범위를 벗어난(높음·낮음) 측정 기록 지우기 */
    public static void cleanOutOfRangeOnce(Context c) {
        if (sp(c).getBoolean("clean_v23", false)) return;
        List<Record> l = records(c);
        List<Spec> specs = specs(c);
        l.removeIf(r -> {
            Spec s = null;
            for (Spec x : specs) if (x.name.equals(r.spec)) s = x;
            int j = U.judge(s, r.vRms);
            return j == 1 || j == 2;
        });
        saveRecords(c, l);
        sp(c).edit().putBoolean("clean_v23", true).commit();
    }

    /** 처음 실행 시 실제 측정 기록 넣기 */
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
        b.rounds = 5; b.minutes = 100; b.rShape = "양호"; b.memo = s + " · 5회";
        rs.add(b);
        Result d = new Result();
        d.time = t(10, 15, 16, 14); d.unit = 1; d.spec = "90-30 R"; d.setHz = 79.8; d.vRms = 50.2;
        d.rounds = 4; d.minutes = 95; d.rShape = "양호"; d.memo = s + " · 4회 + 15분";
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
