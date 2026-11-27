package com.simpletvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

/**
 * Полный список приложений.
 * OK запускает приложение; в режимах «скрыть сразу несколько» и «в избранное» — отмечает его.
 * Удерживайте OK — меню (открыть, скрыть/показать, удалить, о приложении).
 */
public class ManageActivity extends Activity {
    private Prefs prefs;
    private float s;
    private boolean favMode, hideMode;
    private LinearLayout col;
    private Snap sv;
    private TextView sub;
    private List<AppRepo.AppItem> all;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        s = UI.scale(this);
        favMode = getIntent().getBooleanExtra("fav", false);
        hideMode = getIntent().getBooleanExtra("hide", false);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.addView(bgView = new BgView(this, prefs.settingsAccent()), new FrameLayout.LayoutParams(-1, -1));

        TextView title = UI.text(this, L.t("Все приложения"), 58 * s, 0xFFFFFFFF, GlassView.MED);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(80 * s));
        tl.leftMargin = Math.round(96 * s);
        tl.topMargin = Math.round(50 * s);
        root.addView(title, tl);

        TextView cap = UI.text(this, L.t("Отображение на главном экране"), 22 * s, 0x80FFFFFF, GlassView.REG);
        cap.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        cap.setPadding(0, 0, Math.round(38.4f * s), 0);       // правый край — над столбцом «Показано» / «Скрыто»
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(40 * s));
        cl.leftMargin = Math.round(96 * s);
        cl.topMargin = Math.round(132 * s);
        root.addView(cap, cl);
        sub = UI.text(this, "", 26 * s, 0xA6FFFFFF, GlassView.REG);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(40 * s));
        sl.leftMargin = Math.round(96 * s);
        sl.topMargin = Math.round(132 * s);
        root.addView(sub, sl);

        sv = new Snap(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        // на экране только целые кнопки: список прокручивается ровно на строку, а то, что не помещается целиком, не рисуется
        sv.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (n == null || n.getParent() != col) return;
            Object t = n.getTag();
            if (t instanceof String) lastPkg = (String) t;
            lastIdx = rows().indexOf(n);
            if (!rebuilding) snapTo(n);       // сразу, до отрисовки: иначе один кадр выбранная строка рисуется вне видимой области
        });
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        FrameLayout.LayoutParams svl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), H - Math.round(188 * s));
        svl.leftMargin = Math.round(96 * s);
        svl.topMargin = Math.round(190 * s);
        root.addView(sv, svl);
        setContentView(root);
        LiquidGlass.watch(getWindow());

        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);      // приложение включили или отключили
        f.addDataScheme("package");
        registerReceiver(pkgChanged, f);
    }

    private int tgt;                // положение прокрутки, к которому идёт список (начало какой-нибудь строки)
    private int first;              // номер строки, с которой начинается видимая часть списка
    private String lastPkg;         // приложение, на котором стоял фокус (чтобы вернуть его после перестройки списка)
    private int lastIdx;            // и номер его строки, если приложения уже нет в списке (например, удалили)
    private boolean rebuilding;     // список только что перестроен и ещё не разложен по экрану: прокручивать рано
    private ViewTreeObserver.OnPreDrawListener pre;

    private int margin() { return Math.round(8 * s); }

    private List<View> rows() {
        List<View> r = new java.util.ArrayList<>();
        for (int k = 0; k < col.getChildCount(); k++) if (col.getChildAt(k).isFocusable()) r.add(col.getChildAt(k));
        return r;
    }

    /** Считает, к какому положению прокрутить список, чтобы строка n была видна целиком (список всегда начинается с начала строки). */
    private void aim(View n) {
        List<View> rows = rows();
        int idx = rows.indexOf(n), m = margin(), hgt = sv.getHeight();
        if (idx < 0) return;
        first = Math.max(0, Math.min(first, rows.size() - 1));
        if (idx < first) first = idx;
        else while (first < idx && n.getBottom() > rows.get(first).getTop() - m + hgt) first++;
        tgt = Math.min(Math.max(0, rows.get(first).getTop() - m), sv.max());
    }

    private void snapTo(View n) {
        aim(n);
        sv.jump(tgt);       // без плавной анимации: список двигается сразу вместе с выделением
    }

    /**
     * Свой список вместо ScrollView: тот сам «доводит» выбранную строку на экран в момент перестройки списка и сбивает
     * положение (то обрезанная строка сверху, то пустой экран). Здесь прокручивается только то, что решили мы.
     */
    private class Snap extends FrameLayout {
        Snap(android.content.Context c) { super(c); }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            View c = getChildAt(0);
            if (c != null) c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        int max() {
            View c = getChildAt(0);
            return c == null ? 0 : Math.max(0, c.getHeight() - getHeight());
        }

        void jump(int y) {
            scrollTo(0, y);
        }

        @Override protected void dispatchDraw(android.graphics.Canvas c) {
            // выделение не должно уходить от видимой области дальше чем на строку: если ушло (кадры не успевали) — список догоняет мгновенно
            View f = col.getFocusedChild();
            if (f != null && !rebuilding) {
                int sy = getScrollY(), row = Math.round(98 * s);
                int off = f.getTop() < sy ? sy - f.getTop() : Math.max(0, f.getBottom() - (sy + getHeight()));
                if (off > row) { aim(f); jump(tgt); }
            }
            int lim = tgt + getHeight(), best = 0;
            for (View v : rows()) if (v.getBottom() <= lim) best = Math.max(best, v.getBottom());
            if (best == 0) { super.dispatchDraw(c); return; }
            c.save();
            c.clipRect(0, getScrollY(), getWidth(), Math.min(getScrollY() + getHeight(), best + Math.round(6 * s)));
            super.dispatchDraw(c);
            c.restore();
        }
    }
    private int langV = L.version;
    private BgView bgView;

    @Override protected void onResume() {
        super.onResume();
        if (L.version != langV) { recreate(); return; }      // язык сменили, пока экран был закрыт
        bgView.syncLook();
        reload.run();
    }

    private boolean stale;          // список изменился, пока поверх был открыт диалог: перестроим, когда он закроется

    private final Runnable reload = new Runnable() {
        @Override public void run() {
            Weather.IO.execute(() -> {
                final List<AppRepo.AppItem> l = AppRepo.loadAll(ManageActivity.this);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    all = l;
                    // под открытым меню строки не подменяем: меню держит ссылку на свою строку
                    if (col.getChildCount() > 0 && !hasWindowFocus()) stale = true; else populate();
                });
            });
        }
    };

    /** Приложение установили, удалили или обновили, пока список открыт (например, по ADB). */
    private final android.content.BroadcastReceiver pkgChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            // при обновлении система сначала сообщает об «удалении»: ждём сообщения об установке, чтобы строка не пропадала
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            col.removeCallbacks(reload);
            col.postDelayed(reload, 400);       // несколько сообщений подряд — одно обновление списка
        }
    };

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && stale) { stale = false; col.post(() -> { if (!isDestroyed()) populate(); }); }
    }

    @Override protected void onDestroy() {
        unregisterReceiver(pkgChanged);
        col.removeCallbacks(reload);
        super.onDestroy();
    }

    private void updateSub() {
        int hidden = 0;
        Set<String> hs = prefs.hidden();
        for (AppRepo.AppItem a : all) if (hs.contains(a.pkg)) hidden++;
        if (favMode) sub.setText(L.t("OK — добавить в избранное или убрать · удержание — меню"));
        else if (hideMode) sub.setText(L.t("OK — скрыть или показать на главном экране · удержание — меню"));
        else sub.setText(L.t("Всего: ") + all.size() + L.t(" · скрыто: ") + hidden + L.t(" · избранных: ") + prefs.favs().size());
    }

    private void populate() { populate(null); }

    /** focusPkg — приложение, на которое вернуть фокус после перестройки списка (иначе на то, где он стоял; если его уже нет — на ту же позицию). */
    private void populate(String focusPkg) {
        if (focusPkg == null) focusPkg = lastPkg;
        final int prevIdx = lastIdx;
        col.removeAllViews();
        int want = -1, n = 0;
        final List<AppRepo.AppItem> lst = AppRepo.favFirst(all, prefs);
        final List<String> fav = prefs.favs();
        for (final AppRepo.AppItem a : lst) {
            final GlassView r = new GlassView(this);
            r.left = true;
            r.label = a.label;
            r.appIcon = a.icon;
            r.radius = 22 * s;
            r.focusScale = 1.02f;
            r.setTag(a.pkg);
            style(r, a);
            r.setOnClickListener(v -> {
                if (favMode) toggleFav(a); else if (hideMode) toggle(r, a); else menu(r, a);     // OK: открыть, показать/скрыть, избранное, удалить, о приложении
            });
            r.setOnLongClickListener(v -> { menu(r, a); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(88 * s));
            lp.bottomMargin = Math.round(10 * s);
            lp.leftMargin = Math.round(12 * s);
            lp.rightMargin = Math.round(12 * s);
            col.addView(r, lp);
            if (a.pkg.equals(focusPkg)) want = col.getChildCount() - 1;
            // отсечка между избранными и остальными
            if (fav.contains(a.pkg) && n + 1 < lst.size() && !fav.contains(lst.get(n + 1).pkg)) {
                View sep = new View(this);
                sep.setBackgroundColor(0x40FFFFFF);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, Math.max(2, Math.round(3 * s)));
                sl.topMargin = Math.round(8 * s);
                sl.bottomMargin = Math.round(18 * s);
                sl.leftMargin = Math.round(40 * s);
                sl.rightMargin = Math.round(40 * s);
                col.addView(sep, sl);
            }
            n++;
        }
        updateSub();
        View target = want >= 0 ? col.getChildAt(want) : null;
        if (target == null) {                  // приложения больше нет (удалили) или фокуса ещё не было: та же позиция
            List<View> rs = rows();
            if (!rs.isEmpty()) target = rs.get(Math.max(0, Math.min(prevIdx, rs.size() - 1)));
        }
        if (pre != null && sv.getViewTreeObserver().isAlive()) sv.getViewTreeObserver().removeOnPreDrawListener(pre);
        pre = null;
        if (target == null) { rebuilding = false; return; }
        final View ft = target;
        rebuilding = true;           // пока строки не разложены по экрану, положение прокрутки не считаем
        ft.requestFocus();
        // как только список разложен, но ещё до первого кадра — ставим прокрутку так, чтобы выбранная строка была видна целиком
        pre = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (sv.getViewTreeObserver().isAlive()) sv.getViewTreeObserver().removeOnPreDrawListener(this);
                pre = null;
                rebuilding = false;
                View fv = getCurrentFocus();
                if (fv == null || fv.getParent() != col) fv = ft;
                aim(fv);
                sv.jump(tgt);
                return true;
            }
        };
        sv.getViewTreeObserver().addOnPreDrawListener(pre);
        sv.invalidate();
    }

    private void style(GlassView r, AppRepo.AppItem a) {
        boolean hid = prefs.hidden().contains(a.pkg);
        r.lock = prefs.isLocked(a.pkg);
        r.fav = prefs.favs().contains(a.pkg);
        r.eye = hid ? 2 : 1;        // глаз: показано — 100%, скрыто — 50%
        r.invalidate();
    }

    private void toggle(GlassView r, AppRepo.AppItem a) {
        Set<String> hs = prefs.hidden();
        if (!hs.remove(a.pkg)) {
            hs.add(a.pkg);
        } else {
            // вернувшееся приложение ставим в конец списка
            java.util.List<String> o = prefs.order();
            o.remove(a.pkg);
            if (prefs.gridAlign() == 1) o.add(0, a.pkg); else o.add(a.pkg);      // по центру новые идут на верхнюю строку
            prefs.setOrder(o);
        }
        prefs.setHidden(hs);
        style(r, a);
        updateSub();
    }

    private void toggleFav(final AppRepo.AppItem a) {
        List<String> f = prefs.favs();
        if (!f.remove(a.pkg)) f.add(a.pkg);
        prefs.setFavs(f);
        populate(a.pkg);
    }

    /** Двигает избранное приложение выше или ниже среди избранных. */
    private void moveFav(final AppRepo.AppItem a, int d) {
        List<String> f = prefs.favs();
        int i = f.indexOf(a.pkg), j = i + d;
        if (i < 0 || j < 0 || j >= f.size()) return;
        f.remove(i);
        f.add(j, a.pkg);
        prefs.setFavs(f);
        populate(a.pkg);
    }

    private void launch(final AppRepo.AppItem a) {
        android.app.Dialog d = Parental.guard(this, prefs, a.pkg, a.label, () -> {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(a.component())
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UI.start(this, i);
        });
        if (d != null) d.show();
    }

    /** Защита приложения PIN-кодом: первый раз попросим придумать код, снять защиту можно только зная код. */
    private void toggleLock(final GlassView r, final AppRepo.AppItem a) {
        if (prefs.isLocked(a.pkg)) {
            Parental.askPin(this, prefs, a.label, L.t("Введите PIN-код, чтобы снять защиту"), () -> {
                java.util.Set<String> ls = prefs.locked();
                ls.remove(a.pkg);
                prefs.setLocked(ls);
                style(r, a);
            }).show();
            return;
        }
        final Runnable lockIt = () -> {
            java.util.Set<String> ls = prefs.locked();
            ls.add(a.pkg);
            prefs.setLocked(ls);
            style(r, a);
        };
        if (prefs.hasPin()) lockIt.run();
        else Parental.createPin(this, prefs, lockIt).show();
    }

    private void menu(final GlassView r, final AppRepo.AppItem a) {
        boolean hid = prefs.hidden().contains(a.pkg);
        boolean lk = prefs.isLocked(a.pkg);
        final List<String> fl = prefs.favs();
        final boolean fv = fl.contains(a.pkg);
        List<String> its = new java.util.ArrayList<>();
        final List<Integer> ids = new java.util.ArrayList<>();
        String[] base = {L.t("Открыть"), hid ? L.t("Показать на рабочем столе") : L.t("Скрыть с рабочего стола"),
                fv ? L.t("Убрать из избранного") : L.t("В избранное"),
                lk ? L.t("Снять защиту PIN-кодом") : L.t("Защитить PIN-кодом"), L.t("Удалить"), L.t("О приложении")};
        for (int k = 0; k < base.length; k++) {
            its.add(base[k]); ids.add(k);
            if (k == 2 && fv) {
                int pos = fl.indexOf(a.pkg);
                if (pos > 0) { its.add(L.t("Переместить выше")); ids.add(10); }
                if (pos < fl.size() - 1) { its.add(L.t("Переместить ниже")); ids.add(11); }
            }
        }
        GlassDialog.menu(this, a.label, its.toArray(new String[0]), i -> {
            switch (ids.get(i)) {
                case 0: launch(a); break;
                case 1: toggle(r, a); break;
                case 2: toggleFav(a); break;
                case 3: toggleLock(r, a); break;
                case 4: UI.start(this, new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); break;
                case 5: UI.start(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))); break;
                case 10: moveFav(a, -1); break;
                case 11: moveFav(a, 1); break;
            }
        }).show();
    }
}
