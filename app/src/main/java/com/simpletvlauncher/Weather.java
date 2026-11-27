package com.simpletvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Загрузка погоды выбранным провайдером, разбор кэша (общий JSON-вид) и поиск города (OpenStreetMap Nominatim). */
public final class Weather {
    private Weather() {}

    public static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ---------- модель ----------
    public static class Pt {
        public long unix;
        public int off;                 // смещение часового пояса, минуты
        public Double t, tMin, tMax, feel, wind, windDeg, hum, press, prec;
        public String icon, desc, sunrise, sunset;
    }

    public static class Data {
        public Pt cur;
        public List<Pt> h3 = new ArrayList<>();
        public List<Pt> h24 = new ArrayList<>();
        public String city;
        public long fetched;
    }

    public interface Callback<T> {
        void ok(T result);
        void fail(String message);
    }

    // ---------- загрузка ----------
    public static void refresh(final Context ctx, final Callback<Data> cb) {
        final Prefs pr = new Prefs(ctx.getApplicationContext());
        if (!pr.hasLocation()) { cb.fail(L.t("Не выбран город")); return; }
        final double lat = pr.lat(), lon = pr.lon();
        final int prov = pr.wProvider();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    String[] r;
                    try {
                        r = prov == 0 ? WeatherProviders.metNo(lat, lon)
                                : prov == 1 ? WeatherProviders.sevenTimer(lat, lon)
                                : WeatherProviders.brightSky(lat, lon);
                    } catch (java.io.IOException io) {
                        throw new Exception(pr.providerName() + L.t(": нет связи с сервером. Выберите другой источник погоды в настройках"));
                    }
                    pr.saveWeather(r[0], r[1], r[2]);
                    final Data d = load(pr);
                    UI.post(new Runnable() { @Override public void run() { cb.ok(d); } });
                } catch (final Exception e) {
                    android.util.Log.w("GlassWeather", "refresh failed", e);
                    UI.post(new Runnable() { @Override public void run() { cb.fail(e.getMessage() == null ? L.t("Ошибка сети") : e.getMessage()); } });
                }
            }
        });
    }

    // ---------- разбор кэша ----------
    public static Data load(Prefs pr) {
        String cur = pr.weatherJson("cur");
        if (cur.isEmpty()) return null;
        try {
            Data d = new Data();
            d.fetched = pr.weatherTime();
            JSONObject root = new JSONObject(cur);
            JSONObject o = root.getJSONObject("data");
            d.cur = parsePt(o);
            JSONObject city = o.optJSONObject("city");
            d.city = city != null ? city.optString("name", pr.city()) : pr.city();
            if (d.city == null || d.city.isEmpty()) d.city = pr.city();
            d.h3 = parseList(pr.weatherJson("h3"));
            d.h24 = parseList(pr.weatherJson("h24"));
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Pt> parseList(String json) {
        List<Pt> r = new ArrayList<>();
        if (json == null || json.isEmpty()) return r;
        try {
            JSONArray a = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < a.length(); i++) r.add(parsePt(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return r;
    }

    static Pt parsePt(JSONObject o) {
        Pt p = new Pt();
        JSONObject d = o.optJSONObject("date");
        if (d != null) { p.unix = d.optLong("unix"); p.off = d.optInt("timeZoneOffset"); }
        JSONObject ic = o.optJSONObject("icon");
        if (ic != null) p.icon = ic.optString("icon-weather", "d");
        p.desc = L.desc(o.optString("description", ""));       // в кэше описание по-русски — переводим при разборе
        p.t = num(o, "temperature", "air", "C");
        p.tMax = num(o, "temperature", "air", "max", "C");
        p.tMin = num(o, "temperature", "air", "min", "C");
        if (p.t == null) p.t = num(o, "temperature", "air", "avg", "C");
        p.feel = num(o, "temperature", "comfort", "C");
        if (p.feel == null) p.feel = num(o, "temperature", "comfort", "max", "C");
        p.wind = first(num(o, "wind", "speed", "m_s"), num(o, "wind", "speed", "max", "m_s"), num(o, "wind", "speed", "avg", "m_s"));
        p.windDeg = num(o, "wind", "direction", "degree");
        p.hum = first(num(o, "humidity", "percent"), num(o, "humidity", "percent", "avg"));
        p.press = first(num(o, "pressure", "mm_hg_atm"), num(o, "pressure", "mm_hg_atm", "max"));
        p.prec = num(o, "precipitation", "amount");
        JSONObject sun = sub(sub(o, "astronomy"), "sun");
        if (sun != null) {
            p.sunrise = hhmm(sun.optString("sunrise", ""));
            p.sunset = hhmm(sun.optString("sunset", ""));
        }
        return p;
    }

    private static JSONObject sub(JSONObject o, String k) { return o == null ? null : o.optJSONObject(k); }

    private static Double first(Double... v) {
        for (Double x : v) if (x != null) return x;
        return null;
    }

    private static String hhmm(String iso) {
        // "2023-08-22T05:14:02+03:00" -> "05:14" (местное время точки)
        return iso.length() >= 16 && iso.charAt(10) == 'T' ? iso.substring(11, 16) : null;
    }

    static Double num(JSONObject o, String... path) {
        JSONObject cur = o;
        for (int i = 0; i < path.length - 1; i++) {
            if (cur == null) return null;
            cur = cur.optJSONObject(path[i]);
        }
        if (cur == null) return null;
        Object v = cur.opt(path[path.length - 1]);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    // ---------- форматирование ----------
    /** Единицы измерения: 0 — метрические (°C, м/с, мм рт. ст., мм), 1 — США (°F, mph, inHg, in), 2 — Великобритания (°C, mph, hPa, мм). Данные хранятся в метрических, пересчёт только при показе. */
    public static volatile int units = 0;

    public static String temp(Double t) {
        if (t == null) return "–";
        if (units == 1) t = t * 9.0 / 5.0 + 32.0;
        long r = Math.round(t);
        return (r > 0 ? "+" : r < 0 ? "−" : "") + Math.abs(r) + (units == 1 ? "°F" : "°C");
    }

    public static String local(Pt p, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, L.locale());
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(p.unix * 1000L + p.off * 60000L));
    }

    /** Откуда дует ветер, полным словом: «Юго-Восточный». */
    public static String windDir(Double deg) {
        if (deg == null) return "";
        String[] n = {L.t("Северный"), L.t("Северо-Восточный"), L.t("Восточный"), L.t("Юго-Восточный"), L.t("Южный"), L.t("Юго-Западный"), L.t("Западный"), L.t("Северо-Западный")};
        return n[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }

    public static String windSpeedText(Pt p) {
        if (p.wind == null) return "–";
        return units == 0 ? Math.round(p.wind) + L.t(" м/с") : Math.round(p.wind * 2.236936) + " mph";
    }

    /** Давление (в данных — мм рт. ст.). */
    public static String pressText(Double mm) {
        if (mm == null) return "–";
        if (units == 1) return String.format(java.util.Locale.US, "%.2f inHg", mm * 0.03937008);
        if (units == 2) return Math.round(mm * 1.333224) + " hPa";
        return Math.round(mm) + L.t(" мм рт. ст.");
    }

    /** Осадки (в данных — мм). */
    public static String precText(double mm) {
        if (units == 1) return String.format(java.util.Locale.US, "%.2f in", mm * 0.03937008);
        return (mm == Math.floor(mm) ? String.valueOf((long) mm) : String.format(java.util.Locale.US, "%.1f", mm)) + L.t(" мм");
    }

    public static String windDirText(Pt p) {
        return p.windDeg == null ? "–" : windDir(p.windDeg);
    }

    // ---------- поиск города ----------
    public static class Place {
        public String name, region;
        public double lat, lon;
        public String title() { return region == null || region.isEmpty() ? name : name + ", " + region; }
    }

    private static List<Place> nominatim(String q) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=10&accept-language=" + L.code + "&featuretype=settlement&q="
                + URLEncoder.encode(q, "UTF-8");
        JSONArray a = new JSONArray(WeatherProviders.http(url, null));
        List<Place> res = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < a.length() && res.size() < 8; i++) {
            JSONObject r = a.getJSONObject(i);
            JSONObject ad = r.optJSONObject("address");
            Place p = new Place();
            String nm = r.optString("name", "");
            if (nm.isEmpty() && ad != null) nm = firstNonEmpty(ad, "city", "town", "village", "hamlet");
            if (nm.isEmpty()) continue;
            p.name = nm;
            String state = ad == null ? "" : firstNonEmpty(ad, "state", "region", "county");
            String country = ad == null ? "" : ad.optString("country", "");
            p.region = state.isEmpty() ? country : (country.isEmpty() || state.equals(country) ? state : state + ", " + country);
            p.lat = r.getDouble("lat");
            p.lon = r.getDouble("lon");
            if (seen.add(p.title())) res.add(p);
        }
        return res;
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) { String v = o.optString(k, ""); if (!v.isEmpty()) return v; }
        return "";
    }

    public static void searchCity(final String query, final Callback<List<Place>> cb) {
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<Place> found = nominatim(query);
                    UI.post(new Runnable() { @Override public void run() { cb.ok(found); } });
                } catch (final Exception e) {
                    android.util.Log.w("GlassWeather", "city search failed", e);
                    UI.post(new Runnable() { @Override public void run() { cb.fail(L.t("Поиск города недоступен: ") + (e.getMessage() == null ? L.t("нет связи") : e.getMessage())); } });
                }
            }
        });
    }
}
