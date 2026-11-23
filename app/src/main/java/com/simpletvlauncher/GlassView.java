package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Базовая «стеклянная» плитка: полупрозрачная заливка, блик сверху, тонкая светлая кромка.
 * От неё наследуются плитки приложений, кнопки, строки списков и карточки погоды.
 */
public class GlassView extends View {
    public interface Painter { void paint(Canvas c, GlassView v); }

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    public static final Typeface REG = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public float radius = 20;
    public int icon;                 // Icons.*
    public String label, value, hint, title;
    public Drawable appIcon;         // для строк списка
    public boolean left;             // строка списка: текст слева, значение справа
    public boolean floating;         // фокус рисует плавающая подсветка, не сама плитка
    public boolean frosted;          // frosted glass with backdrop blur (home screen)
    public boolean blurBack;          // dialog row: blurred backdrop snapshot
    public int tint;                 // строка настроек: заливка другим цветом (0 — нет)
    public boolean warn;            // строка настроек: жёлтый «!» перед названием
    public int eye;                 // строка списка приложений: 1 — глаз (показано, 100%), 2 — тот же глаз вполсилы (скрыто)
    public boolean fav;             // строка списка приложений: звёздочка слева от глаза (приложение в избранном)
    public boolean lock;            // строка списка приложений: замок (приложение под PIN-кодом)
    public boolean plain;           // без собственного стекла (кнопка внутри капсулы)
    public float focusScale = 1.08f;
    public Painter painter;
    public int iconColor = 0xFFFFFFFF;

    protected final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader fillShader, sheenShader, diagShader, topBandShader, botShader;
    private final RectF rf = new RectF();

    // Material-стиль (все экраны, кроме главного): сплошные поверхности, обводка при фокусе
    public static final int MAT_SURFACE = 0xFF2B2930, MAT_FOCUS = 0xFF4A4458, MAT_PRIMARY = 0xFFD0BCFF;
    public boolean solidFocus;       // главный экран: в фокусе — сплошная белая таблетка с тёмным содержимым
    public boolean material;
    public int surface = MAT_SURFACE;
    public int swatch;               // цветной кружок слева (выбор цвета)

