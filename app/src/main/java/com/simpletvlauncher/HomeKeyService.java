package com.simpletvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Необязательная служба для телевизоров, где системная кнопка «Домой» всегда ведёт в стандартный лаунчер (Xiaomi, Philips и др.),
 * даже если по умолчанию выбран другой. Включается вручную в «Специальных возможностях»:
 *  - перехватывает клавишу Home и открывает Simple TV Launcher;
 *  - после включения ТВ (система сама поднимает службу при загрузке) один раз открывает лаунчер, чтобы не оставаться на HDMI/ТВ-входе.
 *
 * Перехват клавиш (фильтр) включается только когда он нужен: пока включён «Домой» в настройках или в первые секунды после загрузки.
 * Иначе каждое нажатие пульта проходило бы через эту службу, и на некоторых телевизорах кнопки начинали «залипать».
 */
public class HomeKeyService extends AccessibilityService {
    private static final long BOOT_WINDOW_MS = 4 * 60 * 1000L;      // «свежая» загрузка: служба поднялась не позже, чем через 4 минуты после старта системы
    private static final long POLL_MS = 500;            // как часто проверяем, что сверху лаунчер
    private static final long MAX_MS = 30000;           // предохранитель

    private final Handler h = new Handler(Looper.getMainLooper());
    private String fg = "";
    private boolean bootRun, polling;
    private long pollStart;
    private SharedPreferences.OnSharedPreferenceChangeListener prefL;
    private BroadcastReceiver wakeRx;

    private void setPolling(boolean v) {
        if (polling == v) return;
        polling = v;
        updateKeyFilter();
    }

    /** Фильтр клавиш нужен только для перехвата «Домой» и для короткого окна сразу после загрузки. */
    private void updateKeyFilter() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            boolean need = polling || new Prefs(this).homeKey();
            int flags = need ? (info.flags | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
                    : (info.flags & ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS);
            if (flags == info.flags) return;
            info.flags = flags;
            setServiceInfo(info);
        } catch (Throwable ignored) {}
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            if (SystemClock.elapsedRealtime() - pollStart > MAX_MS) { setPolling(false); return; }
            String auto = new Prefs(HomeKeyService.this).autoApp();
            // лаунчер на экране — задача выполнена; то же, если уже открылось приложение автозапуска (иначе лаунчер перехватил бы экран у него)
            if (getPackageName().equals(fg) || (!auto.isEmpty() && auto.equals(fg))) { setPolling(false); return; }
            openHome();
            h.postDelayed(this, POLL_MS);
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            String p = e.getPackageName().toString();
            if (!p.startsWith("com.android.systemui")) fg = p;
        }
    }

    @Override public void onInterrupt() {}

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            Prefs prefs = new Prefs(this);
            updateKeyFilter();
            // переключили «Домой» в настройках лаунчера — фильтр клавиш включается или выключается сразу
            prefL = (sp, key) -> { if ("homeKey".equals(key)) updateKeyFilter(); };
            prefs.raw().registerOnSharedPreferenceChangeListener(prefL);
            // пробуждение устройства: служба живёт и пока другое приложение на экране, поэтому может открыть приложение автозапуска и тогда
            if (wakeRx == null) {
                wakeRx = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) { Wake.wake(HomeKeyService.this); }
                };
                registerReceiver(wakeRx, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
            if (!prefs.bootHome() || bootRun) return;
            if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return;
            int boot = -1;
            try { boot = Settings.Global.getInt(getContentResolver(), "boot_count", -1); } catch (Throwable ignored) {}
            if (boot != -1 && prefs.raw().getInt("homeBootDone", -2) == boot) return;      // на этой загрузке уже открывали
            prefs.raw().edit().putInt("homeBootDone", boot).apply();
            bootRun = true;
            pollStart = SystemClock.elapsedRealtime();
            setPolling(true);
            h.post(poll);
        } catch (Throwable ignored) {}
    }

    @Override public boolean onUnbind(Intent i) {
        h.removeCallbacksAndMessages(null);
        try { if (prefL != null) new Prefs(this).raw().unregisterOnSharedPreferenceChangeListener(prefL); } catch (Throwable ignored) {}
        try { if (wakeRx != null) unregisterReceiver(wakeRx); } catch (Throwable ignored) {}
        wakeRx = null;
        return super.onUnbind(i);
    }

    private void openHome() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable ignored) {}
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) { if (polling) setPolling(false); return false; }      // пользователь сам взялся за пульт — не мешаем
        if (!new Prefs(this).homeKey()) return false;         // перехват «Домой» выключен в настройках
        if (e.getAction() == KeyEvent.ACTION_UP) openHome();
        return true;        // нажатие поглощаем, чтобы система не открыла свой лаунчер
    }
}
