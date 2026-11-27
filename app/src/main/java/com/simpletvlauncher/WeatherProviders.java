package com.simpletvlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Провайдеры погоды: MET Norway, 7Timer! и DWD (Brightsky).
 * Каждый приводит ответ к тому же JSON-виду, что и Gismeteo v3, поэтому весь остальной код (кэш, разбор, экраны) общий.
 * Результат: {cur, h3 (каждые 3 часа), h24 (по дням)}.
 */
final class WeatherProviders {
    private WeatherProviders() {}

    // ------------------------------------------------------------------ вспомогательное
    static String http(String url, String[][] headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "SimpleTVLauncher/1.5 (Android TV)");
        if (headers != null) for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static void put(JSONObject o, Object v, String... path) throws Exception {
        if (v == null) return;
        JSONObject cur = o;
        for (int i = 0; i < path.length - 1; i++) {
            JSONObject n = cur.optJSONObject(path[i]);
            if (n == null) { n = new JSONObject(); cur.put(path[i], n); }
            cur = n;
        }
        cur.put(path[path.length - 1], v);
    }

    /** Точка прогноза в формате Gismeteo. */
    static JSONObject pt(long unix, int offMin, String icon, String desc, Double t, Double tMax, Double tMin, Double feel,
                         Double wind, Double deg, Double gust, Double hum, Double pressMm, Double prec, Double cloud,
                         long sunrise, long sunset) throws Exception {
        JSONObject o = new JSONObject();
        put(o, unix, "date", "unix");
        put(o, offMin, "date", "timeZoneOffset");
        put(o, icon, "icon", "icon-weather");
        put(o, desc, "description");
        put(o, t, "temperature", "air", "C");
        put(o, tMax, "temperature", "air", "max", "C");
        put(o, tMin, "temperature", "air", "min", "C");
        if (t == null && tMax != null && tMin != null) put(o, (tMax + tMin) / 2, "temperature", "air", "avg", "C");
        put(o, feel, "temperature", "comfort", "C");
        put(o, wind, "wind", "speed", "m_s");
        put(o, deg, "wind", "direction", "degree");
        put(o, gust, "wind", "gust_speed", "m_s");
        put(o, hum, "humidity", "percent");
        put(o, pressMm, "pressure", "mm_hg_atm");
        put(o, prec, "precipitation", "amount");
        put(o, cloud, "cloudiness", "percent");
        if (sunrise > 0) put(o, "0000-00-00T" + hm(sunrise, offMin), "astronomy", "sun", "sunrise");
        if (sunset > 0) put(o, "0000-00-00T" + hm(sunset, offMin), "astronomy", "sun", "sunset");
        return o;
    }