    public GlassView(Context c) {
        super(c);
        material = !(c instanceof MainActivity);
        setFocusable(true);
        setClickable(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    /** Строка без собственного рисунка в Material-режиме: фокус = сплошная светлая таблетка с тёмным текстом. */
    private boolean pill() { return material && painter == null && !plain; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        applyFocusScale();
        if (material && left && painter == null) radius = h / 2f;
        fillShader = new LinearGradient(0, 0, 0, h, 0x40FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP);
        sheenShader = new LinearGradient(0, 0, 0, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        // блики выбранной плитки создаются один раз, а не на каждой перерисовке
        diagShader = new LinearGradient(0, 0, w * 0.7f, h * 0.95f, 0x20FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        topBandShader = new LinearGradient(0, 0, 0, h * 0.22f, 0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        botShader = new LinearGradient(0, h * 0.6f, 0, h, 0x00000000, 0x14000000, Shader.TileMode.CLAMP);
        invalidateOutline();
    }

    @Override protected void onFocusChanged(boolean gained, int direction, Rect prev) {
        super.onFocusChanged(gained, direction, prev);
        applyFocusScale();
        invalidate();
    }

    /** Масштаб подбирается так, чтобы увеличенная плитка занимала ЦЕЛОЕ число пикселей с каждой стороны: иначе её край попадает на дробный пиксель. */
    public static float snap(int size, float sc) {
        if (size <= 0 || sc == 1f) return sc;
        int ex = Math.round(size * (sc - 1f) / 2f);
        return (size + 2 * ex) / (float) size;
    }

    public void applyFocusScale() {
        float t = isFocused() && !material ? focusScale : 1f;
        setScaleX(snap(getWidth(), t));
        setScaleY(snap(getHeight(), t));
    }

    protected void drawBg(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (material && blurBack && Backdrop.has() && !(pill() && isFocused())) {
            Backdrop.draw(this, c, w, h, radius, true);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(Backdrop.shade());
            c.drawRect(0, 0, w, h, p);
            p.setColor(isFocused() ? 0x40FFFFFF : 0x1CFFFFFF);
            c.drawRect(0, 0, w, h, p);
            return;
        }
        if (material) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            // матовая таблетка; в фокусе — сплошная светлая (для строк), у карточек с рисунком — подсветка + белая рамка
            p.setColor(isFocused() ? (pill() ? 0xFFF2F2F7 : 0x40FFFFFF) : 0x22FFFFFF);
            c.drawRect(0, 0, w, h, p);
            return;
        }
        if (solidFocus && isFocused()) return;      // белую таблетку рисует HighlightView под плиткой
        if (frosted) {
            Frost.draw(this, c, w, h, radius);
            if (!floating && isFocused()) { p.setStyle(Paint.Style.FILL); p.setShader(null); p.setColor(0x38FFFFFF); c.drawRect(0, 0, w, h, p); }
            return;
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(fillShader);
        c.drawRect(0, 0, w, h, p);
        p.setShader(sheenShader);
        c.drawRect(0, 0, w, h * 0.55f, p);
        p.setShader(null);
        if (!floating && isFocused()) {
            p.setColor(0x38FFFFFF);
            c.drawRect(0, 0, w, h, p);
        }
    }

    protected void drawChrome(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (material) {
            if (!pill() || !isFocused()) {
                boolean f = isFocused();
                float sw = f ? Math.max(3f, h / 28f) : Math.max(1.5f, h / 56f);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setColor(f ? 0xFFFFFFFF : 0x44FFFFFF);
                rf.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
                c.drawRoundRect(rf, Math.max(0, radius - sw / 2), Math.max(0, radius - sw / 2), p);
            }
            return;
        }
        p.setShader(null);
        if (solidFocus && isFocused()) return;
        if (floating && isFocused()) {
            // блик: диагональный «зеркальный» отсвет по верхней части плитки
            p.setStyle(Paint.Style.FILL);
            // сильнее «стекло» у выбранной плитки: диагональный блик, светлая верхняя кромка и лёгкое затемнение снизу
            p.setShader(diagShader);
            c.drawRect(0, 0, w, h, p);
            p.setShader(topBandShader);
            c.drawRect(0, 0, w, h * 0.22f, p);
            p.setShader(botShader);
            c.drawRect(0, h * 0.6f, w, h, p);
            p.setShader(null);
        }
        p.setStyle(Paint.Style.STROKE);
        boolean f = !floating && isFocused();
        float sw = Math.max(1.2f, h / 100f) * (f ? 1.8f : 1f);
        p.setStrokeWidth(sw);
        p.setColor(f ? 0xDDFFFFFF : 0x26FFFFFF);
        rf.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        c.drawRoundRect(rf, Math.max(0, radius - sw / 2), Math.max(0, radius - sw / 2), p);
    }

    @Override protected void onDraw(Canvas c) {
        if (!plain) drawBg(c);
        if (tint != 0) {        // подсветка строки другим цветом («сюда надо нажать»)
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(tint);
            rf.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(rf, radius, radius, p);
        }
        drawContent(c);
        if (!plain) drawChrome(c);
    }

    protected void drawContent(Canvas c) {
        if (painter != null) { painter.paint(c, this); return; }
        int w = getWidth(), h = getHeight();
        boolean dk = isFocused() && (material ? pill() : (solidFocus || plain));
        int ic = dk ? ColorUtil.alpha(0xFF1B1B22, android.graphics.Color.alpha(iconColor) >= 0xF0 ? 255 : 120) : iconColor;
        if (left) {
            float pad = h * 0.3f, x = pad;
            if (swatch != 0) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
                p.setColor(swatch);
                c.drawCircle(x + h * 0.26f, h / 2f, h * 0.26f, p);
                x += h * 0.52f + pad * 0.7f;
            } else if (appIcon != null) {
                int s = (int) (h * 0.62f);
                appIcon.setBounds((int) x, (h - s) / 2, (int) x + s, (h - s) / 2 + s);
                appIcon.draw(c);
                x += s + pad * 0.7f;
            } else if (icon != 0) {
                Icons.draw(c, icon, x + h * 0.26f, h / 2f, h * 0.26f, p, ic);
                x += h * 0.52f + pad * 0.7f;
            }
            float vw = 0;
            if (eye != 0) {         // большой глаз справа: показано — полностью, скрыто — 10 %
                float er = h * 0.3f;
                int ec = dk ? 0xFF55555F : 0xFFFFFFFF;
                Icons.draw(c, Icons.EYE, w - pad - er * 1.4f, h / 2f, er, p, ColorUtil.alpha(ec, eye == 1 ? 255 : 26));
                vw = er * 2.8f + pad;
            }
            if (fav) {          // звёздочка избранного — слева от глаза, высотой примерно с него
                float r = h * 0.27f;
                float cx2 = w - vw - pad * 0.55f - r, cy2 = h / 2f + r * 0.095f;      // поправка: нижние лучи короче верхнего
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
                p.setColor(dk ? 0xFFB07A00 : 0xFFFFD25A);
                android.graphics.Path star = new android.graphics.Path();
                for (int k = 0; k < 10; k++) {
                    double ang = -Math.PI / 2 + k * Math.PI / 5;
                    float rr = k % 2 == 0 ? r : r * 0.45f;
                    float px = cx2 + (float) Math.cos(ang) * rr, py = cy2 + (float) Math.sin(ang) * rr;
                    if (k == 0) star.moveTo(px, py); else star.lineTo(px, py);
                }
                star.close();
                c.drawPath(star, p);
                vw += 2 * r + pad * 0.55f;
            }
            if (value != null) {
                tp.setTypeface(REG);
                tp.setTextSize(h * 0.30f * UI.fs);
                vw = tp.measureText(value) + pad;
                text(c, value, w - pad, h * 0.64f, h * 0.30f, dk ? 0xFF55555F : 0xA0FFFFFF, 1, Paint.Align.RIGHT, 0);
            }
            if (lock) {         // замок — слева от значения («Показано»)
                float r = h * 0.2f;
                float cx2 = w - vw - pad * 0.55f - r;
                Icons.draw(c, Icons.LOCK, cx2, h / 2f, r * 1.05f, p, dk ? 0xFF8A5A00 : 0xFFFFC53D);
                vw += 2 * r + pad * 0.55f;
            }
            if (warn) {         // жёлтый восклицательный знак в кружке — слева от значения («Вкл»)
                float r = h * 0.2f;
                float cx2 = w - vw - pad * 0.55f - r;
                p.setStyle(Paint.Style.FILL);
                p.setShader(null);
                p.setColor(0xFFFFC53D);
                c.drawCircle(cx2, h / 2f, r, p);
                text(c, "!", cx2, h / 2f + r * 0.62f, r * 1.55f, 0xFF3A2A00, 2, Paint.Align.CENTER, 0);
                vw += 2 * r + pad * 0.55f;
            }
            text(c, label, x, h * 0.64f, h * 0.36f, dk ? 0xFF1B1B22 : 0xF2FFFFFF, 2, Paint.Align.LEFT, w - x - vw - pad);
            return;
        }
        if (icon != 0) {
            float r = (label != null ? h * 0.22f : h * 0.3f);
            float cy = label != null ? h * 0.37f : h / 2f;
            Icons.draw(c, icon, w / 2f, cy, r, p, ic);
        }
        if (label != null) {
            text(c, label, w / 2f, h * 0.84f, h * 0.22f, dk ? 0xFF1B1B22 : 0xE6FFFFFF, 1, Paint.Align.CENTER, w * 0.94f);
        }
    }

    /** style: 0 light, 1 regular, 2 medium. maxW<=0 — без обрезки. */
    public void text(Canvas c, String s, float x, float y, float size, int color, int style, Paint.Align al, float maxW) {
        if (s == null) return;
        tp.setStyle(Paint.Style.FILL);
        tp.setShader(null);
        tp.setTextSize(size * UI.fs);
        tp.setColor(color);
        tp.setTypeface(style == 0 ? LIGHT : style == 1 ? REG : MED);
        tp.setTextAlign(al);
        if (maxW > 0 && tp.measureText(s) > maxW) {
            int n = s.length();
            while (n > 1 && tp.measureText(s, 0, n) + tp.measureText("…") > maxW) n--;
            s = s.substring(0, n) + "…";
        }
        c.drawText(s, x, y, tp);
    }
}
