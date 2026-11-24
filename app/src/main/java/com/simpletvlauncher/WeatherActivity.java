package com.simpletvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Отдельный экран погоды в том же стеклянном стиле. Данные — Gismeteo API v3. */
public class WeatherActivity extends Activity {
    private Prefs prefs;
    private float s;
    private int W, H;
    private FrameLayout root, content;
    private TextView titleTv, subTv;
    private GlassView btnRefresh, btnSettings, detail;
    private Weather.Pt selected;
    private Weather.Data data;
    private boolean loading;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        s = UI.scale(this);
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;

        root = new FrameLayout(this);
        root.addView(new BgView(this, prefs.settingsAccent()), new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        titleTv = UI.text(this, L.t("Погода"), 62 * s, 0xFFFFFFFF, GlassView.MED);
        put(root, titleTv, 96, 40, 1000, 84);
        subTv = UI.text(this, "", 26 * s, 0xA6FFFFFF, GlassView.REG);
        put(root, subTv, 96, 124, 1100, 40);

        btnSettings = button(L.t("Настройки"), Icons.TUNE);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class).putExtra("weather", true)));
        put(root, btnSettings, (1920 - 96 - 330), 48, 330, 80);
        btnRefresh = button(L.t("Обновить"), Icons.REFRESH);
        btnRefresh.setOnClickListener(v -> load(true));
        put(root, btnRefresh, (1920 - 96 - 330 - 20 - 330), 48, 330, 80);
        setContentView(root);
    }

    private int langV = L.version;

    @Override protected void onResume() {
        super.onResume();
        if (L.version != langV) { recreate(); return; }      // язык сменили, пока экран был закрыт
        boolean kid = MainActivity.kidLocked(prefs);       // режим для бабушек: кнопки «Настройки» нет, «Обновить» занимает её место
        btnSettings.setVisibility(kid ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams rl = (FrameLayout.LayoutParams) btnRefresh.getLayoutParams();
        rl.leftMargin = Math.round((1920 - 96 - 330 - (kid ? 0 : 20 + 330)) * s);
        btnRefresh.setLayoutParams(rl);
        data = Weather.load(prefs);
        if (!prefs.hasLocation()) {
            showMessage(L.t("Не выбран город"),
                    L.t("Откройте настройки лаунчера → «Погода»: выберите источник и город."));
            (kid ? btnRefresh : btnSettings).requestFocus();
            return;
        }
        if (data != null) show(data);
        else showMessage(L.t("Загрузка…"), L.t("Запрашиваю прогноз: ") + prefs.providerName());
        if (data == null || System.currentTimeMillis() - data.fetched > 10 * 60 * 1000L) load(false);
    }

    private GlassView button(String label, int icon) {
        GlassView g = new GlassView(this);
        g.left = true;
        g.label = label;
        g.icon = icon;
        g.radius = 40 * s;
        g.focusScale = 1.05f;
        return g;
    }

    private void put(FrameLayout parent, View v, float x, float y, float w, float hh) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * s), Math.round(hh * s));
        lp.leftMargin = Math.round(x * s);
        lp.topMargin = Math.round(y * s);
        parent.addView(v, lp);
    }

    private GlassView panel(float radius) {
        GlassView g = new GlassView(this);
        g.setFocusable(false);
        g.setClickable(false);
        g.radius = radius * s;
        return g;
    }

    // ---------------------------------------------------------------- загрузка
    private void load(final boolean userAction) {
        if (loading) return;
        loading = true;
        subTv.setText(L.t("Обновление…"));
        Weather.refresh(this, new Weather.Callback<Weather.Data>() {
            @Override public void ok(Weather.Data d) {
                loading = false;
                data = d;
                show(d);
            }
            @Override public void fail(String m) {
                loading = false;
                if (data == null) showMessage(L.t("Не удалось получить погоду"), m);
                else {
                    subTv.setText(L.t("Ошибка обновления: ") + m);
                }
            }
        });
    }

    private void showMessage(String head, final String text) {
        content.removeAllViews();
        titleTv.setText(L.t("Погода"));
        GlassView p = panel(36);
        p.painter = (c, v) -> {
            float hh = v.getHeight(), w = v.getWidth();
            Icons.weather(c, "d_c2", hh * 0.2f + 20, hh * 0.5f, hh * 0.18f, v.tp);
            v.text(c, head, hh * 0.55f, hh * 0.45f, hh * 0.16f, 0xFFFFFFFF, 2, Paint.Align.LEFT, w - hh * 0.7f);
            drawWrapped(c, v, text, hh * 0.55f, hh * 0.62f, w - hh * 0.8f, hh * 0.09f);
        };
        put(content, p, 96, 330, 1728, 360);
    }

    private void drawWrapped(Canvas c, GlassView v, String text, float x, float y, float maxW, float size) {
        v.tp.setTextSize(size * UI.fs);
        v.tp.setTypeface(GlassView.REG);
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float yy = y;
        for (String w : words) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (v.tp.measureText(t) > maxW && line.length() > 0) {
                v.text(c, line.toString(), x, yy, size, 0xB8FFFFFF, 1, Paint.Align.LEFT, 0);
                yy += size * 1.4f;
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0) v.text(c, line.toString(), x, yy, size, 0xB8FFFFFF, 1, Paint.Align.LEFT, 0);
    }

    // ---------------------------------------------------------------- экран с данными
    private void show(final Weather.Data d) {
        content.removeAllViews();
        titleTv.setText(d.city == null || d.city.isEmpty() ? L.t("Погода") : d.city);
        subTv.setText(L.t("Обновлено в ") + new java.text.SimpleDateFormat(prefs.clock12() ? "h:mm a" : "HH:mm", prefs.clock12() ? java.util.Locale.US : java.util.Locale.getDefault()).format(new java.util.Date(d.fetched))
                + "  ·  " + prefs.providerName());

        final Weather.Pt cur = d.cur;
        // --- текущая погода
        GlassView curP = panel(36);
        curP.painter = (c, v) -> {
            float hh = v.getHeight(), w = v.getWidth();
            Icons.weather(c, cur.icon, hh * 0.3f, hh * 0.4f, hh * 0.2f, v.tp);
            v.text(c, Weather.temp(cur.t), hh * 0.62f, hh * 0.5f, hh * 0.4f, 0xFFFFFFFF, 2, Paint.Align.LEFT, 0);
            v.text(c, cur.desc, hh * 0.1f, hh * 0.75f, hh * 0.1f, 0xF2FFFFFF, 1, Paint.Align.LEFT, w - hh * 0.2f);
            String feel = cur.feel == null ? "" : L.t("Ощущается как ") + Weather.temp(cur.feel);
            v.text(c, feel, hh * 0.1f, hh * 0.9f, hh * 0.085f, 0xA6FFFFFF, 1, Paint.Align.LEFT, w - hh * 0.2f);
        };
        put(content, curP, 96, 190, 760, 300);

        // --- подробности выбранного дня
        detail = panel(36);
        selected = cur;
        detail.painter = (c, v) -> paintDetail(c, v, selected, d);
        put(content, detail, 880, 190, 944, 300);

        // --- ближайшие часы
        TextView hl = UI.text(this, L.t("Ближайшие часы"), 30 * s, 0xCCFFFFFF, GlassView.MED);
        put(content, hl, 96, 506, 800, 40 + 40 * (UI.fs - 1f));
        List<Weather.Pt> hours = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (Weather.Pt p : d.h3) if (p.unix >= now - 5400 && hours.size() < 8) hours.add(p);
        int hn = Math.max(1, hours.size());
        float hw = (1728 - (hn - 1) * 16) / (float) hn;
        for (int i = 0; i < hours.size(); i++) {
            final Weather.Pt p = hours.get(i);
            GlassView g = panel(26);
            g.painter = (c, v) -> {
                float hh = v.getHeight(), w = v.getWidth();
                v.text(c, Weather.local(p, prefs.clock12() ? "h:mm a" : "HH:mm"), w / 2f, hh * 0.2f, hh * 0.13f, 0xB8FFFFFF, 1, Paint.Align.CENTER, 0);
                Icons.weather(c, p.icon, w / 2f, hh * 0.41f, hh * 0.165f, v.tp);
                v.text(c, Weather.temp(p.t), w / 2f, hh * 0.8f, hh * 0.19f, 0xFFFFFFFF, 2, Paint.Align.CENTER, 0);
            };
            put(content, g, 96 + i * (hw + 16), 552, hw, 176);
        }

        // --- по дням
        TextView dl = UI.text(this, L.t("Прогноз по дням"), 30 * s, 0xCCFFFFFF, GlassView.MED);
        put(content, dl, 96, 748, 800, 40 + 40 * (UI.fs - 1f));
        List<Weather.Pt> days = new ArrayList<>();
        for (Weather.Pt p : d.h24) if (p.unix >= now - 86400 && days.size() < 10) days.add(p);
        View firstDay = null;
        if (days.isEmpty()) {
            TextView none = UI.text(this, L.t("Дневной прогноз недоступен для этого источника или точки"), 28 * s, 0x99FFFFFF, GlassView.REG);
            put(content, none, 96, 810, 1500, 50);
        }
        int dn = Math.max(1, days.size());
        float dw = (1728 - (dn - 1) * 16) / (float) dn;
        final String todayKey = Weather.local(cur, "yyyyMMdd");
        for (int i = 0; i < days.size(); i++) {
            final Weather.Pt p = days.get(i);
            final boolean today = Weather.local(p, "yyyyMMdd").equals(todayKey);
            GlassView g = new GlassView(this);
            g.radius = 26 * s;
            g.focusScale = 1.06f;
            g.painter = (c, v) -> {
                float hh = v.getHeight(), w = v.getWidth();
                String wd = today ? L.t("Сегодня") : cap(Weather.local(p, "EEE"));
                v.text(c, wd, w / 2f, hh * 0.15f, hh * 0.115f, 0xFFFFFFFF, 2, Paint.Align.CENTER, w * 0.94f);
                v.text(c, Weather.local(p, "d MMM"), w / 2f, hh * 0.27f, hh * 0.085f, 0x99FFFFFF, 1, Paint.Align.CENTER, w * 0.94f);
                Icons.weather(c, p.icon, w / 2f, hh * 0.45f, hh * 0.15f, v.tp);
                Double hi = p.tMax != null ? p.tMax : p.t, lo = p.tMin;
                v.text(c, Weather.temp(hi), w / 2f, hh * 0.77f, hh * 0.15f, 0xFFFFFFFF, 2, Paint.Align.CENTER, w * 0.94f);
                v.text(c, Weather.temp(lo), w / 2f, hh * 0.9f, hh * 0.105f, 0x99FFFFFF, 1, Paint.Align.CENTER, w * 0.94f);
            };
            g.setOnFocusChangeListener((v, has) -> {
                if (has) { selected = p; detail.invalidate(); }
            });
            put(content, g, 96 + i * (dw + 16), 792 + 80 * (UI.fs - 1f), dw, 232);
            if (firstDay == null) firstDay = g;
        }
        if (firstDay != null) firstDay.requestFocus(); else btnRefresh.requestFocus();
    }

    private void paintDetail(Canvas c, GlassView v, Weather.Pt p, Weather.Data d) {
        float hh = v.getHeight(), w = v.getWidth();
        boolean isCur = p == d.cur;
        String head = isCur ? L.t("Сейчас") : cap(Weather.local(p, L.en() ? "EEEE, MMMM d" : "EEEE, d MMMM"));
        v.text(c, head, hh * 0.1f, hh * 0.17f, hh * 0.115f, 0xFFFFFFFF, 2, Paint.Align.LEFT, w * 0.6f);
        String tr = isCur ? Weather.temp(p.t) : Weather.temp(p.tMax != null ? p.tMax : p.t) + " / " + Weather.temp(p.tMin);
        v.text(c, tr, w - hh * 0.1f, hh * 0.17f, hh * 0.115f, 0xFFFFFFFF, 2, Paint.Align.RIGHT, 0);
        v.text(c, p.desc, hh * 0.1f, hh * 0.31f, hh * 0.085f, 0xB8FFFFFF, 1, Paint.Align.LEFT, w - hh * 0.2f);

        String[][] items = {
                {L.t("Ощущается как"), Weather.temp(p.feel)},
                {L.t("Скорость ветра"), Weather.windSpeedText(p)},
                {L.t(new Prefs(this).fontSize() >= 2 ? "Направление" : "Направление ветра"), Weather.windDirText(p)},
                {L.t("Влажность"), p.hum == null ? "–" : Math.round(p.hum) + " %"},
                {L.t("Давление"), Weather.pressText(p.press)},
                {L.t("Осадки"), p.prec == null || p.prec < 0.05 ? L.t("нет") : Weather.precText(p.prec)},
        };
        // правый блок сдвинут вправо, а значения левого — отодвинуты от него: длинное «Северо-Восточный» не упирается в подписи
        float col1X = hh * 0.1f + (w - hh * 0.2f) / 2f + hh * 0.2f;
        for (int i = 0; i < items.length; i++) {
            int col = i / 3, row = i % 3;
            float x = col == 0 ? hh * 0.1f : col1X, y = hh * (0.5f + row * 0.15f);
            float right = col == 0 ? col1X - hh * 0.14f : w - hh * 0.1f;
            v.text(c, items[i][0], x, y, hh * 0.075f, 0x99FFFFFF, 1, Paint.Align.LEFT, 0);
            v.text(c, items[i][1], right, y, hh * 0.085f, 0xFFFFFFFF, 1, Paint.Align.RIGHT, 0);
        }
        String sun = "";
        Weather.Pt sp = d.cur;
        if (sp.sunrise != null && sp.sunset != null) sun = L.t("Восход ") + sp.sunrise + L.t("  ·  Закат ") + sp.sunset;
        else if (p.sunrise != null && p.sunset != null) sun = L.t("Восход ") + p.sunrise + L.t("  ·  Закат ") + p.sunset;
        if (!sun.isEmpty()) v.text(c, sun, hh * 0.1f, hh * 0.95f, hh * 0.075f, 0x99FFFFFF, 1, Paint.Align.LEFT, w - hh * 0.2f);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.US, "%.1f", v);
    }
}
