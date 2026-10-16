package com.hg.vibmeter;

import android.content.Context;
import android.graphics.Color;
import android.widget.TextView;

/** 가공 종류: 스펙 이름 = "직경-전장 종류" (예: 67-30 N60) */
final class Kind {
    private Kind() {}

    static final String[] LIST = {"R", "RR", "N90", "N60", "N45", "N30", "NF"};

    /** "67-30 N60" → {"67-30", "N60"} · "48-20" → {"48-20", ""} */
    static String[] split(String name) {
        String n = name == null ? "" : name.trim();
        int i = n.indexOf(' ');
        if (i > 0) return new String[]{n.substring(0, i).trim(), n.substring(i + 1).trim()};
        return new String[]{n, ""};
    }

    static String join(String base, String kind) {
        String b = base.trim().replace(" ", ""), k = kind.trim().replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        return k.isEmpty() ? b : b + " " + k;
    }

    static int order(String kind) {
        for (int i = 0; i < LIST.length; i++) if (LIST[i].equalsIgnoreCase(kind)) return i;
        return kind.isEmpty() ? -1 : 50;
    }

    /** {글자색, 바탕색} */
    static int[] colors(String k) {
        String u = k.toUpperCase(java.util.Locale.ROOT);
        if (u.startsWith("NF")) return new int[]{Color.rgb(0x8a, 0x61, 0x10), Color.rgb(0xf6, 0xe6, 0xc4)};
        if (u.startsWith("R")) return new int[]{Color.rgb(0x2f, 0x5f, 0x8a), Color.rgb(0xe0, 0xea, 0xf4)};
        if (u.startsWith("N")) return new int[]{Color.rgb(0x7a, 0x3f, 0x8a), Color.rgb(0xee, 0xe1, 0xf2)};
        return new int[]{U.SUB, Color.rgb(0xe3, 0xe7, 0xe1)};
    }

    static TextView tag(Context c, String k, float sp) {
        int[] col = colors(k);
        TextView t = U.tv(c, k, sp, col[0], U.MONO_B);
        t.setBackground(U.box(c, col[1], 3, col[1], 0));
        t.setPadding(U.dp(c, 5), U.dp(c, 1), U.dp(c, 5), U.dp(c, 1));
        return t;
    }
}
