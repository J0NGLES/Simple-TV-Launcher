package com.simpletvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Snapshot of the activity under a dialog, blurred on two levels (panel: soft, buttons: stronger). */
final class Backdrop {
    private Backdrop() {}
    static Bitmap soft, hard;
    static View decor;
    static int dw, dh;
    /** Идёт снимок окна: фон-видео рисует вместо себя свой кадр (TextureView в снимок не попадает). */
    static boolean capturing;
    private static final Paint BP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R = new RectF();
    private static final int[] A = new int[2], B = new int[2];

    // Подложка меню из самого фона (кадр видео, картинка или градиент): маленькая картинка считается мгновенно и один раз
    // на фон, а не снимком окна (живое видео в него не попадает, а плитки приложений только грязнят цвет).
    private static final int STILL_DOWN = 16;
    private static Bitmap stillSoft, stillHard;
    private static String stillKey = "";

    static String stillKey(int w, int h, int dimAlpha, int sat, Bitmap frame) {
        return w + "x" + h + ":" + dimAlpha + ":" + sat + ":" + System.identityHashCode(frame);
    }

    static synchronized void ensureStill(Bitmap frame, int w, int h, int dimAlpha, int sat) {
        String key = stillKey(w, h, dimAlpha, sat, frame);
        if (key.equals(stillKey) && stillSoft != null) return;
        try {
            Bitmap b = Bitmap.createBitmap(Math.max(8, w / STILL_DOWN), Math.max(8, h / STILL_DOWN), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF07050B);
            float sc = Math.max(b.getWidth() / (float) frame.getWidth(), b.getHeight() / (float) frame.getHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(sc, sc);
            m.postTranslate((b.getWidth() - frame.getWidth() * sc) / 2f, (b.getHeight() - frame.getHeight() * sc) / 2f);
            Paint sp = new Paint(BP);
            if (sat < 100) {
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                cm.setSaturation(sat / 100f);
                sp.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            }
            c.drawBitmap(frame, m, sp);
            if (dimAlpha > 0) c.drawColor(dimAlpha << 24);
            stillSoft = smooth(blur(b, 1));
            stillHard = smooth(blur(b, 2));
            stillKey = key;
        } catch (Throwable ignored) { stillSoft = null; stillHard = null; stillKey = ""; }
    }

    static synchronized boolean hasStill(String key) { return key.equals(stillKey) && stillSoft != null; }

    /** Подложка взята из готового кадра видео: тёмной вуали под стеклом нужно меньше, иначе меню выглядят непрозрачными. */
    private static boolean fromStill;

    static int shade() { return fromStill ? 0x32000000 : 0x73000000; }

    static void capture(Activity a) {
        soft = null; hard = null; decor = null; fromStill = false;
        try {
            View d = a.getWindow().getDecorView();
            int w = d.getWidth(), h = d.getHeight();
            if (w <= 0 || h <= 0) return;
            BgView bg = Frost.bg != null && Frost.bg.getContext() == a ? Frost.bg : null;
            Bitmap b = Bitmap.createBitmap(Math.max(8, w / CAP_DOWN), Math.max(8, h / CAP_DOWN), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(b.getWidth() / (float) w, b.getHeight() / (float) h);
            capturing = true;
            try { d.draw(c); } finally { capturing = false; }
            Bitmap s = smooth(blur(b, 2)), hd = smooth(blur(b, 4));     // сквозь стекло угадываются плитки, но без резких краёв
            if (bg != null && bg.stillReady()) {        // цвет самого фона (кадр видео / картинка / градиент) поверх снимка
                tint(s, stillSoft);
                tint(hd, stillHard);
                fromStill = true;
            }
            soft = desaturate(s);
            hard = desaturate(hd);
            decor = d; dw = w; dh = h;
        } catch (Throwable t) { soft = null; hard = null; decor = null; }
    }

    private static final int CAP_DOWN = 8;
    private static final Paint TP = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static void tint(Bitmap dst, Bitmap color) {
        if (color == null) return;
        TP.setAlpha(135);
        new Canvas(dst).drawBitmap(color, null, new RectF(0, 0, dst.getWidth(), dst.getHeight()), TP);
    }

    /** Меню чуть спокойнее самого фона по цвету (насыщенность как у фона). */
    private static Bitmap desaturate(Bitmap src) {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.0f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }
    static void clear() { soft = null; hard = null; decor = null; }

    static boolean has() { return soft != null && decor != null; }

    static void draw(View v, Canvas c, float w, float h, float radius, boolean strong) {
        Bitmap bm = strong ? hard : soft;
        if (bm == null || decor == null) return;
        v.getLocationOnScreen(A);
        decor.getLocationOnScreen(B);
        R.set(0, 0, w, h);
        PATH.reset();
        PATH.addRoundRect(R, radius, radius, Path.Direction.CW);
        int sc = c.save();
        c.clipPath(PATH);
        c.translate(B[0] - A[0], B[1] - A[1]);
        c.drawBitmap(bm, null, new RectF(0, 0, dw, dh), BP);
        c.restoreToCount(sc);
    }

    /**
     * Маленькая размытая картинка при растяжении на весь экран даёт видимые «пиксели». Поэтому её один раз увеличивают вдвое-вчетверо
     * с фильтрацией (до ~500 точек по ширине) и сглаживают ещё одним проходом: края блоков исчезают, а само размытие остаётся лёгким.
     */
    static Bitmap smooth(Bitmap src) {
        Bitmap b = src;
        for (int k = 0; k < 2 && b.getWidth() < 400; k++) b = Bitmap.createScaledBitmap(b, b.getWidth() * 2, b.getHeight() * 2, true);
        if (b == src) return b;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        line(px, w, h, 2, true);
        line(px, w, h, 2, false);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    static Bitmap blur(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) { line(px, w, h, r, true); line(px, w, h, r, false); }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void line(int[] px, int w, int h, int r, boolean horiz) {
        int n = horiz ? w : h, m = horiz ? h : w;
        int[] buf = new int[n];
        int win = 2 * r + 1;
        for (int j = 0; j < m; j++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = px[idx(Math.max(0, Math.min(n - 1, k)), j, w, horiz)];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                buf[i] = 0xFF000000 | ((sr / win) << 16) | ((sg / win) << 8) | (sb / win);
                int add = px[idx(Math.min(n - 1, i + r + 1), j, w, horiz)];
                int rem = px[idx(Math.max(0, i - r), j, w, horiz)];
                sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                sb += (add & 255) - (rem & 255);
            }
            for (int i = 0; i < n; i++) px[idx(i, j, w, horiz)] = buf[i];
        }
    }

    private static int idx(int i, int j, int w, boolean horiz) { return horiz ? j * w + i : i * w + j; }
}