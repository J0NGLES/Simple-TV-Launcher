package com.simpletvlauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Выход устройства из сна и запуск приложения автозапуска. Общая защита от двойного срабатывания (будят сразу и служба, и главный экран). */
final class Wake {
    private Wake() {}

    private static long last;       // когда приложение открывали в последний раз
    private static long lastWake;   // когда в последний раз обрабатывали пробуждение

    /** Устройство только что включили: загрузку обрабатывает отдельная логика, а сон сразу после неё считать не нужно. */
    static boolean afterBoot() { return SystemClock.elapsedRealtime() < 90000L; }

    /**
     * Устройство проснулось: возвращаем главный экран лаунчера поверх любого приложения и закрываем его настройки и список приложений
     * (первая плитка в фокусе), а затем, если включено, открываем приложение автозапуска.
     */
    static synchronized void wake(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (afterBoot() || now - lastWake < 5000L) return;
        lastWake = now;
        final Context a = c.getApplicationContext();
        Prefs p = new Prefs(a);
        final String pkg = p.autoApp();
        if (p.wakeHome()) goHome(a);
        else if (p.wakeApp() && !pkg.isEmpty()) new Handler(Looper.getMainLooper()).postDelayed(() -> openAuto(a, pkg), 500);
    }

    static void goHome(Context c) {
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable ignored) {}
    }

    /** Открывает приложение. Повтор в течение 20 секунд игнорируется. */
    static synchronized boolean openAuto(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        long now = SystemClock.elapsedRealtime();
        if (last != 0 && now - last < 20000L) return false;
        try {
            Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            last = now;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
