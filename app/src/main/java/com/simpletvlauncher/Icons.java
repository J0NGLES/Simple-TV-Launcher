package com.simpletvlauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Все иконки рисуются кодом — никаких внешних ресурсов. */
public final class Icons {
    public static final int GEAR = 1, WIFI = 2, ETH = 3, GRID = 5, LIST = 6, TUNE = 7,
            ETH_NOINET = 30, WIFI_OFF = 8, REFRESH = 10, IMAGE = 14, INPUT = 15, VPN = 16, SEARCH = 17, LOCK = 18,
            SRC_HDMI = 20, SRC_DP = 21, SRC_AV = 22, SRC_SVIDEO = 23, SRC_COMPONENT = 24,
            SRC_VGA = 25, SRC_DVI = 26, SRC_SCART = 27, SRC_TUNER = 28, EYE = 31;

    private Icons() {}

    static android.graphics.Bitmap ethBmp, srcBmp, refreshBmp, vpnBmp, gearBmp;
    private static float vpnShift = Float.NaN;     // на сколько ширины значка непрозрачная часть смещена от центра картинки

    private static float shiftOf(android.graphics.Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), lo = w, hi = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) if ((row[x] >>> 24) > 40) { if (x < lo) lo = x; if (x > hi) hi = x; }
        }
        return hi < lo ? 0f : ((lo + hi + 1) / 2f - w / 2f) / w;
    }

    /** Значки хранятся как векторные ресурсы (в несколько раз легче PNG); один раз рисуем их в картинку с запасом под 4K и смягчение углов (крупнее не нужно: лишние мегабайты ОЗУ), дальше всё как раньше. */
    private static android.graphics.Bitmap vector(android.content.Context c, int res, int w, int h) {
        try {
            android.graphics.drawable.Drawable d = c.getResources().getDrawable(res, null);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            return b;
        } catch (Throwable t) { return null; }
    }

    public static void init(android.content.Context c) {
        if (refreshBmp == null) refreshBmp = vector(c, R.drawable.ic_refresh, 200, 200);
        if (gearBmp == null) gearBmp = vector(c, R.drawable.ic_gear, 192, 192);
        if (vpnBmp == null) vpnBmp = vector(c, R.drawable.ic_vpn, 320, 174);
        if (srcBmp == null) srcBmp = vector(c, R.drawable.ic_src, 160, 149);
        if (ethBmp == null) ethBmp = vector(c, R.drawable.ic_eth, 208, 176);
    }

    // ---------- гладкая отрисовка растровых значков ----------
    private static final java.util.HashMap<String, android.graphics.Bitmap> SCALED = new java.util.HashMap<>();

    /** Уменьшение вдвое, пока не подойдёт размер: усреднение по площади, без «лесенки» от одного резкого сжатия. */
    private static android.graphics.Bitmap down(android.graphics.Bitmap src, int w, int h) {
        android.graphics.Bitmap cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = android.graphics.Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
        }
        if (cur.getWidth() != w || cur.getHeight() != h) cur = android.graphics.Bitmap.createScaledBitmap(cur, w, h, true);
        return cur;
    }

    /** Размытие по альфа-каналу прямоугольным окном (за краями — пусто). */
    private static void boxBlur(float[] a, int W, int H, int R) {
        float[] tmp = new float[Math.max(W, H) + 1];
        float inv = 1f / (2 * R + 1);
        for (int y = 0; y < H; y++) {
            float sum = 0;
            for (int x = 0; x <= R && x < W; x++) sum += a[y * W + x];
            for (int x = 0; x < W; x++) {
                tmp[x] = sum * inv;
                int add = x + R + 1, rem = x - R;
                if (add < W) sum += a[y * W + add];
                if (rem >= 0) sum -= a[y * W + rem];
            }
            for (int x = 0; x < W; x++) a[y * W + x] = tmp[x];
        }
        for (int x = 0; x < W; x++) {
            float sum = 0;
            for (int y = 0; y <= R && y < H; y++) sum += a[y * W + x];
            for (int y = 0; y < H; y++) {
                tmp[y] = sum * inv;
                int add = y + R + 1, rem = y - R;
                if (add < H) sum += a[add * W + x];
                if (rem >= 0) sum -= a[rem * W + x];
            }
            for (int y = 0; y < H; y++) a[y * W + x] = tmp[y];
        }
    }

    /** Скругляет острые углы значка: рисуем в 4-кратном размере, размываем, возвращаем чёткий край и сжимаем до нужного. */
    private static android.graphics.Bitmap soften(android.graphics.Bitmap src, int w, int h) {
        int S = 4, W = w * S, H = h * S;
        android.graphics.Bitmap big = down(src, W, H);
        int[] px = new int[W * H];
        big.getPixels(px, 0, W, 0, 0, W, H);
        float[] a = new float[W * H];
        for (int i = 0; i < a.length; i++) a[i] = (px[i] >>> 24) / 255f;
        boxBlur(a, W, H, Math.max(2, Math.round(W * 0.03f)));
        boxBlur(a, W, H, Math.max(2, Math.round(W * 0.02f)));
        for (int i = 0; i < a.length; i++) {
            float v = Math.max(0f, Math.min(1f, (a[i] - 0.5f) * 3.2f + 0.5f));
            px[i] = (Math.round(v * 255) << 24) | 0xFFFFFF;
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, W, 0, 0, W, H);
        return down(out, w, h);
    }

    private static void drawBmp(Canvas c, android.graphics.Bitmap src, float cx, float cy, float w, float h, Paint p, int color, boolean soft) {
        int wi = Math.max(1, Math.round(w)), hi = Math.max(1, Math.round(h));
        String k = System.identityHashCode(src) + ":" + wi + "x" + hi + (soft ? "s" : "");
        android.graphics.Bitmap b;
        synchronized (SCALED) {
            b = SCALED.get(k);
            if (b == null) {
                b = soft ? soften(src, wi, hi) : down(src, wi, hi);
                SCALED.put(k, b);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setFilterBitmap(true);
        p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(b, Math.round(cx - wi / 2f), Math.round(cy - hi / 2f), p);
        p.setColorFilter(null);
    }
    private static final RectF RF = new RectF();
    private static final Path PATH = new Path();

    public static void draw(Canvas c, int type, float cx, float cy, float r, Paint p, int color) {
        if (type == ETH_NOINET) {
            // Ethernet с белой косой чертой (как у Wi-Fi без подключения): кабель подключён, а интернета нет
            draw(c, ETH, cx, cy, r, p, color);
            p.setShader(null);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(r * 0.16f);
            c.drawLine(cx - r * 0.8f, cy - r * 0.7f, cx + r * 0.8f, cy + r * 0.8f, p);
            p.setStyle(Paint.Style.FILL);
            return;
        }
        p.setShader(null);
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (type) {
            case GEAR: {
                if (gearBmp != null) {
                    float h2 = r * 1.9f;
                    p.setStyle(Paint.Style.FILL);
                    p.setFilterBitmap(true);
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    drawBmp(c, gearBmp, cx, cy, h2, h2, p, color, false);
                    p.setColorFilter(null);
                    break;
                }
                // сплошная шестерня с 6 скруглёнными зубцами и круглым отверстием
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeWidth(r * 0.09f);
                Path body = new Path();
                body.addCircle(cx, cy, r * 0.63f, Path.Direction.CW);
                for (int i = 0; i < 6; i++) {
                    Path tooth = new Path();
                    RF.set(cx - r * 0.2f, cy - r * 0.95f, cx + r * 0.2f, cy - r * 0.45f);
                    tooth.addRoundRect(RF, r * 0.12f, r * 0.12f, Path.Direction.CW);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setRotate(i * 60f, cx, cy);
                    tooth.transform(m);
                    body.op(tooth, Path.Op.UNION);
                }
                Path hole = new Path();
                hole.addCircle(cx, cy, r * 0.34f, Path.Direction.CW);
                body.op(hole, Path.Op.DIFFERENCE);
                c.drawPath(body, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case WIFI:
            case WIFI_OFF: {
                if (type == WIFI_OFF) p.setColor(ColorUtil.alpha(color, 90));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.17f);
                float by = cy + r * 0.55f;
                for (int i = 1; i <= 3; i++) {
                    float rr = r * 0.42f * i;
                    RF.set(cx - rr, by - rr, cx + rr, by + rr);
                    c.drawArc(RF, -135, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, by, r * 0.1f, p);
                if (type == WIFI_OFF) {
                    p.setColor(color);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.16f);
                    c.drawLine(cx - r * 0.8f, cy - r * 0.7f, cx + r * 0.8f, cy + r * 0.8f, p);
                }
                break;
            }
            case ETH: {
                // иконка пользователя (ic_eth.png) без изменений, только перекрашивается в цвет значка
                if (ethBmp != null) {
                    float h2 = r * 1.7f, w2 = h2 * ethBmp.getWidth() / ethBmp.getHeight();
                    RF.set(cx - w2 / 2f, cy - h2 / 2f, cx + w2 / 2f, cy + h2 / 2f);
                    p.setStyle(Paint.Style.FILL);
                    p.setFilterBitmap(true);
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    drawBmp(c, ethBmp, RF.centerX(), RF.centerY(), RF.width(), RF.height(), p, color, false);
                    p.setColorFilter(null);
                }
                break;
            }
            case VPN: {
                if (vpnBmp != null) {
                    float w2 = r * 2.1f, h2 = w2 * vpnBmp.getHeight() / vpnBmp.getWidth();
                    if (Float.isNaN(vpnShift)) vpnShift = shiftOf(vpnBmp);
                    cx -= vpnShift * w2;        // по центру ячейки — сам ключ, а не картинка с прозрачными полями
                    RF.set(cx - w2 / 2f, cy - h2 / 2f, cx + w2 / 2f, cy + h2 / 2f);
                    p.setStyle(Paint.Style.FILL);
                    p.setFilterBitmap(true);
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    drawBmp(c, vpnBmp, RF.centerX(), RF.centerY(), RF.width(), RF.height(), p, color, true);
                    p.setColorFilter(null);
                    break;
                }
                // запасной вариант: три буквы «VPN»
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(GlassView.MED);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(r * 1.05f);
                p.setLetterSpacing(0.02f);
                c.drawText("VPN", cx, cy + r * 0.33f, p);
                p.setLetterSpacing(0f);
                break;
            }
            case INPUT: {
                // иконка пользователя (ic_src.png) без изменений, только перекрашивается в цвет значка
                if (srcBmp != null) {
                    float h2 = r * 1.5f, w2 = h2 * srcBmp.getWidth() / srcBmp.getHeight();
                    RF.set(cx - w2 / 2f, cy - h2 / 2f, cx + w2 / 2f, cy + h2 / 2f);
                    p.setStyle(Paint.Style.FILL);
                    p.setFilterBitmap(true);
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    drawBmp(c, srcBmp, RF.centerX(), RF.centerY(), RF.width(), RF.height(), p, color, false);
                    p.setColorFilter(null);
                    break;
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.16f);
                RF.set(cx - r * 0.85f, cy - r * 0.7f, cx + r * 0.85f, cy + r * 0.5f);
                c.drawRoundRect(RF, r * 0.14f, r * 0.14f, p);
                c.drawLine(cx - r * 0.3f, cy + r * 0.85f, cx + r * 0.3f, cy + r * 0.85f, p);
                c.drawLine(cx, cy + r * 0.5f, cx, cy + r * 0.85f, p);
                c.drawLine(cx - r * 0.45f, cy - r * 0.1f, cx + r * 0.3f, cy - r * 0.1f, p);
                c.drawLine(cx + r * 0.05f, cy - r * 0.38f, cx + r * 0.33f, cy - r * 0.1f, p);
                c.drawLine(cx + r * 0.05f, cy + r * 0.18f, cx + r * 0.33f, cy - r * 0.1f, p);
                break;
            }
            case SRC_HDMI: case SRC_DP: case SRC_AV: case SRC_SVIDEO: case SRC_COMPONENT:
            case SRC_VGA: case SRC_DVI: case SRC_SCART: case SRC_TUNER:
                source(c, type, cx, cy, r, p);
                break;
            case SEARCH: {
                // лупа: кольцо и ручка под 45°
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.17f);
                float lr = r * 0.46f, lcx = cx - r * 0.13f, lcy = cy - r * 0.13f;
                c.drawCircle(lcx, lcy, lr, p);
                p.setStrokeWidth(r * 0.2f);
                float d = lr * 0.7071f;
                c.drawLine(lcx + d + r * 0.05f, lcy + d + r * 0.05f, cx + r * 0.78f, cy + r * 0.78f, p);
                break;
            }
            case LOCK: {
                // замок: дужка и корпус
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.17f);
                RF.set(cx - r * 0.34f, cy - r * 0.86f, cx + r * 0.34f, cy - r * 0.18f);
                c.drawArc(RF, 180, 180, false, p);
                c.drawLine(cx - r * 0.34f, cy - r * 0.52f, cx - r * 0.34f, cy - r * 0.1f, p);
                c.drawLine(cx + r * 0.34f, cy - r * 0.52f, cx + r * 0.34f, cy - r * 0.1f, p);
                p.setStyle(Paint.Style.FILL);
                RF.set(cx - r * 0.62f, cy - r * 0.12f, cx + r * 0.62f, cy + r * 0.8f);
                c.drawRoundRect(RF, r * 0.16f, r * 0.16f, p);
                break;
            }
            case GRID: {
                p.setStyle(Paint.Style.FILL);
                float cell = r * 0.44f, gap = r * 0.14f, half = (3 * cell + 2 * gap) / 2f;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        float x = cx - half + i * (cell + gap), y = cy - half + j * (cell + gap);
                        RF.set(x, y, x + cell, y + cell);
                        c.drawRoundRect(RF, r * 0.08f, r * 0.08f, p);
                    }
                }
                break;
            }
            case LIST: {
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * r * 0.55f;
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx - r * 0.7f, y, r * 0.11f, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.17f);
                    c.drawLine(cx - r * 0.38f, y, cx + r * 0.78f, y, p);
                }
                break;
            }
            case TUNE: {
                float[] ks = {-0.35f, 0.35f, -0.1f};
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * r * 0.55f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.12f);
                    c.drawLine(cx - r * 0.8f, y, cx + r * 0.8f, y, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + ks[i + 1] * r, y, r * 0.2f, p);
                }
                break;
            }
            case IMAGE: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.15f);
                RF.set(cx - r * 0.8f, cy - r * 0.6f, cx + r * 0.8f, cy + r * 0.6f);
                c.drawRoundRect(RF, r * 0.14f, r * 0.14f, p);
                PATH.reset();
                PATH.moveTo(cx - r * 0.7f, cy + r * 0.5f);
                PATH.lineTo(cx - r * 0.2f, cy - r * 0.05f);
                PATH.lineTo(cx + r * 0.15f, cy + r * 0.3f);
                PATH.lineTo(cx + r * 0.4f, cy + r * 0.05f);
                PATH.lineTo(cx + r * 0.7f, cy + r * 0.5f);
                c.drawPath(PATH, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + r * 0.38f, cy - r * 0.25f, r * 0.1f, p);
                break;
            }
            case EYE: {
                // открытый глаз: контур-миндалина и радужка, всё линиями и кругом (ни картинок, ни памяти)
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.17f);
                PATH.reset();
                PATH.moveTo(cx - r * 1.4f, cy);
                PATH.cubicTo(cx - r * 0.7f, cy - r * 1.0f, cx + r * 0.7f, cy - r * 1.0f, cx + r * 1.4f, cy);
                PATH.cubicTo(cx + r * 0.7f, cy + r * 1.0f, cx - r * 0.7f, cy + r * 1.0f, cx - r * 1.4f, cy);
                PATH.close();
                c.drawPath(PATH, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r * 0.46f, p);
                break;
            }
            case REFRESH: {
                if (refreshBmp != null) {
                    float h2 = r * 1.9f;
                    RF.set(cx - h2 / 2f, cy - h2 / 2f, cx + h2 / 2f, cy + h2 / 2f);
                    p.setStyle(Paint.Style.FILL);
                    p.setFilterBitmap(true);
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                    drawBmp(c, refreshBmp, RF.centerX(), RF.centerY(), RF.width(), RF.height(), p, color, true);
                    p.setColorFilter(null);
                    break;
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.16f);
                RF.set(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f);
                c.drawArc(RF, -60, 280, false, p);
                p.setStyle(Paint.Style.FILL);
                PATH.reset();
                PATH.moveTo(cx + r * 0.3f, cy - r * 0.95f);
                PATH.lineTo(cx + r * 0.85f, cy - r * 0.35f);
                PATH.lineTo(cx + r * 0.1f, cy - r * 0.25f);
                PATH.close();
                c.drawPath(PATH, p);
                break;
            }
            default:
                break;
        }
    }

    // ---------- значки типов разъёмов (источники сигнала) ----------
    private static void plug(Canvas c, float cx, float cy, float r, Paint p, float... pts) {
        PATH.reset();
        for (int i = 0; i < pts.length; i += 2) {
            float x = cx + pts[i] * r, y = cy + pts[i + 1] * r;
            if (i == 0) PATH.moveTo(x, y); else PATH.lineTo(x, y);
        }
        PATH.close();
        p.setStyle(Paint.Style.STROKE);
        c.drawPath(PATH, p);
    }

    private static void dots(Canvas c, float cx, float cy, float r, Paint p, float y, float x0, float x1, int n, float rad) {
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? (x0 + x1) / 2f : x0 + (x1 - x0) * i / (n - 1);
            c.drawCircle(cx + x * r, cy + y * r, rad * r, p);
        }
    }

    private static void ring(Canvas c, float x, float y, float rad, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        c.drawCircle(x, y, rad, p);
    }

    private static void source(Canvas c, int type, float cx, float cy, float r, Paint p) {
        p.setStrokeWidth(r * 0.17f);
        p.setStyle(Paint.Style.STROKE);
        switch (type) {
            case SRC_HDMI:
                plug(c, cx, cy, r, p, -1.1f, -0.5f, 1.1f, -0.5f, 1.1f, 0.1f, 0.7f, 0.55f, -0.7f, 0.55f, -1.1f, 0.1f);
                c.drawLine(cx - 0.6f * r, cy + 0.02f * r, cx + 0.6f * r, cy + 0.02f * r, p);
                break;
            case SRC_DP:
                plug(c, cx, cy, r, p, -1.1f, -0.5f, 1.1f, -0.5f, 1.1f, 0.05f, 0.6f, 0.55f, -1.1f, 0.55f);
                c.drawLine(cx - 0.6f * r, cy + 0.02f * r, cx + 0.6f * r, cy + 0.02f * r, p);
                break;
            case SRC_AV:
                ring(c, cx, cy, r * 0.72f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r * 0.24f, p);
                break;
            case SRC_SVIDEO:
                ring(c, cx, cy, r * 0.78f, p);
                dots(c, cx, cy, r, p, 0.22f, -0.36f, 0.36f, 2, 0.1f);
                dots(c, cx, cy, r, p, -0.28f, -0.2f, 0.2f, 2, 0.1f);
                break;
            case SRC_COMPONENT:
                ring(c, cx - 0.82f * r, cy, r * 0.34f, p);
                ring(c, cx, cy, r * 0.34f, p);
                ring(c, cx + 0.82f * r, cy, r * 0.34f, p);
                break;
            case SRC_VGA:
                plug(c, cx, cy, r, p, -1.1f, -0.5f, 1.1f, -0.5f, 0.8f, 0.55f, -0.8f, 0.55f);
                dots(c, cx, cy, r, p, -0.14f, -0.6f, 0.6f, 5, 0.09f);
                dots(c, cx, cy, r, p, 0.2f, -0.42f, 0.42f, 4, 0.09f);
                break;
            case SRC_DVI:
                RF.set(cx - 1.1f * r, cy - 0.5f * r, cx + 1.1f * r, cy + 0.5f * r);
                c.drawRoundRect(RF, r * 0.15f, r * 0.15f, p);
                c.drawLine(cx - 0.62f * r, cy - 0.22f * r, cx - 0.62f * r, cy + 0.22f * r, p);
                dots(c, cx, cy, r, p, -0.12f, -0.2f, 0.7f, 4, 0.09f);
                dots(c, cx, cy, r, p, 0.2f, -0.2f, 0.7f, 4, 0.09f);
                break;
            case SRC_SCART:
                RF.set(cx - 1.1f * r, cy - 0.5f * r, cx + 1.1f * r, cy + 0.5f * r);
                c.drawRoundRect(RF, r * 0.15f, r * 0.15f, p);
                c.drawLine(cx - 0.8f * r, cy, cx + 0.8f * r, cy, p);
                break;
            case SRC_TUNER: {
                float oy = cy - 0.1f * r;
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy, r * 0.15f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx, oy, cx, cy + 0.85f * r, p);
                c.drawLine(cx - 0.4f * r, cy + 0.85f * r, cx + 0.4f * r, cy + 0.85f * r, p);
                for (int k = 0; k < 2; k++) {
                    float rad = r * (0.45f + 0.4f * k);
                    RF.set(cx - rad, oy - rad, cx + rad, oy + rad);
                    c.drawArc(RF, -40, 80, false, p);
                    c.drawArc(RF, 140, 80, false, p);
                }
                break;
            }
            default:
                break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** Погодная иконка по коду Gismeteo вида d_c2_r1_st / n_c1 / d. */
    public static void weather(Canvas c, String code, float cx, float cy, float r, Paint p) {
        if (code == null) code = "d";
        boolean night = code.startsWith("n");
        int cl = 0, rain = 0, snow = 0, mix = 0;
        boolean storm = false;
        for (String t : code.split("_")) {
            if (t.equals("st")) storm = true;
            else if (t.startsWith("rs") && t.length() == 3) mix = t.charAt(2) - '0';
            else if (t.startsWith("c") && t.length() == 2) cl = t.charAt(1) - '0';
            else if (t.startsWith("r") && t.length() == 2) rain = t.charAt(1) - '0';
            else if (t.startsWith("s") && t.length() == 2) snow = t.charAt(1) - '0';
        }
        boolean precip = rain + snow + mix > 0;
        boolean hasCloud = cl > 0 || precip || storm;
        p.setShader(null);
        p.setStrokeCap(Paint.Cap.ROUND);

        if (cl < 3) {
            float sx = hasCloud ? cx - r * 0.38f : cx, sy = hasCloud ? cy - r * 0.38f : cy;
            float sr = hasCloud ? r * 0.46f : r * 0.58f;
            if (night) {
                PATH.reset();
                Path a = new Path(), b = new Path();
                a.addCircle(sx, sy, sr, Path.Direction.CW);
                b.addCircle(sx + sr * 0.55f, sy - sr * 0.3f, sr * 0.85f, Path.Direction.CW);
                PATH.op(a, b, Path.Op.DIFFERENCE);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF6F9CFF);
                c.drawPath(PATH, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFC83D);
                c.drawCircle(sx, sy, sr, p);
                if (!hasCloud) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * 0.1f);
                    for (int i = 0; i < 8; i++) {
                        double an = i * Math.PI / 4;
                        float cs = (float) Math.cos(an), sn = (float) Math.sin(an);
                        c.drawLine(sx + cs * sr * 1.3f, sy + sn * sr * 1.3f, sx + cs * sr * 1.65f, sy + sn * sr * 1.65f, p);
                    }
                }
            }
        }
        if (hasCloud) {
            float ox = cl < 3 ? r * 0.1f : 0, oy = precip || storm ? -r * 0.12f : r * 0.1f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(cl >= 3 || precip || storm ? 0xFFB7BFCC : 0xFFF2F5FA);
            float x = cx + ox, y = cy + oy;
            c.drawCircle(x - r * 0.32f, y + r * 0.12f, r * 0.34f, p);
            c.drawCircle(x + r * 0.02f, y - r * 0.08f, r * 0.46f, p);
            c.drawCircle(x + r * 0.46f, y + r * 0.16f, r * 0.32f, p);
            RF.set(x - r * 0.62f, y + r * 0.1f, x + r * 0.78f, y + r * 0.48f);
            c.drawRoundRect(RF, r * 0.19f, r * 0.19f, p);
            float py = y + r * 0.62f;
            if (rain + mix > 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.09f);
                p.setColor(0xFF6EB6FF);
                for (int i = 0; i < 3; i++) {
                    float lx = x - r * 0.35f + i * r * 0.34f;
                    c.drawLine(lx, py, lx - r * 0.12f, py + r * 0.3f, p);
                }
            }
            if (snow + mix > 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                for (int i = 0; i < 3; i++) {
                    float lx = x - r * 0.3f + i * r * 0.32f;
                    c.drawCircle(lx, py + (i % 2) * r * 0.2f + r * 0.08f, r * 0.08f, p);
                }
            }
            if (storm) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFD84A);
                PATH.reset();
                PATH.moveTo(x + r * 0.05f, py - r * 0.05f);
                PATH.lineTo(x - r * 0.18f, py + r * 0.28f);
                PATH.lineTo(x, py + r * 0.28f);
                PATH.lineTo(x - r * 0.1f, py + r * 0.62f);
                PATH.lineTo(x + r * 0.25f, py + r * 0.18f);
                PATH.lineTo(x + r * 0.07f, py + r * 0.18f);
                PATH.close();
                c.drawPath(PATH, p);
            }
        }
    }
}
