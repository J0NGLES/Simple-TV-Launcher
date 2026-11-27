package com.simpletvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Поиск через выбранное приложение: вместо голосового поиска системы пользователь сам решает, где искать. */
final class SearchHelper {
    private SearchHelper() {}

    static class Provider {
        String pkg, cls, action, label;
        String key() { return pkg + "|" + action; }
    }

    private static final String ASSIST = "android.intent.action.ASSIST";
    private static final String VOICE_ASSIST = "android.intent.action.VOICE_ASSIST";
    private static final String GLOBAL = "android.search.action.GLOBAL_SEARCH";

    /** Действия, которыми голосовой поиск принимает запрос (в порядке предпочтения при запуске). */
    private static final String[] LAUNCH = {
            "android.intent.action.WEB_SEARCH",
            "android.speech.action.WEB_SEARCH",
            ASSIST,
            VOICE_ASSIST,
            GLOBAL,
    };

    /** Действия, по которым ищем кандидатов. */
    private static final String[] PROBE = {
            ASSIST, VOICE_ASSIST, GLOBAL,
            "android.intent.action.WEB_SEARCH", "android.speech.action.WEB_SEARCH",
    };

    /**
     * Только приложения с голосовым поиском общего назначения (Google, «ATV Поиск», TV Search…). Признаки, проверенные
     * по настоящим поисковым клиентам: один и тот же экран принимает ASSIST (голосовой помощник) и ещё VOICE_ASSIST
     * или GLOBAL_SEARCH, и у приложения есть доступ к микрофону. Браузеры, магазины и заглушки так не объявляются.
     */
    static List<Provider> find(Context c) {
        PackageManager pm = c.getPackageManager();
        // экран (пакет/класс) -> какие действия он принимает
        Map<String, Set<String>> acts = new LinkedHashMap<>();
        Map<String, ResolveInfo> infos = new HashMap<>();
        for (String act : PROBE) {
            List<ResolveInfo> l;
            try { l = pm.queryIntentActivities(new Intent(act).addCategory(Intent.CATEGORY_DEFAULT), 0); } catch (Throwable t) { continue; }
            if (l == null) continue;
            for (ResolveInfo ri : l) {
                if (ri.activityInfo == null || !ri.activityInfo.exported || !ri.activityInfo.enabled) continue;
                String k = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                Set<String> s = acts.get(k);
                if (s == null) { s = new HashSet<>(); acts.put(k, s); infos.put(k, ri); }
                s.add(act);
            }
        }
        List<Provider> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : acts.entrySet()) {
            Set<String> s = e.getValue();
            ResolveInfo ri = infos.get(e.getKey());
            String pkg = ri.activityInfo.packageName;
            boolean system = pkg.equals("com.google.android.katniss") || pkg.equals("com.google.android.googlequicksearchbox");   // штатный поиск Android TV
            if (!system && (!s.contains(ASSIST) || !(s.contains(VOICE_ASSIST) || s.contains(GLOBAL)) || !hasMic(pm, pkg))) continue;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            Provider p = new Provider();
            p.pkg = pkg;
            p.cls = ri.activityInfo.name;
            p.action = ASSIST;
            for (String a : LAUNCH) if (s.contains(a)) { p.action = a; break; }
            try { p.label = String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm)); } catch (Throwable t) { p.label = pkg; }
            res.add(p);
        }
        java.text.Collator col = java.text.Collator.getInstance();
        java.util.Collections.sort(res, (a, b) -> col.compare(a.label, b.label));
        return res;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasMic(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null)
                for (String perm : pi.requestedPermissions) if ("android.permission.RECORD_AUDIO".equals(perm)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** Ранее выбранное приложение, если оно всё ещё умеет искать; иначе null. */
    static Provider chosen(Prefs prefs, List<Provider> all) {
        String k = prefs.searchApp();
        if (k.isEmpty()) return null;
        for (Provider p : all) if (p.key().equals(k)) return p;
        // действие могло смениться после обновления приложения — достаточно совпадения пакета
        for (Provider p : all) if (k.startsWith(p.pkg + "|")) return p;
        return null;
    }

    static void launch(Context c, Provider p) {
        Intent i = new Intent(p.action).setComponent(new ComponentName(p.pkg, p.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UI.start(c, i);
    }

    static String[] names(List<Provider> l) {
        String[] n = new String[l.size()];
        for (int i = 0; i < n.length; i++) n[i] = l.get(i).label;
        return n;
    }
}