    private static String hm(long unix, int offMin) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(unix * 1000L + offMin * 60000L));
    }

    private static String wrapOne(JSONObject pt) throws Exception { return new JSONObject().put("data", pt).toString(); }

    private static String wrapList(List<JSONObject> l) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        return new JSONObject().put("data", a).toString();
    }

    private static final double HPA_TO_MM = 0.750062;

    /** Среднее значение за интервал [from, to) по почасовым данным; null, если данных нет. */
    private static Double avg(double sum, int n, double k) { return n == 0 ? null : sum / n * k; }

    static String[] metNo(double lat, double lon) throws Exception {
        String url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + String.format(Locale.US, "%.4f", lat)
                + "&lon=" + String.format(Locale.US, "%.4f", lon);
        // MET Norway требует идентифицировать приложение в User-Agent
        JSONObject root = new JSONObject(http(url, new String[][]{{"User-Agent", "SimpleTVLauncher/1.5"}}));
        JSONArray ts = root.getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        TimeZone tz = TimeZone.getDefault();
        List<JSONObject> h3 = new ArrayList<>();
        JSONObject cur = null;
        // по дням: [ключ дня] -> {max, min, иконка около полудня, unix полуночи}
        List<double[]> days = new ArrayList<>();
        List<String> dayIcon = new ArrayList<>();
        List<String> dayDesc = new ArrayList<>();
        List<String> dayKeys = new ArrayList<>();
        SimpleDateFormat kf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        kf.setTimeZone(tz);
        SimpleDateFormat hf = new SimpleDateFormat("HH", Locale.US);
        hf.setTimeZone(tz);
        long firstUnix = 0;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long ms = f.parse(e.getString("time")).getTime();
            long unix = ms / 1000;
            int off = tz.getOffset(ms) / 60000;
            JSONObject data = e.getJSONObject("data");
            JSONObject det = data.getJSONObject("instant").getJSONObject("details");
            JSONObject n1 = data.optJSONObject("next_1_hours"), n6 = data.optJSONObject("next_6_hours"), n12 = data.optJSONObject("next_12_hours");
            JSONObject nx = n1 != null ? n1 : n6 != null ? n6 : n12;
            String sym = nx == null ? "cloudy" : nx.getJSONObject("summary").optString("symbol_code", "cloudy");
            int hour = Integer.parseInt(hf.format(new Date(ms)));
            boolean day = sym.endsWith("_night") ? false : sym.endsWith("_day") ? true : hour >= 6 && hour < 22;
            Double t = det.has("air_temperature") ? det.optDouble("air_temperature") : null;
            Double prec = n1 != null && n1.optJSONObject("details") != null ? n1.getJSONObject("details").optDouble("precipitation_amount") : null;
            JSONObject p = pt(unix, off, metIcon(sym, day), metDesc(sym), t, null, null,
                    apparent(t, det.has("relative_humidity") ? det.optDouble("relative_humidity") : null,
                            det.has("wind_speed") ? det.optDouble("wind_speed") : null),
                    det.has("wind_speed") ? det.optDouble("wind_speed") : null,
                    det.has("wind_from_direction") ? det.optDouble("wind_from_direction") : null,
                    det.has("wind_speed_of_gust") ? det.optDouble("wind_speed_of_gust") : null,
                    det.has("relative_humidity") ? det.optDouble("relative_humidity") : null,
                    det.has("air_pressure_at_sea_level") ? det.optDouble("air_pressure_at_sea_level") * HPA_TO_MM : null,
                    prec, det.has("cloud_area_fraction") ? det.optDouble("cloud_area_fraction") : null, 0, 0);
            if (cur == null) { cur = p; firstUnix = unix; }
            if (h3.size() < 16 && (unix - firstUnix) % 10800 == 0) h3.add(p);
            // сводка по дням
            String key = kf.format(new Date(ms));
            int di = dayKeys.indexOf(key);
            if (di < 0) {
                dayKeys.add(key);
                days.add(new double[]{-1e9, 1e9, unix - (hour * 3600L), off, 1e9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
                dayIcon.add(null);
                dayDesc.add(null);
                di = dayKeys.size() - 1;
            }
            double[] a = days.get(di);
            if (t != null) { a[0] = Math.max(a[0], t); a[1] = Math.min(a[1], t); }
            if (det.has("relative_humidity")) { a[5] += det.optDouble("relative_humidity"); a[6]++; }
            if (det.has("air_pressure_at_sea_level")) { a[7] += det.optDouble("air_pressure_at_sea_level"); a[8]++; }
            if (det.has("wind_speed")) {
                a[9] += det.optDouble("wind_speed"); a[10]++;
                if (det.has("wind_from_direction")) {
                    double wt = Math.max(det.optDouble("wind_speed"), 0.1), rad = Math.toRadians(det.optDouble("wind_from_direction"));
                    a[15] += wt * Math.sin(rad);
                    a[16] += wt * Math.cos(rad);
                }
            }
            if (det.has("wind_speed_of_gust")) a[11] = Math.max(a[11], det.optDouble("wind_speed_of_gust"));
            if (det.has("cloud_area_fraction")) { a[12] += det.optDouble("cloud_area_fraction"); a[13]++; }
            if (prec != null && n1 != null) a[14] += prec;
            double dist = Math.abs(hour - 13);
            if (dist < a[4] && sym != null) { a[4] = dist; dayIcon.set(di, metIcon(sym, true)); dayDesc.set(di, metDesc(sym)); }
        }
        if (cur == null) throw new Exception(L.t("Пустой ответ MET Norway"));
        List<JSONObject> h24 = new ArrayList<>();
        for (int i = 0; i < days.size() && h24.size() < 10; i++) {
            double[] a = days.get(i);
            if (a[0] < -1e8) continue;
            h24.add(pt((long) a[2], (int) a[3], dayIcon.get(i), dayDesc.get(i), null, a[0], a[1],
                    apparent((a[0] + a[1]) / 2, avg(a[5], (int) a[6], 1), avg(a[9], (int) a[10], 1)),
                    avg(a[9], (int) a[10], 1), meanDir(a[15], a[16]), null, avg(a[5], (int) a[6], 1), avg(a[7], (int) a[8], HPA_TO_MM),
                    a[14] > 0 ? a[14] : null, avg(a[12], (int) a[13], 1), 0, 0));
        }
        return new String[]{wrapOne(cur), wrapList(h3), wrapList(h24)};
    }

    static String metIcon(String sym, boolean day) {
        String p = day ? "d" : "n";
        String s = sym.replaceAll("_(day|night|polartwilight)$", "");
        boolean thunder = s.contains("thunder");
        String lvl = s.startsWith("light") ? "1" : s.startsWith("heavy") ? "3" : "2";
        String base = s.replace("light", "").replace("heavy", "").replace("andthunder", "").replace("showers", "").replace("shower", "");
        StringBuilder r = new StringBuilder(p);
        if (base.equals("clearsky")) { if (thunder) r.append("_c4_r2_st"); return r.toString(); }
        if (base.equals("fair")) return r.append("_c1").toString();
        if (base.equals("partlycloudy")) return r.append("_c2").toString();
        if (base.equals("cloudy")) return r.append("_c4").toString();
        if (base.equals("fog")) return r.append("_c3").toString();
        r.append(thunder || lvl.equals("3") ? "_c4" : "_c3");
        if (base.contains("sleet")) r.append("_rs").append(lvl);
        else if (base.contains("snow")) r.append("_s").append(lvl);
        else r.append("_r").append(lvl);
        if (thunder) r.append("_st");
        return r.toString();
    }

    static String metDesc(String sym) {
        String s = sym.replaceAll("_(day|night|polartwilight)$", "");
        boolean thunder = s.contains("thunder");
        String lvl = s.startsWith("light") ? "Небольшой " : s.startsWith("heavy") ? "Сильный " : "";
        String base = s.replace("light", "").replace("heavy", "").replace("andthunder", "").replace("showers", "").replace("shower", "");
        String r;
        switch (base) {
            case "clearsky": return thunder ? "Гроза" : "Ясно";
            case "fair": return "Малооблачно";
            case "partlycloudy": return "Переменная облачность";
            case "cloudy": return "Пасмурно";
            case "fog": return "Туман";
            default:
                if (base.contains("sleet")) r = lvl + "дождь со снегом";
                else if (base.contains("snow")) r = lvl + "снег";
                else r = lvl + "дождь";
        }
        r = r.substring(0, 1).toUpperCase(new Locale("ru")) + r.substring(1);
        return thunder ? r + " с грозой" : r;
    }

    /** «Ощущается как» по формуле кажущейся температуры (Steadman): температура, влажность %, ветер м/с. */
    static Double apparent(Double t, Double rh, Double ws) {
        if (t == null || rh == null || ws == null) return null;
        double e = rh / 100.0 * 6.105 * Math.exp(17.27 * t / (237.7 + t));
        return t + 0.33 * e - 0.70 * ws - 4.0;
    }


    // ------------------------------------------------------------------ 7Timer!
    private static final double[] SEVEN_WIND = {0, 0.2, 1.9, 5.7, 9.4, 14.0, 20.9, 28.5, 34.0};
    private static final double[] SEVEN_PREC = {0, 0.1, 0.6, 2.5, 7, 13, 23, 40, 62, 80};

    static String[] sevenTimer(double lat, double lon) throws Exception {
        String url = "https://www.7timer.info/bin/api.pl?product=civil&output=json&unit=metric&lat=" + String.format(Locale.US, "%.3f", lat)
                + "&lon=" + String.format(Locale.US, "%.3f", lon);
        JSONObject root = new JSONObject(http(url, null));
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHH", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long init = f.parse(root.getString("init")).getTime() / 1000;
        TimeZone tz = TimeZone.getDefault();
        JSONArray ds = root.getJSONArray("dataseries");
        long now = System.currentTimeMillis() / 1000;
        List<JSONObject> h3 = new ArrayList<>();
        List<double[]> dayAgg = new ArrayList<>();      // max, min, sumWind, nWind, sumHum, nHum, sumCloud, nCloud, midnight, off, iconIdx
        List<String> dayIcon = new ArrayList<>(), dayDesc = new ArrayList<>(), dayKeys = new ArrayList<>();
        SimpleDateFormat kf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        kf.setTimeZone(tz);
        JSONObject cur = null;
        for (int i = 0; i < ds.length(); i++) {
            JSONObject e = ds.getJSONObject(i);
            long unix = init + e.getInt("timepoint") * 3600L;
            long ms = unix * 1000L;
            int off = tz.getOffset(ms) / 60000;
            int hour = (int) (((unix + off * 60L) / 3600) % 24);
            String w = e.optString("weather", "cloudyday");
            boolean day = !w.endsWith("night");
            double t = e.optDouble("temp2m");
            String rhs = e.optString("rh2m", "").replace("%", "");
            Double rh = null;
            try { double v = Double.parseDouble(rhs); if (v >= 0) rh = v; } catch (Exception ignored) {}
            JSONObject wo = e.optJSONObject("wind10m");
            int sp = wo == null ? 0 : Math.max(0, Math.min(8, wo.optInt("speed")));
            double ws = SEVEN_WIND[sp];
            Double deg = wo == null ? null : compass(wo.optString("direction"));
            double cloud = Math.max(0, (e.optInt("cloudcover", 1) - 1) * 12.5);
            String icon = sevenIcon(w, day), desc = sevenDesc(w);
            int pa = Math.max(0, Math.min(9, e.optInt("prec_amount", 0)));
            Double prec = pa > 0 ? SEVEN_PREC[pa] * 3 : null;       // интенсивность в мм/ч (середина диапазона) за 3 часа
            JSONObject p = pt(unix, off, icon, desc, t, null, null, apparent(t, rh, ws), ws, deg, null, rh, null, prec, cloud, 0, 0);
            if (unix + 10800 > now && cur == null) cur = p;
            if (unix + 5400 >= now && h3.size() < 16) h3.add(p);
            String key = kf.format(new Date(ms));
            int di = dayKeys.indexOf(key);
            if (di < 0) {
                dayKeys.add(key);
                dayAgg.add(new double[]{-1e9, 1e9, 0, 0, 0, 0, 0, 0, unix - (unix + off * 60L) % 86400, off, 1e9, 0, 0, 0});
                dayIcon.add(null);
                dayDesc.add(null);
                di = dayKeys.size() - 1;
            }
            double[] a = dayAgg.get(di);
            a[0] = Math.max(a[0], t); a[1] = Math.min(a[1], t);
            a[2] += ws; a[3]++;
            if (deg != null) {
                double wt = Math.max(ws, 0.1);
                a[12] += wt * Math.sin(Math.toRadians(deg));
                a[13] += wt * Math.cos(Math.toRadians(deg));
            }
            if (rh != null) { a[4] += rh; a[5]++; }
            a[6] += cloud; a[7]++;
            if (prec != null) a[11] += prec;
            double dist = Math.abs(hour - 13);
            if (dist < a[10]) { a[10] = dist; dayIcon.set(di, sevenIcon(w, true)); dayDesc.set(di, desc); }
        }
        if (cur == null) throw new Exception(L.t("Пустой ответ 7Timer"));
        List<JSONObject> h24 = new ArrayList<>();
        for (int i = 0; i < dayAgg.size() && h24.size() < 8; i++) {
            double[] a = dayAgg.get(i);
            h24.add(pt((long) a[8], (int) a[9], dayIcon.get(i), dayDesc.get(i), null, a[0], a[1],
                    apparent((a[0] + a[1]) / 2, avg(a[4], (int) a[5], 1), avg(a[2], (int) a[3], 1)),
                    avg(a[2], (int) a[3], 1), meanDir(a[12], a[13]), null, avg(a[4], (int) a[5], 1), null, a[11] > 0 ? a[11] : null, avg(a[6], (int) a[7], 1), 0, 0));
        }
        return new String[]{wrapOne(cur), wrapList(h3), wrapList(h24)};
    }

    private static Double compass(String d) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < n.length; i++) if (n[i].equals(d)) return i * 45.0;
        return null;
    }

    static String sevenIcon(String w, boolean day) {
        String p = day ? "d" : "n";
        String s = w.replaceAll("(day|night)$", "");
        switch (s) {
            case "clear": return p;
            case "pcloudy": return p + "_c2";
            case "mcloudy": return p + "_c3";
            case "cloudy": return p + "_c4";
            case "humid": return p + "_c3";
            case "lightrain": return p + "_c3_r1";
            case "oshower": return p + "_c3_r2";
            case "ishower": return p + "_c4_r2";
            case "lightsnow": return p + "_c3_s1";
            case "rain": return p + "_c4_r2";
            case "snow": return p + "_c4_s2";
            case "rainsnow": return p + "_c4_rs2";
            case "ts": return p + "_c4_r2_st";
            case "tsrain": return p + "_c4_r3_st";
            default: return p + "_c2";
        }
    }

    static String sevenDesc(String w) {
        String s = w.replaceAll("(day|night)$", "");
        switch (s) {
            case "clear": return "Ясно";
            case "pcloudy": return "Переменная облачность";
            case "mcloudy": return "Облачно";
            case "cloudy": return "Пасмурно";
            case "humid": return "Влажно, дымка";
            case "lightrain": return "Небольшой дождь";
            case "oshower": return "Местами дождь";
            case "ishower": return "Ливень";
            case "lightsnow": return "Небольшой снег";
            case "rain": return "Дождь";
            case "snow": return "Снег";
            case "rainsnow": return "Дождь со снегом";
            case "ts": return "Гроза";
            case "tsrain": return "Гроза с дождём";
            default: return "";
        }
    }


    // ------------------------------------------------------------------ DWD через Brightsky (ключ не нужен)
    private static Double dn(JSONObject r, String k) {
        return !r.has(k) || r.isNull(k) ? null : r.optDouble(k);
    }

    /** Среднее направление ветра по сумме векторов (вес — скорость), градусы 0..360; null, если данных нет. */
    static Double meanDir(double sx, double sy) {
        if (Math.abs(sx) < 1e-9 && Math.abs(sy) < 1e-9) return null;
        double d = Math.toDegrees(Math.atan2(sx, sy));
        return d < 0 ? d + 360 : d;
    }

    /** Значок и описание по значку Brightsky, облачности и осадкам за час. */
    private static String[] bsLook(String icon, Double cloud, Double prec, boolean day) {
        String p = day ? "d" : "n";
        double pr = prec == null ? 1 : prec;
        String lvl = pr < 0.5 ? "1" : pr < 4 ? "2" : "3";
        String lw = lvl.equals("1") ? "Небольшой " : lvl.equals("3") ? "Сильный " : "";
        String c = lvl.equals("1") ? "_c3" : "_c4";
        switch (icon) {
            case "thunderstorm": return new String[]{p + "_c4_r2_st", "Гроза"};
            case "rain": return new String[]{p + c + "_r" + lvl, lw + "дождь"};
            case "snow": return new String[]{p + c + "_s" + lvl, lw + "снег"};
            case "sleet": return new String[]{p + "_c4_rs2", "Дождь со снегом"};
            case "hail": return new String[]{p + "_c4_r3", "Град"};
            case "fog": return new String[]{p + "_c3", "Туман"};
        }
        double cl = cloud != null ? cloud : icon.startsWith("clear") ? 0 : icon.startsWith("partly") ? 50 : 100;
        if (cl < 12) return new String[]{p, "Ясно"};
        if (cl < 37) return new String[]{p + "_c1", "Малооблачно"};
        if (cl < 62) return new String[]{p + "_c2", "Переменная облачность"};
        if (cl < 87) return new String[]{p + "_c3", "Облачно"};
        return new String[]{p + "_c4", "Пасмурно"};
    }

    static String[] brightSky(double lat, double lon) throws Exception {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        long nowMs = System.currentTimeMillis();
        String url = "https://api.brightsky.dev/weather?lat=" + String.format(Locale.US, "%.4f", lat) + "&lon=" + String.format(Locale.US, "%.4f", lon)
                + "&date=" + df.format(new Date(nowMs - 3600000L)) + "&last_date=" + df.format(new Date(nowMs + 9 * 86400000L)) + "&max_dist=150000";
        JSONArray ws = new JSONObject(http(url, null)).getJSONArray("weather");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        TimeZone tz = TimeZone.getDefault();
        SimpleDateFormat kf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        kf.setTimeZone(tz);
        SimpleDateFormat hf = new SimpleDateFormat("HH", Locale.US);
        hf.setTimeZone(tz);
        // ближайшая к текущему моменту запись (не позже сейчас)
        int start = 0;
        for (int i = 0; i < ws.length(); i++) {
            long ms = f.parse(ws.getJSONObject(i).getString("timestamp").substring(0, 19)).getTime();
            if (ms <= nowMs) start = i;
        }
        if (ws.length() == 0) throw new Exception(L.t("Нет данных DWD для этой точки"));
        JSONObject cur = null;
        List<JSONObject> h3 = new ArrayList<>();
        List<double[]> days = new ArrayList<>();
        List<String> dayIcon = new ArrayList<>(), dayDesc = new ArrayList<>(), dayKeys = new ArrayList<>();
        for (int i = start; i < ws.length(); i++) {
            JSONObject r = ws.getJSONObject(i);
            long ms = f.parse(r.getString("timestamp").substring(0, 19)).getTime();
            long unix = ms / 1000;
            int off = tz.getOffset(ms) / 60000;
            int hour = Integer.parseInt(hf.format(new Date(ms)));
            String ic = r.optString("icon", "cloudy");
            boolean day = ic.endsWith("night") ? false : ic.endsWith("day") ? true : hour >= 6 && hour < 22;
            Double t = dn(r, "temperature"), hum = dn(r, "relative_humidity"), kmh = dn(r, "wind_speed"), deg = dn(r, "wind_direction");
            Double td = dn(r, "dew_point");
            // в прогнозе DWD влажности нет, зато есть точка росы: считаем влажность по ней (формула Магнуса)
            if (hum == null && t != null && td != null) {
                hum = Math.min(100.0, 100.0 * Math.exp(17.625 * td / (243.04 + td)) / Math.exp(17.625 * t / (243.04 + t)));
            }
            Double w = kmh == null ? null : kmh / 3.6;
            Double cloud = dn(r, "cloud_cover"), press = dn(r, "pressure_msl");
            double p3 = 0;
            boolean anyP = false;
            for (int k = i; k < i + 3 && k < ws.length(); k++) {
                Double pv = dn(ws.getJSONObject(k), "precipitation");
                if (pv != null) { p3 += pv; anyP = true; }
            }
            Double p1 = dn(r, "precipitation");
            String[] look = bsLook(ic, cloud, p1 != null ? p1 : (anyP ? p3 / 3 : null), day);
            JSONObject pnt = pt(unix, off, look[0], look[1], t, null, null, apparent(t, hum, w), w, deg, null, hum,
                    press == null ? null : press * HPA_TO_MM, i == start ? p1 : (anyP && p3 > 0 ? p3 : null), cloud, 0, 0);
            if (cur == null) cur = pnt;
            if (h3.size() < 16 && (i - start) % 3 == 0) h3.add(pnt);
            String key = kf.format(new Date(ms));
            int di = dayKeys.indexOf(key);
            if (di < 0) {
                dayKeys.add(key);
                days.add(new double[]{-1e9, 1e9, unix - hour * 3600L, off, 1e9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
                dayIcon.add(null);
                dayDesc.add(null);
                di = dayKeys.size() - 1;
            }
            double[] a = days.get(di);
            if (t != null) { a[0] = Math.max(a[0], t); a[1] = Math.min(a[1], t); }
            if (hum != null) { a[5] += hum; a[6]++; }
            if (press != null) { a[7] += press; a[8]++; }
            if (w != null) {
                a[9] += w; a[10]++;
                if (deg != null) {
                    double wt = Math.max(w, 0.1);
                    a[15] += wt * Math.sin(Math.toRadians(deg));
                    a[16] += wt * Math.cos(Math.toRadians(deg));
                }
            }
            if (cloud != null) { a[12] += cloud; a[13]++; }
            if (p1 != null) a[14] += p1;
            double dist = Math.abs(hour - 13);
            if (dist < a[4]) {
                a[4] = dist;
                String[] dl = bsLook(ic, cloud, p1, true);
                dayIcon.set(di, dl[0]);
                dayDesc.set(di, dl[1]);
            }
        }
        if (cur == null) throw new Exception(L.t("Пустой ответ DWD"));
        List<JSONObject> h24 = new ArrayList<>();
        for (int i = 0; i < days.size() && h24.size() < 10; i++) {
            double[] a = days.get(i);
            if (a[0] < -1e8) continue;
            h24.add(pt((long) a[2], (int) a[3], dayIcon.get(i), dayDesc.get(i), null, a[0], a[1],
                    apparent((a[0] + a[1]) / 2, avg(a[5], (int) a[6], 1), avg(a[9], (int) a[10], 1)),
                    avg(a[9], (int) a[10], 1), meanDir(a[15], a[16]), null, avg(a[5], (int) a[6], 1), avg(a[7], (int) a[8], HPA_TO_MM),
                    a[14] > 0 ? a[14] : null, avg(a[12], (int) a[13], 1), 0, 0));
        }
        return new String[]{wrapOne(cur), wrapList(h3), wrapList(h24)};
    }
}
