package com.simpletvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.Arrays;

/**
 * Квадратная картинка плитки из значка приложения. Значок не уменьшается «в рамочке»: по его форме выбирается способ.
 * 1) Адаптивный значок с непрозрачным фоном — слои в полтора раза крупнее плитки (видимая часть слоёв 72 из 108).
 * 2) Готовая «пластина» (квадрат, скруглённый квадрат, круг) — растягивается на всю плитку, а то, чего не хватает до углов,
 *    продолжается цветом её края. Тонкая кайма и тень по краю при этом срезаются, поэтому обводки не остаётся.
 * 3) Широкая картинка вместо значка — по ширине плитки, сверху и снизу продолжается цветом краёв.
 * 4) Логотип произвольной формы без подложки — по центру ровной светлой (для светлых логотипов — тёмной) плитки.
 * Углы скругляет контур самой плитки.
 */
final class SquareIcon {
    private SquareIcon() {}

    private static final int ANGLES = 180;
    private static final int SOLID = 200;       // непрозрачность, с которой точка считается частью фигуры (мягкая тень не считается)

    static Bitmap render(Drawable ic, int n) {
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (Build.VERSION.SDK_INT >= 26 && ic instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) ic;
            int over = n / 4;
            for (Drawable layer : new Drawable[]{ad.getBackground(), ad.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-over, -over, n + over, n + over);
                layer.draw(c);
            }
            if (opaqueEdges(out)) return out;
            return compose(out, n);         // фон прозрачный: дальше как с обычным значком
        }
        int iw = ic.getIntrinsicWidth(), ih = ic.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) iw = ih = n;
        // рисуется с сохранением пропорций, длинная сторона — n
        float k = n / (float) Math.max(iw, ih);
        int dw = Math.max(1, Math.round(iw * k)), dh = Math.max(1, Math.round(ih * k));
        ic.setBounds((n - dw) / 2, (n - dh) / 2, (n - dw) / 2 + dw, (n - dh) / 2 + dh);
        ic.draw(c);
        return compose(out, n);
    }

    private static boolean opaqueEdges(Bitmap b) {
        int n = b.getWidth();
        for (int i = 0; i < n; i += 4) {
            if ((b.getPixel(i, 0) >>> 24) < 250 || (b.getPixel(i, n - 1) >>> 24) < 250
                    || (b.getPixel(0, i) >>> 24) < 250 || (b.getPixel(n - 1, i) >>> 24) < 250) return false;
        }
        return true;
    }

    /** Прямоугольник, занятый точками с непрозрачностью не ниже min; null — картинка пустая. */
    private static Rect bounds(int[] px, int n, int min) {
        int l = n, t = n, r = -1, b = -1;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if ((px[y * n + x] >>> 24) >= min) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > b) b = y;
                }
        return r < 0 ? null : new Rect(l, t, r + 1, b + 1);
    }

    private static Bitmap compose(Bitmap src, int n) {
        int[] px = new int[n * n];
        src.getPixels(px, 0, n, 0, 0, n, n);
        Rect box = bounds(px, n, SOLID);
        if (box == null || box.width() < n / 8 || box.height() < n / 8) return plate(src, px, n);

        int solid = 0;
        for (int y = box.top; y < box.bottom; y++)
            for (int x = box.left; x < box.right; x++)
                if ((px[y * n + x] >>> 24) >= SOLID) solid++;
        float cover = solid / (float) (box.width() * box.height());
        float aspect = box.width() / (float) box.height();

        if (aspect > 1.3f && cover > 0.93f) return wide(src, box, n);
        if (aspect > 0.92f && aspect < 1.09f && cover > 0.74f) {
            // фигура растягивается на всю плитку
            Bitmap full = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            new Canvas(full).drawBitmap(src, box, new Rect(0, 0, n, n), new Paint(Paint.FILTER_BITMAP_FLAG));
            if (cover > 0.985f) return full;        // сплошной квадрат: рисуется как есть
            int[] f = new int[n * n];
            full.getPixels(f, 0, n, 0, 0, n, n);
            float[] radius = profile(f, n);
            if (radius != null) {
                extend(f, n, radius);
                full.setPixels(f, 0, n, 0, 0, n, n);
                return full;
            }
            full.recycle();
        }
        return plate(src, px, n);
    }

    /**
     * Расстояние от центра до края фигуры по направлениям. Null, если фигура не похожа на круг или (скруглённый) квадрат:
     * сердечки, шестиугольники и прочие фигурные логотипы растягивать нельзя.
     */
    private static float[] profile(int[] f, int n) {
        float half = n / 2f;
        float[] res = new float[ANGLES];
        int bad = 0;
        for (int a = 0; a < ANGLES; a++) {
            double an = 2 * Math.PI * a / ANGLES;
            float cos = (float) Math.cos(an), sin = (float) Math.sin(an);
            float r = half * 1.45f;
            for (; r > 0; r -= 1f) {
                int x = (int) (half + cos * r), y = (int) (half + sin * r);
                if (x >= 0 && y >= 0 && x < n && y < n && (f[y * n + x] >>> 24) >= SOLID) break;
            }
            res[a] = r;
            if (r < half * 0.93f) bad++;
        }
        return bad > ANGLES / 25 ? null : res;
    }

    /** Всё за краем фигуры (минус узкая кайма) заливается цветом края на этом же направлении. */
    private static void extend(int[] f, int n, float[] radius) {
        float half = n / 2f;
        int[][] raw = new int[ANGLES][3];
        for (int a = 0; a < ANGLES; a++) {
            double an = 2 * Math.PI * a / ANGLES;
            int[] sum = new int[3];
            for (float k : new float[]{0.9f, 0.885f, 0.87f}) {
                int x = clamp((int) (half + Math.cos(an) * radius[a] * k), n), y = clamp((int) (half + Math.sin(an) * radius[a] * k), n);
                int p = f[y * n + x];
                sum[0] += (p >> 16) & 255; sum[1] += (p >> 8) & 255; sum[2] += p & 255;
            }
            for (int i = 0; i < 3; i++) raw[a][i] = sum[i] / 3;
        }
        // медиана по соседним направлениям убирает рисунок, доходящий до края; затем сглаживание, чтобы не было «лучей»
        int[][] med = new int[ANGLES][3];
        int[] win = new int[17];
        for (int a = 0; a < ANGLES; a++)
            for (int i = 0; i < 3; i++) {
                for (int d = -8; d <= 8; d++) win[d + 8] = raw[(a + d + ANGLES) % ANGLES][i];
                Arrays.sort(win);
                med[a][i] = win[8];
            }
        float[][] col = new float[ANGLES][3];
        for (int a = 0; a < ANGLES; a++)
            for (int i = 0; i < 3; i++) {
                int sum = 0;
                for (int d = -4; d <= 4; d++) sum += med[(a + d + ANGLES) % ANGLES][i];
                col[a][i] = sum / 9f;
            }
        float feather = Math.max(2f, n * 0.012f);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - half, dy = y + 0.5f - half;
                float rho = (float) Math.sqrt(dx * dx + dy * dy);
                if (rho < half * 0.8f) continue;
                double pos = (Math.atan2(dy, dx) / (2 * Math.PI) + 1.0) % 1.0 * ANGLES;
                int a0 = (int) pos % ANGLES, a1 = (a0 + 1) % ANGLES;
                float w = (float) (pos - Math.floor(pos));
                float limit = (radius[a0] * (1 - w) + radius[a1] * w) * 0.912f;
                int p = f[y * n + x];
                float t = (rho - (limit - feather)) / feather;
                if ((p >>> 24) < 250) t = 1f;               // прозрачные и полупрозрачные точки — всегда цвет края
                if (t <= 0f) continue;
                if (t > 1f) t = 1f;
                int rgb = 0xFF000000;
                for (int i = 0; i < 3; i++) {
                    float edge = col[a0][i] * (1 - w) + col[a1][i] * w;
                    int own = (p >> (16 - 8 * i)) & 255;
                    rgb |= Math.round(own * (1 - t) + edge * t) << (16 - 8 * i);
                }
                f[y * n + x] = rgb;
            }
    }

    private static Bitmap wide(Bitmap src, Rect box, int n) {
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int h = Math.max(1, Math.round(n * box.height() / (float) box.width()));
        int top = (n - h) / 2;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        int upper = rowColor(src, box, box.top + 2), lower = rowColor(src, box, box.bottom - 3);
        p.setShader(new LinearGradient(0, top, 0, top + h, upper, lower, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(null);
        c.drawBitmap(src, box, new Rect(0, top, n, top + h), p);
        return out;
    }

    /** Преобладающий цвет строки (по медиане каждого канала). */
    private static int rowColor(Bitmap b, Rect box, int y) {
        int w = box.width();
        int[] row = new int[w];
        b.getPixels(row, 0, w, box.left, Math.max(box.top, Math.min(box.bottom - 1, y)), w, 1);
        int rgb = 0xFF000000;
        int[] v = new int[w];
        for (int i = 0; i < 3; i++) {
            for (int x = 0; x < w; x++) v[x] = (row[x] >> (16 - 8 * i)) & 255;
            Arrays.sort(v);
            rgb |= v[w / 2] << (16 - 8 * i);
        }
        return rgb;
    }

    /** Логотип на ровной плитке: светлой, а если сам логотип светлый — тёмной. */
    private static Bitmap plate(Bitmap src, int[] px, int n) {
        Rect box = bounds(px, n, 40);
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (box == null) { c.drawColor(0xFFF4F5F7); return out; }
        long sum = 0, weight = 0;
        for (int p : px) {
            int a = p >>> 24;
            if (a < SOLID) continue;
            sum += (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
            weight++;
        }
        boolean light = weight > 0 && sum / weight > 190;
        c.drawColor(light ? 0xFF23252B : 0xFFF4F5F7);
        float k = n * 0.68f / Math.max(box.width(), box.height());
        float w = box.width() * k, h = box.height() * k;
        c.drawBitmap(src, box, new RectF((n - w) / 2f, (n - h) / 2f, (n + w) / 2f, (n + h) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static int clamp(int v, int n) { return v < 0 ? 0 : v >= n ? n - 1 : v; }
}
