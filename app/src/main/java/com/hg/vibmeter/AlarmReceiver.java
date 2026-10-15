package com.hg.vibmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 타이머 시간이 되면 깨어나서 완료 처리 + 알림 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int unit = i.getIntExtra(TimerCenter.EXTRA_UNIT, 0);
        long end = i.getLongExtra(TimerCenter.EXTRA_END, 0);
        if (unit < 1 || unit > Store.UNITS) return;
        TimerCenter.finish(c, unit, end);
    }
}
