package com.hg.vibmeter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 호기 타이머: 시작/정지/연장, 회차 진행, 완료 알림 예약 */
public final class TimerCenter {

    public static final String CH = "vib_timer_v2";
    public static final String EXTRA_UNIT = "unit";
    public static final String EXTRA_END = "endAt";

    private TimerCenter() {}

    private static PendingIntent pi(Context c, int unit, long endAt) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra(EXTRA_UNIT, unit);
        i.putExtra(EXTRA_END, endAt);
        return PendingIntent.getBroadcast(c, 1000 + unit, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, int unit, long endAt) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = pi(c, unit, endAt);
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, p);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, p);
        }
    }

    private static void unschedule(Context c, int unit) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pi(c, unit, 0));
    }

    /** 단일 타이머 (회차와 무관) */
    public static void startSingle(Context c, int unit, int minutes) {
        Store.Machine m = Store.machine(c, unit);
        m.endAt = System.currentTimeMillis() + minutes * 60_000L;
        m.durMin = minutes;
        clearPause(m);
        m.round = false;
        m.finished = false;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
    }

    /** 가공 계획 시작 (회차 0부터) */
    public static void startPlan(Context c, int unit, int a, int roundMin) {
        Store.Machine m = Store.machine(c, unit);
        m.planActive = true;
        m.planA = a; m.roundMin = roundMin;
        m.doneRounds = 0;
        m.planStart = System.currentTimeMillis();
        m.log.clear();
        Store.save(c, m);
        startRound(c, unit);
    }

    /** 다음 회차 시작 */
    public static void startRound(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        if (!m.planActive || m.planDone()) return;
        m.endAt = System.currentTimeMillis() + m.roundMin * 60_000L;
        m.durMin = m.roundMin;
        clearPause(m);
        m.round = true;
        m.finished = false;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
    }

    private static void clearPause(Store.Machine m) { m.pausedLeft = 0; m.pauseAt = 0; m.pausedMs = 0; }

    /** 일시정지: 남은 시간을 보관하고 알람 취소 */
    public static boolean pause(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        long now = System.currentTimeMillis();
        if (m.endAt <= now) return false;
        m.pausedLeft = m.endAt - now;
        m.pauseAt = now;
        m.endAt = 0;
        Store.save(c, m);
        unschedule(c, unit);
        return true;
    }

    /** 계속: 남은 시간부터 다시 */
    public static boolean resume(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        if (!m.paused()) return false;
        long now = System.currentTimeMillis();
        m.endAt = now + m.pausedLeft;
        if (m.pauseAt > 0) m.pausedMs += now - m.pauseAt;
        m.pausedLeft = 0;
        m.pauseAt = 0;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
        return true;
    }

    public static void stop(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        clearPause(m);
        m.endAt = 0;
        m.finished = false;
        Store.save(c, m);
        unschedule(c, unit);
    }

    public static void endPlan(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        m.planActive = false;
        clearPause(m);
        m.endAt = 0;
        m.finished = false;
        Store.save(c, m);
        unschedule(c, unit);
    }

    public static void addMinute(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        long now = System.currentTimeMillis();
        if (m.paused()) { m.pausedLeft += 60_000L; m.durMin += 1; Store.save(c, m); return; }
        if (m.endAt <= now) return;
        m.endAt += 60_000L;
        m.durMin += 1;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
    }

    public static void acknowledge(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        if (!m.finished) return;
        m.finished = false;
        Store.save(c, m);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(2000 + unit);
    }

    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(t)); }

    /**
     * 타이머가 끝났을 때 처리. 알람과 화면 갱신 양쪽에서 불릴 수 있으므로 endAt 이 일치할 때 한 번만 처리.
     * @return 알림 문구 (처리 안 했으면 null)
     */
    public static synchronized String finish(Context c, int unit, long expectedEnd) {
        Store.Machine m = Store.machine(c, unit);
        if (m.endAt == 0 || (expectedEnd != 0 && m.endAt != expectedEnd)) return null;
        if (System.currentTimeMillis() < m.endAt - 1500) return null;
        long end = m.endAt;
        String msg;
        if (m.round && m.planActive) {
            m.doneRounds++;
            long start = end - m.durMin * 60_000L - m.pausedMs;
            int idx = m.doneRounds;
            m.log.add(idx + "회 " + hm(start) + "–" + hm(end));
            Store.addRound(c, unit, m.spec, start, end);
            if (m.planDone()) msg = unit + "호기 작업 완료 (" + idx + "회) — 가공 결과를 입력하세요";
            else msg = unit + "호기 대기 — " + idx + "회 가공 끝";
        } else {
            msg = unit + "호기 " + m.durMin + "분 타이머 완료";
        }
        m.endAt = 0;
        m.lastEnd = end;
        clearPause(m);
        m.finished = true;
        m.finishMsg = msg;
        Store.save(c, m);
        notify(c, unit, msg);
        return msg;
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH, "호기 타이머 완료", NotificationManager.IMPORTANCE_HIGH);
        Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (snd == null) snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        ch.setSound(snd, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 900});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    static void notify(Context c, int unit, String msg) {
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra("openTimer", true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent p = PendingIntent.getActivity(c, 3000 + unit, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(unit + "호기")
                .setContentText(msg)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(p)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(2000 + unit, n); } catch (SecurityException ignored) {}
    }
}
