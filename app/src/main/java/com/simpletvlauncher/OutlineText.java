package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * Надпись с настройкой «Контрастные надписи»: вокруг букв чёрная обводка с непрозрачностью 25 % и непрозрачная чёрная тень,
 * сдвинутая на пару пикселей (у мелкого шрифта — на один). Без настройки рисуется как обычный TextView.
 */
final class OutlineText extends TextView {
    private static final int RING_ALPHA = 64;      // 25 %
    private static final int SHADOW_ALPHA = 255;   // 100 %

    private final float s;
    private final Paint ring = new Paint(), shadow = new Paint(), cut = new Paint();
    private int filterAlpha = -1;      // непрозрачность цвета текста, под которую посчитаны фильтры

    OutlineText(Context c) {
        super(c);
        s = UI.scale(c);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    /** Всё нарисованное становится чёрным, непрозрачность умножается на k. */
    private static ColorMatrixColorFilter black(float k) {
        return new ColorMatrixColorFilter(new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, k, 0});
    }

    @Override protected void onDraw(Canvas c) {
        int alpha = getCurrentTextColor() >>> 24;
        if (!UI.contrast || alpha == 0) { super.onDraw(c); return; }
        if (alpha != filterAlpha) {
            filterAlpha = alpha;
            // у полупрозрачного текста (дата, подписи категорий) обводка и тень всё равно должны иметь заданную непрозрачность
            ring.setColorFilter(black(RING_ALPHA / (float) alpha));
            shadow.setColorFilter(black(SHADOW_ALPHA / (float) alpha));
            cut.setColorFilter(black(255f / alpha));
        }
        int px = Math.max(1, Math.round(s));
        // тень рисуется своим проходом, а не через setShadowLayer: так она не затемняет полупрозрачные буквы изнутри
        boolean small = getTextSize() < 21 * s;
        behind(c, shadow, small ? px : 2 * px, 0);
        behind(c, ring, 0, small ? Math.round(1.5f * px) : 2 * px);     // половина ширины штриха уходит внутрь буквы, наружу — один пиксель
        super.onDraw(c);
    }

    /** Чёрный слой под текстом: копия букв, сдвинутая на shift или расширенная штрихом stroke, без того, что попадает внутрь самих букв. */
    private void behind(Canvas c, Paint look, int shift, int stroke) {
        TextPaint p = getPaint();
        float l = getScrollX(), t = getScrollY(), r = l + getWidth(), b = t + getHeight();
        int outer = c.saveLayer(l, t, r, b, look);
        if (stroke > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(stroke);
        }
        c.save();
        c.translate(shift, shift);
        super.onDraw(c);
        c.restore();
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(0);
        // середина букв вырезается, чтобы чёрный не просвечивал сквозь полупрозрачный текст
        int inner = c.saveLayer(l, t, r, b, cut);
        super.onDraw(c);
        c.restoreToCount(inner);
        c.restoreToCount(outer);
    }
}
