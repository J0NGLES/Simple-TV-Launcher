package com.simpletvlauncher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

/**
 * «Жидкое стекло» (анимации в стиле tvOS): одна стеклянная пластина на окно, которая на пружинах перелетает
 * за выделением — по плиткам, кнопкам, строкам меню и настроек.
 *
 * Внешний вид и поведение перенесены из CleanYourTV (https://github.com/Gathin23/cleanyourtv),
 * MIT License, Copyright (c) 2026 Gathin. Полный текст лицензии: assets/licenses.txt.
 * Отличия: пружина своя (без AndroidX), всё движение примерно на четверть быстрее оригинала, стекло работает на всех экранах.
 *
 * Пластина лежит в наложении (overlay) окна и на разметку экранов не влияет. Перед выводом кадра она смотрит, где сейчас
 * выбранный элемент: прокрутку повторяет точно, а смену элемента или его места проходит пружиной.
 * Пока всё стоит на месте, ни одного кадра не запрашивается.
 */
final class LiquidGlass extends View implements Choreographer.FrameCallback, ViewTreeObserver.OnPreDrawListener {
    /** Увеличение выбранной плитки и длительности: в оригинале 1.10, 260 и 180 мс. */
    static final float SCALE = 1.10f, OVERSHOOT = 1.6f;
    static final int TILE_IN = 210, TILE_OUT = 145;
    private static final int FADE_IN = 150, FADE_OUT = 130;
    /** Пружина: в оригинале жёсткость 320; затухание то же — от него зависит «желейность». */
    private static final float STIFFNESS = 480f, DAMPING = 0.58f;
    /**
     * Самый большой отскок в единицах макета. При постоянном затухании он растёт вместе с расстоянием, и дальний перелёт
     * (например, с нижней панели на верхнюю) проскакивал слишком сильно. Короткие переходы этот предел не меняет.
     */
    private static final float MAX_BOUNCE = 36f;

