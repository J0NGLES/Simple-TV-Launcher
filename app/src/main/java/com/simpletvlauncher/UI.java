package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

public final class UI {
    private UI() {}

    /** Масштаб относительно эталонного экрана 1920x1080. */
    public static float scale(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.min(m.widthPixels / 1920f, m.heightPixels / 1080f);
    }

    public static void start(Context c, android.content.Intent i) {
        try {
            c.startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(c, L.t("Недоступно на этой приставке"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

        public static void action(Context c, String action) {
        start(c, new android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Системное окно с нуля: если оно уже было открыто, его задача сбрасывается и показывается главный экран, а не последняя страница. */
    public static void actionFresh(Context c, String action) {
        start(c, new android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    /** Множитель размера шрифта из настроек (1 — как было). */
    public static float fs = 1f;

    /** Текст с учётом выбранного размера шрифта. */
    public static TextView text(Context c, String t, float px, int color, Typeface tf) {
        return text(c, t, px, color, tf, true);
    }

    /** scale = false — размер не зависит от настройки (часы). */
    public static TextView text(Context c, String t, float px, int color, Typeface tf, boolean scale) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale ? px * fs : px);
        v.setTextColor(color);
        v.setTypeface(tf);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
}
