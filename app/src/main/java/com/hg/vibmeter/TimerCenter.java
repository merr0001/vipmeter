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
        m.round = false;
        m.finished = false;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
    }

    /** 가공 계획 시작 (회차 0부터) */
    public static void startPlan(Context c, int unit, int a, int b, int roundMin) {
        Store.Machine m = Store.machine(c, unit);
        m.planActive = true;
        m.planA = a; m.planB = b; m.roundMin = roundMin;
        m.doneRounds = 0; m.swapped = false;
        m.planStart = System.currentTimeMillis();
        m.log.clear();
        Store.save(c, m);
        startRound(c, unit);
    }

    /** 다음 회차 시작 (좌우 교체가 필요한 차례면 교체한 것으로 처리) */
    public static void startRound(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        if (!m.planActive || m.planDone()) return;
        if (m.swapDue()) {
            m.swapped = true;
            m.log.add("좌우 교체 " + hm(System.currentTimeMillis()));
        }
        m.endAt = System.currentTimeMillis() + m.roundMin * 60_000L;
        m.durMin = m.roundMin;
        m.round = true;
        m.finished = false;
        Store.save(c, m);
        schedule(c, unit, m.endAt);
    }

    public static void stop(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        m.endAt = 0;
        m.finished = false;
        Store.save(c, m);
        unschedule(c, unit);
    }

    public static void endPlan(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        m.planActive = false;
        m.endAt = 0;
        m.finished = false;
        Store.save(c, m);
        unschedule(c, unit);
    }

    public static void addMinute(Context c, int unit) {
        Store.Machine m = Store.machine(c, unit);
        long now = System.currentTimeMillis();
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
            long start = end - m.durMin * 60_000L;
            boolean after = m.swapped;
            int idx = after ? m.doneRounds - m.planA : m.doneRounds;
            m.log.add((after ? "좌우 " : "") + idx + "회 " + hm(start) + "–" + hm(end));
            if (m.planDone()) msg = unit + "호기 판 완료 — 가공 결과를 입력하세요";
            else if (m.swapDue()) msg = unit + "호기 " + m.planA + "회 끝 — 좌우 바꾸세요";
            else msg = unit + "호기 " + (after ? "좌우 " : "") + idx + "회 끝 — 다음 회차 시작";
        } else {
            msg = unit + "호기 " + m.durMin + "분 타이머 완료";
        }
        m.endAt = 0;
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