    private static final int RIM = 0xCCFFFFFF, GLOW_ALPHA = 0x30, ACCENT_GLOW_ALPHA = 0x70, GLOW_RINGS = 12;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), ring = new RectF();
    private final int[] at = new int[2];
    private final ViewGroup host;
    private final float inset, maxBounce;
    private double damping = DAMPING;   // затухание текущего перелёта
    private float radius;
    private int accent;                 // 0 — обычное белое стекло
    private View target;
    private int targetLeft, targetTop;
    private float cx, cy;               // где сейчас середина пластины
    private float offX, offY, velX, velY;   // насколько она ещё не долетела до выбранного элемента
    private boolean shown, running, held;
    private long last;

    /** Подключить стекло к окну экрана или диалога. Видно оно только при включённых анимациях. */
    static LiquidGlass watch(Window w) {
        ViewGroup host = (ViewGroup) w.getDecorView();
        LiquidGlass g = new LiquidGlass(host);
        host.getOverlay().add(g);
        host.getViewTreeObserver().addOnPreDrawListener(g);
        return g;
    }

    private LiquidGlass(ViewGroup host) {
        super(host.getContext());
        this.host = host;
        float unit = UI.scale(host.getContext());
        inset = 12 * unit;              // место под свечение вокруг пластины
        maxBounce = MAX_BOUNCE * unit;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, 3 * unit));
        rim.setColor(RIM);
        glow.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        setAlpha(0f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override public boolean onPreDraw() {
        follow();
        return true;
    }

    private void follow() {
        View f = UI.tvos && !held ? host.findFocus() : null;
        if (!(f instanceof GlassView) || f.getWidth() == 0 || f.getAlpha() == 0f || !f.isShown()) {
            fadeOut();
            return;
        }
        GlassView g = (GlassView) f;
        int w = g.getWidth(), h = g.getHeight();
        float scale = g.liquidScale();
        // размер и скругление меняются сразу, пружинит только положение
        int bw = Math.round(w * scale + 2 * inset), bh = Math.round(h * scale + 2 * inset);
        float rr = Math.min(g.radius * scale, Math.min(w, h) * scale / 2f);
        if (bw != getWidth() || bh != getHeight()) {
            layout(0, 0, bw, bh);
            invalidate();
        }
        if (Math.abs(rr - radius) > 0.5f) {
            radius = rr;
            invalidate();
        }
        g.getLocationInWindow(at);
        float x = at[0] + w * g.getScaleX() / 2f, y = at[1] + h * g.getScaleY() / 2f;
        if (!shown) {                   // первое появление — сразу на месте, только проявляется
            stop();
            offX = offY = 0;
            shown = true;
            animate().cancel();
            animate().alpha(1f).setDuration(FADE_IN).start();
        } else if (g != target) {       // выделение перешло дальше: скорость пружины сохраняется
            offX = cx - x;
            offY = cy - y;
            start();
        } else if (g.getLeft() != targetLeft || g.getTop() != targetTop) {  // плитку переставили
            offX -= g.getLeft() - targetLeft;
            offY -= g.getTop() - targetTop;
            start();
        }
        target = g;
        targetLeft = g.getLeft();
        targetTop = g.getTop();
        cx = x + offX;
        cy = y + offY;
        setTranslationX(cx - bw / 2f);
        setTranslationY(cy - bh / 2f);
    }

    private void start() {
        damping = dampingFor(Math.hypot(offX, offY));
        if (running) return;
        running = true;
        last = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Затухание, при котором отскок после перелёта на distance не превышает предела. */
    private double dampingFor(double distance) {
        if (distance <= maxBounce) return DAMPING;
        double ln = Math.log(distance / maxBounce);            // отскок затухающей пружины: distance · e^(−πζ/√(1−ζ²))
        return Math.max(DAMPING, ln / Math.sqrt(Math.PI * Math.PI + ln * ln));
    }

    /** Плавно убрать: выделять нечего. */
    private void fadeOut() {
        if (!shown) return;
        shown = false;
        target = null;
        stop();
        animate().cancel();
        animate().alpha(0f).setDuration(FADE_OUT).start();
    }

    /** Убрать сразу: экран перестраивается, следующее появление будет без перелёта. */
    void reset() {
        shown = false;
        target = null;
        stop();
        animate().cancel();
        setAlpha(0f);
    }

    /** Пока экран не готов, стекло не показывается. */
    void hold(boolean on) {
        held = on;
        if (on) reset();
    }

    /** Цветная кромка и свечение (перемещение приложения); 0 — обычное стекло. */
    void setAccent(int color) {
        if (color == accent) return;
        accent = color;
        if (color == 0) rim.setColor(RIM);
        else rim.setColor(android.graphics.Color.argb(0xE6,
                (android.graphics.Color.red(color) + 510) / 3,
                (android.graphics.Color.green(color) + 510) / 3,
                (android.graphics.Color.blue(color) + 510) / 3));
        invalidate();
    }

    private void stop() {
        velX = velY = 0;
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void doFrame(long time) {
        if (!running) return;
        float dt = Math.max(0f, Math.min(0.064f, (time - last) / 1e9f));
        last = Math.max(last, time);
        float[] x = spring(offX, velX, dt, damping), y = spring(offY, velY, dt, damping);
        offX = x[0];
        velX = x[1];
        offY = y[0];
        velY = y[1];
        if (rest(x) && rest(y)) {
            offX = offY = 0;
            stop();
        } else Choreographer.getInstance().postFrameCallback(this);
        if (target != null) {
            cx = target.getWidth() * target.getScaleX() / 2f;
            target.getLocationInWindow(at);
            cx += at[0] + offX;
            cy = at[1] + target.getHeight() * target.getScaleY() / 2f + offY;
            setTranslationX(cx - getWidth() / 2f);
            setTranslationY(cy - getHeight() / 2f);
        }
    }

    private static boolean rest(float[] s) { return Math.abs(s[0]) < 0.75f && Math.abs(s[1]) < 45f; }

    /** Точное решение затухающей пружины (масса 1) за время dt: смещение от цели и скорость. Новая цель не обнуляет скорость. */
    private static float[] spring(float off, float vel, float dt, double zeta) {
        double w0 = Math.sqrt(STIFFNESS), g = zeta * w0, wd = w0 * Math.sqrt(1 - zeta * zeta);
        double e = Math.exp(-g * dt), cos = Math.cos(wd * dt), sin = Math.sin(wd * dt), b = (vel + g * off) / wd;
        double pos = e * (off * cos + b * sin);
        double v = e * (cos * (b * wd - g * off) - sin * (off * wd + g * b));
        return new float[]{(float) pos, (float) v};
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        shine.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        box.set(inset, inset, w - inset, h - inset);
        int rgb = (accent != 0 ? accent : 0xFFFFFF) & 0xFFFFFF;
        int alpha = accent != 0 ? ACCENT_GLOW_ALPHA : GLOW_ALPHA;
        float step = inset / GLOW_RINGS;
        glow.setStrokeWidth(step + 0.5f);
        for (int i = 0; i < GLOW_RINGS; i++) {
            float o = step * (i + 0.5f), t = 1f - i / (float) GLOW_RINGS;
            glow.setColor((Math.round(alpha * t) << 24) | rgb);
            ring.set(box);
            ring.inset(-o, -o);
            c.drawRoundRect(ring, radius + o, radius + o, glow);
        }
        c.drawRoundRect(box, radius, radius, fill);
        c.save();
        c.clipRect(0, 0, w, h * 0.45f);
        c.drawRoundRect(box, radius, radius, shine);
        c.restore();
        c.drawRoundRect(box, radius, radius, rim);
    }
}
