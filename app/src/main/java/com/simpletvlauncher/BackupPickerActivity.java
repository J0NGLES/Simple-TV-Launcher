package com.simpletvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Выбор резервной копии из папки «Загрузки» встроенным окном (без системного файлового менеджера). */
public class BackupPickerActivity extends Activity {
    private static final int COLS = 3;

    private Prefs prefs;
    private float s;
    private LinearLayout col;
    private TextView sub;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        s = UI.scale(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.addView(new BgView(this, prefs.settingsAccent()), new FrameLayout.LayoutParams(-1, -1));
        TextView title = UI.text(this, L.t("Восстановить из копии"), 54 * s, 0xFFFFFFFF, GlassView.MED);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(80 * s));
        tl.leftMargin = Math.round(96 * s);
        tl.topMargin = Math.round(50 * s);
        root.addView(title, tl);
        sub = UI.text(this, L.t("Копии из папки «Загрузки»"), 26 * s, 0xA6FFFFFF, GlassView.REG);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(40 * s));
        sl.leftMargin = Math.round(96 * s);
        sl.topMargin = Math.round(132 * s);
        root.addView(sub, sl);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        FrameLayout.LayoutParams svl = new FrameLayout.LayoutParams(Math.round(1760 * s), H - Math.round(190 * s));
        svl.leftMargin = Math.round(80 * s);
        svl.topMargin = Math.round(182 * s);
        root.addView(sv, svl);
        setContentView(root);
        start();
    }

    private static final int REQ = 71;

    /** На Android 6–12 для чтения «Загрузок» нужно разрешение на память; без него список мог быть пустым. */
    private void start() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
            return;
        }
        load();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        load();
    }

    private void load() {
        Weather.IO.execute(() -> {
            final List<Backup.Item> items = Backup.list(this);
            runOnUiThread(() -> populate(items));
        });
    }

    private void populate(List<Backup.Item> items) {
        col.removeAllViews();
        // Android 11+ скрывает от приложения zip-файлы, которых оно не создавало в этой установке (например, после переустановки или смены пакета).
        // Видеть все копии можно только с доступом «ко всем файлам»; запасной путь — выбрать файл вручную.
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            actionRow(L.t("Показать все копии: разрешить доступ ко всем файлам"), () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                    catch (Throwable t2) { Toast.makeText(this, L.t("На этом устройстве нет такого экрана настроек. Выберите файл вручную"), Toast.LENGTH_LONG).show(); }
                }
            });
        }
        actionRow(L.t("Выбрать файл копии вручную…"), () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, PICK); } catch (Throwable t) { Toast.makeText(this, L.t("Системный проводник недоступен"), Toast.LENGTH_LONG).show(); }
        });
        if (items.isEmpty()) {
            String msg = L.t("Копий в «Загрузках» не видно. Если они там есть, значит Android скрывает файлы, созданные другой установкой приложения: разрешите доступ ко всем файлам или выберите файл вручную. Иначе создайте новую копию в настройках.");
            TextView t = UI.text(this, msg, 28 * s, 0x99FFFFFF, GlassView.REG);
            t.setSingleLine(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1600 * s), Math.round(200 * s));
            lp.leftMargin = Math.round(16 * s);
            lp.topMargin = Math.round(16 * s);
            col.addView(t, lp);
            col.getChildAt(0).requestFocus();
            return;
        }
        float gap = 24 * s, tw = (1760 * s - 40 * s - (COLS - 1) * gap) / COLS, th = 230 * s;
        LinearLayout rowL = null;
        SimpleDateFormat df = new SimpleDateFormat("d MMMM yyyy", L.locale());
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.US);
        for (int k = 0; k < items.size(); k++) {
            if (k % COLS == 0) {
                rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Math.round(th + gap));
                rp.leftMargin = Math.round(16 * s);
                col.addView(rowL, rp);
            }
            final Backup.Item it = items.get(k);
            final String date = df.format(new Date(it.time)), time = tf.format(new Date(it.time));
            final GlassView g = new GlassView(this);
            g.radius = 28 * s;
            g.focusScale = 1.04f;
            g.painter = (c, v) -> paintCard(c, v, date, time, it);
            g.setOnClickListener(v -> {
                if (!it.ok) Toast.makeText(this, L.t("Восстановить нельзя: ") + it.problem, Toast.LENGTH_LONG).show();
                else confirm(it, date + ", " + time);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(tw), Math.round(th));
            lp.rightMargin = Math.round(gap);
            rowL.addView(g, lp);
        }
        for (int i = 0; i < col.getChildCount(); i++) {
            if (col.getChildAt(i) instanceof LinearLayout) { ((LinearLayout) col.getChildAt(i)).getChildAt(0).requestFocus(); break; }
        }
    }

    private static final int PICK = 72;

    private void actionRow(String label, Runnable r) {
        GlassView g = new GlassView(this);
        g.left = true;
        g.label = label;
        g.radius = 42 * s;
        g.focusScale = 1.02f;
        g.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1600 * s), Math.round(84 * s));
        lp.leftMargin = Math.round(16 * s);
        lp.bottomMargin = Math.round(14 * s);
        col.addView(g, lp);
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumedOnce) load();       // вернулись с экрана разрешений: список мог измениться
        resumedOnce = true;
    }

    private boolean resumedOnce;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri u = data.getData();
        Weather.IO.execute(() -> {
            final Backup.Item it = Backup.inspect(this, u);
            runOnUiThread(() -> {
                if (!it.ok) { Toast.makeText(this, L.t("Восстановить нельзя: ") + it.problem, Toast.LENGTH_LONG).show(); return; }
                confirm(it, new SimpleDateFormat("d MMMM yyyy, HH:mm", L.locale()).format(new Date(it.time)));
            });
        });
    }

    private void paintCard(Canvas c, GlassView v, String date, String time, Backup.Item it) {
        int w = v.getWidth(), h = v.getHeight();
        float pad = h * 0.1f;
        v.text(c, date, pad, h * 0.22f, h * 0.15f, 0xFFFFFFFF, 2, Paint.Align.LEFT, w - 2 * pad);
        v.text(c, time, pad, h * 0.40f, h * 0.17f, 0xF2FFFFFF, 0, Paint.Align.LEFT, w - 2 * pad);
        if (it.ok) {
            float f = h * 0.075f;
            v.text(c, it.bg, pad, h * 0.53f, f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - 2 * pad);
            v.text(c, it.grid, pad, h * 0.65f, f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - 2 * pad);
            v.text(c, it.pos, pad, h * 0.77f, f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - 2 * pad);
            v.text(c, it.glow, pad, h * 0.89f, f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - 2 * pad);
        } else {
            v.text(c, L.t("Восстановить нельзя"), pad, h * 0.67f, h * 0.088f, 0xFFFF9A8A, 2, Paint.Align.LEFT, w - 2 * pad);
            v.text(c, L.t("Копия другой версии или повреждена"), pad, h * 0.85f, h * 0.075f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - 2 * pad);
        }
    }
    private void confirm(final Backup.Item it, String when) {
        GlassDialog.menu(this, L.t("Восстановить настройки из копии от ") + when + "?", new String[]{L.t("ОК"), L.t("Отмена")}, i -> {
            if (i != 0) return;
            Weather.IO.execute(() -> {
                String err = null;
                try { Backup.restore(this, it.uri); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                final String er = err;
                runOnUiThread(() -> {
                    if (er != null) { Toast.makeText(this, L.t("Не удалось восстановить: ") + er, Toast.LENGTH_LONG).show(); return; }
                    Toast.makeText(this, L.t("Настройки восстановлены"), Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                });
            });
        }).show();
    }
}
