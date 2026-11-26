package com.simpletvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Фон. На главном экране зависит от режима: 0 градиент, 1 картинка, 2 видео (само видео — VideoBg под этим слоем).
 * На остальных экранах (погода, приложения, настройки) — тот же фон, но размытый и неподвижный (если не выключено
 * в настройках): для видео берётся один кадр. Иначе — простой сплошной Material-фон.
 *
 * Градиент рисуется в текстуру (1/6 размера экрана); при включённой анимации два блика движутся независимо.
 */
public class BgView extends View {
    private static final int DOWN = 6;
    private static final int GRADIENT_BASE = 0xFF07050B;

    private int accent;
    private final boolean main;
    private boolean solid;      // простой тёмный фон (остальные экраны без размытого фона)
    private boolean soft;       // остальные экраны с размытым фоном рабочего стола
    private int mode = -1;
    private String sig = "";
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect dst = new Rect();
    private final Matrix m = new Matrix();
    private Bitmap backdrop;
    private Bitmap cur;
    private Bitmap blurredBmp;
    private VideoBg live;
    private boolean animateGradient, homeResumed;
    private GradientDrift drift;

    /** Готовые градиенты по цвету — не пересчитываем при каждом возврате на экран. */
    private static final Map<Long, Bitmap> CACHE = new LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> e) { return size() > 8; }
    };

    public BgView(Context c, int color) {
        super(c);
        accent = color;
        main = c instanceof MainActivity;
        solid = !main;
        setFocusable(false);
        if (!main) refresh(color);
    }

    public void setMode(int mode) {
        if (this.mode == mode && (cur != null || mode >= 1)) {
            if (mode >= 1) reloadBackdrop();        // картинку или видео могли выбрать заново
            return;
        }
        this.mode = mode;
        cur = null;
        build();
        reloadBackdrop();
        invalidate();
        syncLook();
    }

    /** Остальные экраны: подхватить настройки (тот же фон размытым или простой тёмный). Ничего не делает, если ничего не изменилось. */
    public void refresh(int color) {
        if (main) { setAccent(color); return; }
        Context c = getContext();
        Prefs pr = new Prefs(c);
        boolean s = pr.bgAll();
        int md = s ? pr.bgMode() : -1;
        if (md == 1 && !Bg.has(c)) md = 0;
        if (md == 2 && !Bg.hasVideo(c)) md = 0;
        String sg = (s ? 1 : 0) + ":" + md + ":" + color + ":" + Bg.sig(c);
        if (sg.equals(sig)) return;
        sig = sg;
        soft = s;
        solid = !s;
        accent = color;
        mode = md;
        cur = null;
        backdrop = null;
        blurredBmp = null;
        build();
        if (soft) {
            Frost.bg = this;        // матовые панели на этом экране берут размытие из этого же фона
            reloadBackdrop();
        }
        invalidate();
        syncLook();
    }

    /** Фон — картинка (на рабочем столе) или размытая картинка/кадр видео (на остальных экранах). */
    private boolean image() {
        return !solid && (mode == 1 || (soft && mode == 2));
    }

    public void reloadBackdrop() {
        if (!solid && mode == 2) {
            loadFrame();
            return;
        }
        if (!image()) {
            backdrop = null;
            invalidate();
            return;
        }
        Bg.Cb cb = new Bg.Cb() {
            @Override public void done(Bitmap bmp) {
                boolean fadeIt = wantFade && main && !soft && bmp != null && backdrop != null && bmp != backdrop && getWidth() > 0;
                wantFade = false;
                if (fadeIt) { startFade(bmp); return; }
                cancelFade();
                backdrop = bmp;
                blurredBmp = null;
                invalidateTree(getRootView());
            }
        };
        if (main) prepareSoft();
        if (soft) Bg.requestSoft(getContext(), cb); else Bg.request(getContext(), cb);
    }

    /**
     * Размытую картинку для остальных экранов готовим заранее, пока виден рабочий стол. Иначе при первом открытии
     * погоды, настроек или списка приложений фон сначала тёмный, а картинка появляется позже — это выглядит как моргание.
     */
    private void prepareSoft() {
        if (!new Prefs(getContext()).bgAll()) return;
        Bg.requestSoft(getContext(), new Bg.Cb() {
            @Override public void done(Bitmap b) {}         // нужна только копия в кэше
        });
    }

    // ---- плавная смена картинки (автосмена обоев на рабочем столе) ----
    private boolean wantFade, blurSwitched;
    private float fade = 1f;
    private Bitmap prevBackdrop;
    private android.animation.ValueAnimator fadeAnim;

    /** Следующая загруженная картинка не заменит прежнюю сразу, а плавно проявится поверх неё. */
    public void fadeNext() { wantFade = true; }

    private void cancelFade() {
        if (fadeAnim != null) { fadeAnim.removeAllListeners(); fadeAnim.cancel(); fadeAnim = null; }
        prevBackdrop = null;
        fade = 1f;
    }

    private void startFade(Bitmap next) {
        cancelFade();
        prevBackdrop = backdrop;
        backdrop = next;
        fade = 0f;
        blurSwitched = false;
        fadeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        fadeAnim.setDuration(1400);
        fadeAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                fade = (Float) a.getAnimatedValue();
                if (!blurSwitched && fade >= 0.5f) {
                    // матовые панели переходят на размытие новой картинки на середине смены (оно считается заново)
                    blurSwitched = true;
                    blurredBmp = null;
                    invalidateTree(getRootView());
                } else {
                    invalidate();
                }
            }
        });
        fadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                fadeAnim = null;
                prevBackdrop = null;
                fade = 1f;
                if (!blurSwitched) { blurSwitched = true; blurredBmp = null; }
                invalidateTree(getRootView());
            }
        });
        fadeAnim.start();
    }

    /** Размытый кадр видео: на остальных экранах это сам фон, на рабочем столе — только подложка для стеклянных меню
     *  (живое видео в снимок окна не попадает, см. Backdrop). */
    private void loadFrame() {
        SoftVideo.request(getContext(), new SoftVideo.Cb() {
            @Override public void done(Bitmap frame) {
                if (solid || mode != 2) return;
                backdrop = frame;
                blurredBmp = null;
                if (frame != null) {
                    prepareStill();
                } else if (soft) {          // видео не читается — на остальных экранах остаётся градиент
                    mode = 0;
                    build();
                } else if (live != null) {  // на рабочем столе можно снять кадр с самого идущего видео
                    postDelayed(new Runnable() {
                        @Override public void run() { grabLive(); }
                    }, 3000);
                }
                invalidateTree(getRootView());
            }
        });
    }

    /** Рабочий стол: само видео, чтобы при неудаче чтения файла снять с него кадр для подложки меню. */
    public void setLive(VideoBg v) { live = v; }

    private void grabLive() {
        if (live == null || mode != 2 || backdrop != null || getWidth() == 0) return;
        Bitmap raw = live.still(160, Math.max(2, Math.round(160f * getHeight() / getWidth())));
        Bitmap f = raw == null ? null : SoftVideo.store(getContext(), raw);
        if (f == null) return;
        backdrop = f;
        prepareStill();
    }

    private int dimAlpha() {
        int da = Math.round(new Prefs(getContext()).bgDim() * 2.55f);
        return soft ? Math.round(Math.min(255, da + 0x1C) * 0.8f) : da;      // на остальных экранах затемнение на 20% слабее
    }

    /** Готовит подложку для стеклянных меню (см. Backdrop). */
    void prepareStill() { stillReady(); }

    boolean stillReady() {
        Bitmap src = stillSource();
        if (src == null || getWidth() == 0) return false;
        Backdrop.ensureStill(src, getWidth(), getHeight(), mode == 0 ? 0 : dimAlpha(), sat());
        return Backdrop.hasStill(Backdrop.stillKey(getWidth(), getHeight(), mode == 0 ? 0 : dimAlpha(), sat(), src));
    }

    /** Насыщенность из настроек; для градиента и простого фона не применяется. */
    private int sat() { return solid || mode < 1 ? 100 : new Prefs(getContext()).bgSat(); }

    private int satCache = -1;
    private android.graphics.ColorMatrixColorFilter satFilter;

    private android.graphics.ColorMatrixColorFilter satFilter() {
        int s = sat();
        if (s >= 100) return null;
        if (s != satCache || satFilter == null) {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(s / 100f);
            satFilter = new android.graphics.ColorMatrixColorFilter(cm);
            satCache = s;
        }
        return satFilter;
    }

    private int lookSig = -1;

    /** Затемнение или насыщенность изменили в настройках: сбросить готовые копии фона и перерисовать всё сразу. */
    public void syncLook() {
        int sg = (dimAlpha() << 8) | sat() | (mode << 20);
        if (sg == lookSig) return;
        lookSig = sg;
        blurredBmp = null;
        if (main && live != null) {            // живое видео лежит под этим слоем: насыщенность задаём слою самого видео
            android.graphics.ColorMatrixColorFilter f = mode == 2 ? satFilter() : null;
            if (f == null) live.setLayerType(LAYER_TYPE_NONE, null);
            else { Paint lp = new Paint(); lp.setColorFilter(f); live.setLayerType(LAYER_TYPE_HARDWARE, lp); }
        }
        prepareStill();
        invalidateTree(getRootView());
    }

    /** Из чего строится подложка меню: видео — детальный кадр, картинка — она сама, градиент — его заготовка. */
    private Bitmap stillSource() {
        if (solid) return null;
        if (mode == 2) {
            if (backdrop == null) return null;
            Bitmap d = SoftVideo.detail();
            return d != null ? d : backdrop;
        }
        return mode == 1 ? backdrop : cur;
    }
    public void setAccent(int c) {
        if (!main) { refresh(c); return; }
        if (c == accent && cur != null) return;
        accent = c;
        build();
        rebuildDrift();
        invalidate();
    }

    void setGradientDrift(boolean enabled) {
        if (animateGradient == enabled) return;
        animateGradient = enabled;
        rebuildDrift();
        invalidate();
    }

    void setHomeResumed(boolean resumed) {
        homeResumed = resumed;
        syncDrift();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        syncDrift();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncDrift();
    }

    @Override protected void onDetachedFromWindow() {
        if (drift != null) drift.setRunning(false);
        super.onDetachedFromWindow();
    }

    private void rebuildDrift() {
        if (drift != null) drift.setRunning(false);
        int w = getWidth(), h = getHeight();
        drift = animateGradient && mode == 0 && w > 0 && h > 0 ? new GradientDrift(w, h) : null;
        syncDrift();
    }

    private void syncDrift() {
        if (drift == null) return;
        boolean systemAnimations = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        drift.setRunning(homeResumed && isAttachedToWindow() && hasWindowFocus()
                && getWindowVisibility() == VISIBLE && systemAnimations);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        blurredBmp = null;
        build();
        rebuildDrift();
        prepareStill();
    }

    /** Слегка размытая копия фона-картинки (с затемнением) для матовых элементов. Для градиента не нужна, для видео на рабочем столе недоступна. */
    Bitmap blurred() {
        if (!image() || backdrop == null || getWidth() == 0) return null;
        if (blurredBmp != null) return blurredBmp;
        int w = getWidth(), h = getHeight();
        int bw = Math.max(8, w / 4), bh = Math.max(8, h / 4);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF07050B);
        cover(c, backdrop, bw, bh);
        drawDim(c, bw, bh);
        // настоящее размытие (три прохода — по сути гауссово) на уменьшенной копии; при растяжении на экран блоков не видно
        blurredBmp = Backdrop.blur(b, 1);
        return blurredBmp;
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    private void build() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || mode >= 1) return;
        if (mode < 0 && !solid) return;
        long key = ((long) (accent & 0xFFFFFFFFL) << 20) | ((long) w << 1) | (solid ? 1 : 0);
        Bitmap b;
        synchronized (CACHE) { b = CACHE.get(key); }
        if (b == null) {
            b = bake(w, h);
            synchronized (CACHE) { CACHE.put(key, b); }
        }
        cur = b;
    }

    private Bitmap bake(int w, int h) {
        int bw = Math.max(8, w / DOWN), bh = Math.max(8, h / DOWN);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint q = new Paint(Paint.DITHER_FLAG);
        c.drawColor(solid ? 0xFF0A0F1C : GRADIENT_BASE);
        q.setAlpha(solid ? 150 : 255);
        q.setShader(upperGlow(bw, bh)); c.drawRect(0, 0, bw, bh, q);
        q.setShader(lowerGlow(bw, bh)); c.drawRect(0, 0, bw, bh, q);
        if (!solid) {
            q.setAlpha(255);
            q.setShader(gradientShade(bh));
            c.drawRect(0, 0, bw, bh, q);
        }
        return b;
    }

    private Shader upperGlow(int w, int h) {
        return new RadialGradient(w * 0.30f, h * 0.30f, w * 0.78f,
                new int[]{ColorUtil.alpha(accent, 0xA0), ColorUtil.alpha(accent, 0x38), 0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }

    private Shader lowerGlow(int w, int h) {
        return new RadialGradient(w * 0.92f, h * 1.02f, w * 0.6f,
                new int[]{ColorUtil.alpha(ColorUtil.darker(accent, 0.8f), 0x78), 0},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    private static Shader gradientShade(int h) {
        return new LinearGradient(0, h * 0.45f, 0, h, 0, 0x88000000, Shader.TileMode.CLAMP);
    }

    private static Bitmap bakeGlow(int w, int h, Shader shader) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.DITHER_FLAG);
        paint.setShader(shader);
        new Canvas(bitmap).drawRect(0, 0, w, h, paint);
        return bitmap;
    }

    private final class GradientDrift implements Runnable {
        // These slow glows only need about 30 redraws per second.
        private static final long FRAME_DELAY_MS = 32;
        private final DriftGlow upper, lower;
        private final Paint shade = new Paint(Paint.DITHER_FLAG);
        private final RectF bounds = new RectF();
        private long startedAt = -1, elapsed;

        GradientDrift(int w, int h) {
            int bw = Math.max(8, w / DOWN), bh = Math.max(8, h / DOWN);
            upper = new DriftGlow(bakeGlow(bw, bh, upperGlow(bw, bh)), 52_000, 0.065f, 0.04f, -0.04f, -0.025f);
            lower = new DriftGlow(bakeGlow(bw, bh, lowerGlow(bw, bh)), 71_000, -0.065f, -0.04f, 0.04f, 0.015f);
            shade.setShader(gradientShade(h));
        }

        void setRunning(boolean running) {
            if (running == (startedAt >= 0)) return;
            long now = SystemClock.uptimeMillis();
            if (running) {
                startedAt = now;
                invalidate();
            } else {
                elapsed += now - startedAt;
                startedAt = -1;
                removeCallbacks(this);
            }
        }

        boolean hasStarted() { return startedAt >= 0 || elapsed > 0; }

        @Override public void run() { invalidate(); }

        void draw(Canvas c, int w, int h) {
            long time = elapsed + (startedAt >= 0 ? SystemClock.uptimeMillis() - startedAt : 0);
            c.drawColor(GRADIENT_BASE);
            upper.draw(c, p, bounds, w, h, time);
            lower.draw(c, p, bounds, w, h, time);
            c.drawRect(0, h * 0.45f, w, h, shade);
            if (startedAt >= 0 && !Backdrop.capturing) {
                removeCallbacks(this);
                postOnAnimationDelayed(this, FRAME_DELAY_MS);
            }
        }
    }

    private static final class DriftGlow {
        private static final PathInterpolator EASE = new PathInterpolator(0.42f, 0f, 0.58f, 1f);
        // Extend each texture by 7% on all sides so it covers the viewport throughout its path.
        private static final float OVERSCAN = 0.07f;
        private static final int MIN_GLOW_ALPHA = 209;
        private final Bitmap bitmap;
        private final long period;
        private final float inwardX, inwardY, outwardX, outwardY;

        DriftGlow(Bitmap bitmap, long period, float inwardX, float inwardY, float outwardX, float outwardY) {
            this.bitmap = bitmap;
            this.period = period;
            this.inwardX = inwardX;
            this.inwardY = inwardY;
            this.outwardX = outwardX;
            this.outwardY = outwardY;
        }

        void draw(Canvas c, Paint paint, RectF bounds, int w, int h, long time) {
            float progress = (time % period) / (float) period;
            float x = offset(progress, inwardX, outwardX);
            float y = offset(progress, inwardY, outwardY);
            bounds.set(w * (x - OVERSCAN), h * (y - OVERSCAN),
                    w * (1 + x + OVERSCAN), h * (1 + y + OVERSCAN));
            float inwardFraction = (x - outwardX) / (inwardX - outwardX);
            paint.setAlpha(MIN_GLOW_ALPHA + Math.round((255 - MIN_GLOW_ALPHA) * inwardFraction));
            c.drawBitmap(bitmap, null, bounds, paint);
        }

        private static float offset(float progress, float first, float second) {
            if (progress < 0.35f) return first * EASE.getInterpolation(progress / 0.35f);
            if (progress < 0.72f) return first + (second - first) * EASE.getInterpolation((progress - 0.35f) / 0.37f);
            return second * (1 - EASE.getInterpolation((progress - 0.72f) / 0.28f));
        }
    }

    private final Paint dimPaint = new Paint();

    /** Пользовательское затемнение отдельной кистью, чтобы не портить прозрачность кисти, которой рисуется картинка.
     *  На остальных экранах добавляется ещё немного, чтобы текст читался на светлых кадрах. */
    private void drawDim(Canvas c, int w, int h) {
        int da = dimAlpha();
        if (da <= 0) return;
        dimPaint.setColor(da << 24);
        c.drawRect(0, 0, w, h, dimPaint);
    }

    /** Картинка на весь холст с обрезкой по центру. */
    private void cover(Canvas c, Bitmap b, int w, int h) { cover(c, b, w, h, 255); }

    private void cover(Canvas c, Bitmap b, int w, int h, int alpha) {
        float sc = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        m.setScale(sc, sc);
        m.postTranslate((w - b.getWidth() * sc) / 2f, (h - b.getHeight() * sc) / 2f);
        p.setAlpha(alpha);
        p.setColorFilter(satFilter());
        c.drawBitmap(b, m, p);
        p.setColorFilter(null);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (!solid && mode == 2 && !soft) {
            if (Backdrop.capturing && backdrop != null) {       // снимок под меню: вместо живого видео — его кадр
                c.drawColor(0xFF07050B);
                cover(c, backdrop, w, h);
            }
            drawDim(c, w, h);       // видео лежит под этим слоем: только затемнение для читаемости текста
            return;
        }
        if (image()) {
            c.drawColor(0xFF07050B);
            if (backdrop != null) {
                if (prevBackdrop != null && fade < 1f) {        // смена картинки: прежняя остаётся снизу, новая проявляется сверху
                    cover(c, prevBackdrop, w, h);
                    cover(c, backdrop, w, h, Math.round(255 * fade));
                } else {
                    cover(c, backdrop, w, h);
                }
                drawDim(c, w, h);       // затемнение задаёт пользователь
            }
            return;
        }
        if (drift != null && drift.hasStarted()) {
            drift.draw(c, w, h);
            return;
        }
        if (cur == null) { c.drawColor(solid ? 0xFF0A0F1C : GRADIENT_BASE); return; }
        dst.set(0, 0, w, h);
        p.setAlpha(255);
        c.drawBitmap(cur, null, dst, p);
    }
}
