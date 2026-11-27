package com.simpletvlauncher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Единое «матовое стекло» главного экрана: виджет погоды, капсула значков, подложка сетки приложений и нижние карточки
 * рисуются одним кодом — размытый фон, лёгкая высветленность, мягкий блик сверху и тонкая световая кромка.
 * Тёмный фон под стеклом чуть приподнят по яркости (светлый почти не меняется) — иначе на тёмных обоях оно выглядит грязным.
 */
final class Frost {
    private Frost() {}

    /** Верх/низ высветленности и блик; «лёгкий» эффект стекла — почти прозрачный. */
    private static final int TINT_TOP = 0x22FFFFFF, TINT_BOT = 0x14FFFFFF, LIGHT_TOP = 0x0BFFFFFF, LIGHT_BOT = 0x05FFFFFF;
    private static final int GLOSS = 0x10FFFFFF, LIGHT_GLOSS = 0x07FFFFFF;
    /** Высота блика в единицах макета: на высокой подложке он той же высоты, что и на кнопках. */
    private static final float GLOSS_HEIGHT = 52f;
    static boolean light;

    static BgView bg;
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LIFT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint LIGHT_LIFT = new Paint(Paint.FILTER_BITMAP_FLAG);      // фон под лёгким стеклом почти как есть
    private static final RectF R = new RectF(), E = new RectF();
    private static final Path PATH = new Path();
    private static final int[] A = new int[2], B = new int[2];

    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(1.2f);
        m.postConcat(new android.graphics.ColorMatrix(new float[]{
                0.85f, 0, 0, 0, 28,
                0, 0.85f, 0, 0, 28,
                0, 0, 0.85f, 0, 28,
                0, 0, 0, 1, 0}));
        LIFT.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
        LIGHT_LIFT.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                0.94f, 0, 0, 0, 10,
                0, 0.94f, 0, 0, 10,
                0, 0, 0.94f, 0, 10,
                0, 0, 0, 1, 0}));
    }

    static void draw(View v, Canvas c, float w, float h, float radius) {
        R.set(0, 0, w, h);
        PATH.reset();
        PATH.addRoundRect(R, radius, radius, Path.Direction.CW);
        int sc = c.save();
        c.clipPath(PATH);
        BgView g = bg;
        android.graphics.Bitmap bl = (g != null && g.getWidth() > 0) ? g.blurred() : null;
        if (bl != null) {
            // место берётся без увеличения самого элемента: иначе перерисовка в увеличенном виде сдвигала фон под стеклом
            View parent = (View) v.getParent();
            parent.getLocationInWindow(A);
            g.getLocationInWindow(B);
            float dx = A[0] - parent.getScrollX() + v.getLeft() + v.getTranslationX() - B[0];
            float dy = A[1] - parent.getScrollY() + v.getTop() + v.getTranslationY() - B[1];
            c.translate(-dx, -dy);
            c.drawBitmap(bl, null, new RectF(0, 0, g.getWidth(), g.getHeight()), light ? LIGHT_LIFT : LIFT);
            c.translate(dx, dy);
        }
        float unit = UI.scale(v.getContext());
        P.setStyle(Paint.Style.FILL);
        P.setShader(new LinearGradient(0, 0, 0, h, light ? LIGHT_TOP : TINT_TOP, light ? LIGHT_BOT : TINT_BOT, Shader.TileMode.CLAMP));
        c.drawRect(R, P);
        float gloss = Math.min(h * 0.5f, GLOSS_HEIGHT * unit);
        P.setShader(new LinearGradient(0, 0, 0, gloss, light ? LIGHT_GLOSS : GLOSS, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, gloss, P);
        c.restoreToCount(sc);

        // кромка: чуть светится сверху, гаснет по бокам и слабо отсвечивает снизу
        float sw = Math.max(1.2f, 1.5f * unit), r = Math.max(0, radius - sw / 2);
        E.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw);
        P.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x5CFFFFFF, 0x24FFFFFF, 0x1CFFFFFF, 0x34FFFFFF},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(E, r, r, P);
        P.setShader(null);
    }
}
