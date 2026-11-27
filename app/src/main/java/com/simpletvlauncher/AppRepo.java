package com.simpletvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Список установленных приложений + применение скрытия и порядка. */
public final class AppRepo {

    public static class AppItem {
        public String pkg, cls, label;
        public Drawable icon, banner;
        public int color;          // кэш доминантного цвета (0 = ещё не считали)
        android.graphics.Bitmap square;      // готовая картинка квадратной плитки
        public ComponentName component() { return new ComponentName(pkg, cls); }
    }

    private AppRepo() {}

    /** Системные «Настройки» в списке не нужны: доступ к ним есть сверху и снизу лаунчера. */
    private static boolean isSettings(String pkg) {
        return pkg.equals("com.android.settings") || pkg.equals("com.android.tv.settings")
                || pkg.equals("com.google.android.tvsettings") || pkg.equals("com.android.settings.intelligence");
    }

    private static boolean isWide(Drawable d) {
        return d != null && d.getIntrinsicHeight() > 0 && d.getIntrinsicWidth() > d.getIntrinsicHeight() * 1.3f;
    }

    /** Все приложения, которые можно запустить, по алфавиту. Вызывать в фоне. */
    public static List<AppItem> loadAll(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppItem> map = new HashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || map.containsKey(pkg) || isSettings(pkg)) continue;
                AppItem a = new AppItem();
                a.pkg = pkg;
                a.cls = ri.activityInfo.name;
                CharSequence l = ri.loadLabel(pm);
                a.label = l == null ? pkg : l.toString();
                try { a.icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                if (isWide(a.icon)) {          // вместо значка подставлен баннер — пробуем значок самого приложения
                    try {
                        Drawable own = ri.activityInfo.applicationInfo.loadIcon(pm);
                        if (!isWide(own)) a.icon = own;
                    } catch (Throwable ignored) {}
                }
                try { a.banner = ri.activityInfo.loadBanner(pm); } catch (Throwable ignored) {}
                map.put(pkg, a);
            }
        }
        List<AppItem> res = new ArrayList<>(map.values());
        final Collator col = Collator.getInstance();
        Collections.sort(res, new Comparator<AppItem>() {
            @Override public int compare(AppItem x, AppItem y) { return col.compare(x.label, y.label); }
        });
        return res;
    }

    /** Полный список: сначала избранные (в порядке пользователя), затем остальные по алфавиту. */
    public static List<AppItem> favFirst(List<AppItem> all, Prefs prefs) {
        Map<String, AppItem> by = new HashMap<>();
        for (AppItem a : all) by.put(a.pkg, a);
        List<AppItem> res = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String p : prefs.favs()) {
            AppItem a = by.get(p);
            if (a != null && used.add(p)) res.add(a);
        }
        for (AppItem a : all) if (used.add(a.pkg)) res.add(a);
        return res;
    }

    /** Видимые приложения в пользовательском порядке (новые — в конце, по алфавиту). */
    public static List<AppItem> visible(List<AppItem> all, Prefs prefs) {
        Set<String> hidden = prefs.hidden();
        Map<String, AppItem> byPkg = new HashMap<>();
        for (AppItem a : all) if (!hidden.contains(a.pkg)) byPkg.put(a.pkg, a);
        List<AppItem> res = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String p : prefs.order()) {
            AppItem a = byPkg.get(p);
            if (a != null && used.add(p)) res.add(a);
        }
        // новые приложения: по центру — на верхнюю строку (в начало), иначе в конец
        boolean top = prefs.gridAlign() == 1;
        int ins = 0;
        for (AppItem a : all) if (byPkg.containsKey(a.pkg) && used.add(a.pkg)) { if (top) res.add(ins++, a); else res.add(a); }
        return res;
    }

    /** Сохраняет порядок: сначала видимые в текущем порядке, затем прочие ранее сохранённые. */
    public static void saveOrder(Prefs prefs, List<AppItem> visible) {
        List<String> o = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AppItem a : visible) { o.add(a.pkg); seen.add(a.pkg); }
        for (String p : prefs.order()) if (seen.add(p)) o.add(p);
        prefs.setOrder(o);
    }
}
