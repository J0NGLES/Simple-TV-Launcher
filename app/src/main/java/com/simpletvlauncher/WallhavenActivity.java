package com.simpletvlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Онлайн-каталог обоев wallhaven.cc: темы и порядок. */
public class WallhavenActivity extends Activity {
    private static final int REQ_WRITE = 12, COLS = 4, MAX_SHOWN = 120;

    private Prefs prefs;
    private float s, tw, th;
    private LinearLayout col, grid;
    private LinearLayout lastRow;
    private View footer;
    private TextView sub;
    private GlassView themeBtn, sortBtn;
    private int shown;
    /** Растёт при каждой новой подборке: ответы и миниатюры от старой отбрасываются. */
    private volatile int gen;
    private boolean loading, busy;
    private Wallhaven.Page last;
    private Wallhaven.Item pending;
    private boolean pendingApply;
    private ExecutorService pool;
    /** Все миниатюры в памяти: освобождаются при смене подборки и при выходе с экрана. На диск они не пишутся. */
    private final List<Bitmap> held = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        pool = Executors.newFixedThreadPool(3);
        s = UI.scale(this);
        tw = (1760 * s - 40 * s - (COLS - 1) * 20 * s) / COLS;
        th = tw * 9f / 16f;
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.addView(new BgView(this, prefs.settingsAccent()), new FrameLayout.LayoutParams(-1, -1));
        TextView title = UI.text(this, L.t("Обои из онлайн-каталога"), 54 * s, 0xFFFFFFFF, GlassView.MED);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(80 * s));
        tl.leftMargin = Math.round(96 * s);
        tl.topMargin = Math.round(50 * s);
        root.addView(title, tl);
        sub = UI.text(this, credit(), 26 * s, 0xA6FFFFFF, GlassView.REG);
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
        LiquidGlass.watch(getWindow());

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setClipChildren(false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, Math.round(100 * s));
        hp.leftMargin = Math.round(16 * s);
        col.addView(head, hp);
        themeBtn = button("", Icons.LIST);
        themeBtn.setOnClickListener(v -> askTheme());
        sortBtn = button("", Icons.TUNE);
        sortBtn.setOnClickListener(v -> askSort());
        for (GlassView g : new GlassView[]{themeBtn, sortBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(640 * s), Math.round(80 * s));
            lp.rightMargin = Math.round(24 * s);
            head.addView(g, lp);
        }
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        col.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        themeBtn.requestFocus();
        reload();
    }
    @Override protected void onDestroy() {
        gen++;
        pool.shutdownNow();
        dropThumbs();
        if (!busy) Wallhaven.clearTemp(this);       // идущая загрузка уберёт свои временные файлы сама
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_WRITE) {
            Wallhaven.Item it = pending;
            pending = null;
            if (ok && it != null) download(it, pendingApply);
            else if (!ok) toast(L.t("Без доступа к памяти обои не сохранить"));
        }
    }

    // ------------------------------------------------------------------ элементы
    private GlassView button(String label, int icon) {
        GlassView r = new GlassView(this);
        r.left = true;
        r.label = label;
        r.icon = icon;
        r.radius = 40 * s;
        r.focusScale = 1.03f;
        return r;
    }

    /** Вызывается после увеличения gen: опоздавшие миниатюры в память уже не попадут. */
    private void dropThumbs() {
        if (grid != null) grid.removeAllViews();
        synchronized (held) {
            for (Bitmap b : held) b.recycle();
            held.clear();
        }
        if (last != null) for (Wallhaven.Item it : last.items) it.bmp = null;
    }

    /** Из фонового потока: оставить миниатюру, только если её подборка всё ещё на экране. */
    private Bitmap keep(Bitmap b, int g) {
        if (b == null) return null;
        synchronized (held) {
            if (g != gen) { b.recycle(); return null; }
            held.add(b);
            return b;
        }
    }

    private void clearGrid() {
        grid.removeAllViews();
        lastRow = null;
        footer = null;
        shown = 0;
    }

    private void removeFooter() {
        if (footer != null) grid.removeView(footer);
        footer = null;
    }

    private void note(String text) {
        removeFooter();
        TextView t = UI.text(this, text, 30 * s, 0x99FFFFFF, GlassView.REG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(70 * s));
        lp.leftMargin = Math.round(16 * s);
        grid.addView(t, lp);
        footer = t;
    }

    private GlassView footerButton(String label, int icon) {
        removeFooter();
        GlassView r = button(label, icon);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(900 * s), Math.round(80 * s));
        lp.leftMargin = Math.round(16 * s);
        lp.topMargin = Math.round(8 * s);
        lp.bottomMargin = Math.round(24 * s);
        grid.addView(r, lp);
        footer = r;
        return r;
    }

    private GlassView tile() {
        if (lastRow == null || lastRow.getChildCount() >= COLS) {
            lastRow = new LinearLayout(this);
            lastRow.setOrientation(LinearLayout.HORIZONTAL);
            lastRow.setClipChildren(false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Math.round(th + 20 * s));
            rp.leftMargin = Math.round(16 * s);
            grid.addView(lastRow, rp);
        }
        GlassView g = new GlassView(this);
        g.radius = 20 * s;
        g.focusScale = 1.05f;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(tw), Math.round(th));
        lp.rightMargin = Math.round(20 * s);
        lastRow.addView(g, lp);
        shown++;
        return g;
    }

    private void paintTile(Canvas c, GlassView v, Bitmap b) {
        int w = v.getWidth(), h = v.getHeight();
        if (b != null && !b.isRecycled()) {
            float sc = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            c.save();
            c.translate((w - b.getWidth() * sc) / 2f, (h - b.getHeight() * sc) / 2f);
            c.scale(sc, sc);
            v.tp.setShader(null);
            v.tp.setColor(0xFF000000);
            v.tp.setStyle(Paint.Style.FILL);
            c.drawBitmap(b, 0, 0, v.tp);
            c.restore();
        }

    }

    // ------------------------------------------------------------------ каталог
    private int theme() { return Math.max(0, Math.min(Wallhaven.THEMES.length - 1, prefs.raw().getInt("whTheme", 0))); }

    private int sort() { return Math.max(0, Math.min(Wallhaven.SORT_NAMES.length - 1, prefs.raw().getInt("whSort", 0))); }

    private void askTheme() {
        String[] names = new String[Wallhaven.THEMES.length];
        for (int i = 0; i < names.length; i++) names[i] = L.t(Wallhaven.THEMES[i][0]);
        GlassDialog.menu(this, L.t("Тема"), names, theme(), i -> {
            prefs.raw().edit().putInt("whTheme", i).apply();
            reload();
        }).show();
    }

    private void askSort() {
        String[] names = new String[Wallhaven.SORT_NAMES.length];
        for (int i = 0; i < names.length; i++) names[i] = L.t(Wallhaven.SORT_NAMES[i]);
        GlassDialog.menu(this, L.t("Порядок"), names, sort(), i -> {
            prefs.raw().edit().putInt("whSort", i).apply();
            reload();
        }).show();
    }

    private void reload() {
        themeBtn.label = L.t("Тема: ") + L.t(Wallhaven.THEMES[theme()][0]);
        themeBtn.invalidate();
        sortBtn.label = L.t("Порядок: ") + L.t(Wallhaven.SORT_NAMES[sort()]);
        sortBtn.invalidate();
        gen++;
        loading = false;
        dropThumbs();
        last = null;
        clearGrid();
        loadPage(1, "", false);
    }

    private void loadPage(final int page, final String seed, final boolean replace) {
        if (loading) return;
        loading = true;
        final int g = gen, t = theme(), o = sort();
        // кнопка «Показать ещё» остаётся на месте (и под выделением), пока ответ не пришёл
        if (footer instanceof GlassView) {
            ((GlassView) footer).label = L.t("Загрузка…");
            footer.invalidate();
        } else note(L.t("Загрузка…"));
        Weather.IO.execute(() -> {
            Wallhaven.Page res = null;
            int code = 0;
            try { res = Wallhaven.search(t, o, page, seed); }
            catch (Wallhaven.HttpError e) { code = e.code; }
            catch (Throwable ignored) {}
            final Wallhaven.Page r = res;
            final int err = code;
            runOnUiThread(() -> {
                if (g != gen || isDestroyed()) return;
                loading = false;
                if (r == null) {
                    sub.setText(errorText(err));
                    GlassView again = footerButton(L.t("Повторить"), Icons.REFRESH);
                    again.setOnClickListener(v -> loadPage(page, seed, replace));
                    again.post(again::requestFocus);
                    return;
                }
                sub.setText(credit());
                showPage(r, replace);
            });
        });
    }

    private String errorText(int code) {
        if (code == 429) return L.t("Слишком много запросов к сайту. Подождите минуту и повторите");
        if (code != 0) return L.t("Сайт wallhaven.cc ответил ошибкой: ") + code;
        return L.t("Нет связи с wallhaven.cc. Проверьте интернет");
    }

    private String credit() {
        return L.t("Сайт wallhaven.cc. Права на изображения принадлежат их авторам");
    }

    private void showPage(Wallhaven.Page r, boolean replace) {
        boolean focusInGrid = footer != null && footer.isFocused();
        boolean first = shown == 0 || replace;
        if (replace) {
            gen++;                      // миниатюры убранных плиток больше не нужны
            dropThumbs();
            clearGrid();
        } else if (focusInGrid) {
            themeBtn.requestFocus();    // иначе при удалении кнопки выделение пропадёт; ниже оно переедет на новую плитку
            removeFooter();
        } else removeFooter();
        last = r;
        View target = null;
        final int g = gen, width = Math.round(tw);
        for (final Wallhaven.Item it : r.items) {
            GlassView v = tile();
            v.painter = (c, view) -> paintTile(c, view, it.bmp);
            v.setOnClickListener(x -> askDownload(it));
            if (target == null) target = v;
            final GlassView tv = v;
            pool.execute(() -> {
                if (g != gen) return;
                try { it.bmp = keep(Wallhaven.thumb(it.thumb, width), g); } catch (Throwable ignored) {}
                tv.postInvalidate();
            });
        }
        if (shown == 0) {
            note(L.t("Ничего не найдено"));
            return;
        }
        if (r.page < r.last) {
            final boolean next = shown + r.items.size() > MAX_SHOWN;     // чтобы не копить сотни картинок в памяти
            GlassView more = footerButton(next ? L.t("Следующая страница") : L.t("Показать ещё"), Icons.REFRESH);
            more.setOnClickListener(v -> loadPage(last.page + 1, last.seed, next));
        }
        if (target != null && (first || focusInGrid)) {
            final View f = target;
            f.post(f::requestFocus);
        }
    }

    private void askDownload(final Wallhaven.Item it) {
        if (busy) { toast(L.t("Дождитесь окончания загрузки")); return; }
        String info = L.t("Сохранится в Full HD") + (it.size > 0 ? " · " + L.t("загрузка ") + String.format(Locale.US, "%.1f ", it.size / 1048576f) + L.t("МБ") : "");
        GlassDialog.menu(this, info, new String[]{L.t("Скачать и установить фоном"), L.t("Только скачать"), L.t("Отмена")}, i -> {
            if (i == 2) return;
            download(it, i == 0);
        }).show();
    }

    private void download(final Wallhaven.Item it, final boolean apply) {
        // на Android 7–9 для записи в общую папку нужно разрешение
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = it;
            pendingApply = apply;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        busy = true;
        final Context app = getApplicationContext();
        sub.setText(L.t("Скачивание…"));
        Weather.IO.execute(() -> {
            Uri uri = null;
            int code = 0;
            try {
                uri = Wallhaven.download(app, it, pc -> runOnUiThread(() -> {
                    if (busy) sub.setText(L.t("Скачивание…") + " " + pc + " %");
                }));
            } catch (Wallhaven.HttpError e) { code = e.code; }
            catch (Throwable ignored) {}
            final boolean saved = uri != null;
            if (saved) Wallhaven.keep(prefs, Wallhaven.fileName(it));     // выбранное вручную автосмена не удаляет
            final boolean set = saved && apply && Bg.setImage(app, uri);
            if (set) useAsBackground();
            final int err = code;
            runOnUiThread(() -> {
                busy = false;
                sub.setText(credit());
                if (!saved) toast(err != 0 ? errorText(err) : L.t("Не удалось скачать или сохранить файл"));
                else if (!apply) toast(L.t("Сохранено в Pictures/SimpleTVLauncher"));
                else applied(set);
            });
        });
    }

    // ------------------------------------------------------------------ общее
    private void useAsBackground() {
        prefs.setBgMode(1);
        prefs.raw().edit().remove("whCur").apply();        // на экране уже не обои, поставленные автосменой
        if (prefs.wpActive()) prefs.setWpLast(System.currentTimeMillis());     // автосмена отсчитывает интервал заново
    }

    private void applied(boolean ok) {
        if (!ok) { toast(L.t("Не удалось прочитать файл")); return; }
        SoftVideo.forget(this);
        toast(prefs.wpActive() ? L.t("Фон установлен. Включена автосмена: она заменит его по расписанию") : L.t("Фон установлен"));
    }

    private void toast(String t) { Toast.makeText(getApplicationContext(), t, Toast.LENGTH_LONG).show(); }
}
