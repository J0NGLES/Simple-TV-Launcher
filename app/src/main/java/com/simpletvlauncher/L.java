package com.simpletvlauncher;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Язык интерфейса. Исходные тексты в коде — русские; для английского и украинского каждая фраза ищется в словаре (LDict, LDictUk).
 * Новая фраза: пишем по-русски, оборачиваем в L.t("…") и добавляем переводы в словари.
 * Язык выбирается автоматически по системе (русский, украинский, любой другой — английский) или вручную в настройках.
 */
public final class L {
    private L() {}

    /** Текущий язык: "ru", "en" или "uk". */
    public static volatile String code = "ru";
    /** Растёт при каждой смене языка: экраны, построенные раньше, по нему понимают, что пора перестроиться. */
    public static volatile int version = 0;

    private static final Map<String, String> EN = new HashMap<>(), UK = new HashMap<>();
    private static boolean loadedEn, loadedUk;

    /** Определяет язык по настройке лаунчера и системе. */
    public static void init(Context c) {
        String pref = new Prefs(c).lang();
        String nc;
        if ("ru".equals(pref) || "en".equals(pref) || "uk".equals(pref)) nc = pref;
        else {
            String sys = Locale.getDefault().getLanguage();
            nc = "ru".equalsIgnoreCase(sys) ? "ru" : "uk".equalsIgnoreCase(sys) ? "uk" : "en";
        }
        if (!nc.equals(code)) { code = nc; version++; }
    }

    public static boolean en() { return "en".equals(code); }

    public static boolean uk() { return "uk".equals(code); }

    public static Locale locale() { return en() ? Locale.ENGLISH : new Locale(code); }

    /** Перевод фразы (на русском возвращает её же). Нет перевода — тоже исходный текст. */
    public static String t(String ru) {
        if (ru == null) return null;
        Map<String, String> m;
        if (en()) {
            if (!loadedEn) { LDict.fill(EN); loadedEn = true; }
            m = EN;
        } else if (uk()) {
            if (!loadedUk) { LDictUk.fill(UK); loadedUk = true; }
            m = UK;
        } else return ru;
        String r = m.get(ru);
        return r == null ? ru : r;
    }

    /** Название в кавычках: «…» по-русски и по-украински, “…” по-английски. */
    public static String q(String s) { return en() ? "“" + s + "”" : "«" + s + "»"; }

    /** Описание погоды из кэша (оно сохранено по-русски): переводится при показе. */
    public static String desc(String ru) {
        if (ru == null || "ru".equals(code)) return ru;
        String direct = t(ru);
        if (!direct.equals(ru)) return direct;
        boolean u = uk();
        String s = ru;
        String suffix = "";
        if (s.endsWith(" с грозой")) { suffix = u ? " з грозою" : " with thunderstorm"; s = s.substring(0, s.length() - " с грозой".length()); }
        String prefix = "";
        if (s.startsWith("Небольшой ")) { prefix = u ? "Невеликий " : "Light "; s = s.substring("Небольшой ".length()); }
        else if (s.startsWith("Сильный ")) { prefix = u ? "Сильний " : "Heavy "; s = s.substring("Сильный ".length()); }
        String base;
        switch (s.toLowerCase(new Locale("ru"))) {
            case "дождь со снегом": base = u ? "дощ зі снігом" : "sleet"; break;
            case "снег": base = u ? "сніг" : "snow"; break;
            case "дождь": base = u ? "дощ" : "rain"; break;
            default: return ru;
        }
        String r = prefix.isEmpty() ? Character.toUpperCase(base.charAt(0)) + base.substring(1) : prefix + base;
        return r + suffix;
    }
}
