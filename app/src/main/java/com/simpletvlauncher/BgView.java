package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Фон. На главном экране зависит от режима: 0 градиент, 1 картинка, 2 видео (само видео — VideoBg под этим слоем).
 * На остальных экранах (погода, приложения, настройки) — тот же фон, но размытый и неподвижный (если не выключено
 * в настройках): для видео берётся один кадр. Иначе — простой сплошной Material-фон.
 *
 * Градиент заранее рисуется в маленькую картинку (1/6 размера экрана) и дальше выводится как обычная текстура.
 */
public class BgView extends View {
    private static final int DOWN = 6;

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
        int dim = new Prefs(getContext()).bgDim();
        // погода, настройки, список приложений и прочие экраны темнее рабочего стола, чтобы светлые обои не слепили
        if (soft) dim = Math.min(MENU_DIM_MAX, dim + MENU_DIM_EXTRA);
        return Math.round(dim * 2.55f);
    }

    private static final int MENU_DIM_EXTRA = 15,MENU_DIM_MAX = 90;      // в процентах; потолок — чтобы фон не стал совсем чёрным

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
        if (main) Frost.light = new Prefs(getContext()).glass() == 0;
        int sg = (dimAlpha() << 8) | sat() | (mode << 20) | (UI.contrast ? 1 << 28 : 0) | (main ? new Prefs(getContext()).glass() << 24 : 0);
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
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (main) Frost.light = new Prefs(getContext()).glass() == 0;
        blurredBmp = null;
        build();
        prepareStill();
    }

    /** Слегка размытая копия фона-картинки (с затемнением) для матовых элементов. Для градиента не нужна, для видео на рабочем столе недоступна. */
    Bitmap blurred() {
        if (!image() || backdrop == null || getWidth() == 0) return null;
        if (blurredBmp != null) return blurredBmp;
        int w = getWidth(), h = getHeight();
        int fx = main ? new Prefs(getContext()).glass() : 1;
        int down = fx == 0 ? 2 : 4;      // «лёгкий»: копия вдвое детальнее и без прохода размытия — фон лишь слегка смягчён
        int bw = Math.max(8, w / down), bh = Math.max(8, h / down);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF07050B);
        cover(c, backdrop, bw, bh);
        drawDim(c, bw, bh);
        // настоящее размытие (три прохода — по сути гауссово) на уменьшенной копии; при растяжении на экран блоков не видно
        blurredBmp = fx == 0 ? b : Backdrop.blur(b, fx == 2 ? 4 : 1);      // «сильный» эффект стекла — то же размытие с широким окном
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
        c.drawColor(solid ? 0xFF0A0F1C : 0xFF07050B);
        Shader a = new RadialGradient(bw * 0.30f, bh * 0.30f, bw * 0.78f,
                new int[]{ColorUtil.alpha(accent, 0xA0), ColorUtil.alpha(accent, 0x38), 0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        Shader d = new RadialGradient(bw * 0.92f, bh * 1.02f, bw * 0.6f,
                new int[]{ColorUtil.alpha(ColorUtil.darker(accent, 0.8f), 0x78), 0},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        q.setAlpha(solid ? 150 : 255);
        q.setShader(a); c.drawRect(0, 0, bw, bh, q);
        q.setShader(d); c.drawRect(0, 0, bw, bh, q);
        if (!solid) {
            q.setAlpha(255);
            q.setShader(new LinearGradient(0, bh * 0.45f, 0, bh, 0, 0x88000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, q);
        }
        return b;
    }

    private final Paint dimPaint = new Paint();

    /** Затемнение отдельной кистью, чтобы не портить прозрачность кисти, которой рисуется картинка. */
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
        if (cur == null) { c.drawColor(solid ? 0xFF0A0F1C : 0xFF07050B); return; }
        dst.set(0, 0, w, h);
        p.setAlpha(255);
        c.drawBitmap(cur, null, dst, p);
    }
}
