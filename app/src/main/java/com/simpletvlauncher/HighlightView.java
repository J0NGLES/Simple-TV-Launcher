package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * Одна плавающая рамка фокуса в духе tvOS, «перепрыгивает» с плитки на плитку (лежит ПОД плитками).
 * Режимы: кольцо (плитки приложений: тонкая белая кромка + небольшая мягкая тень строго вниз) и сплошная белая таблетка (кнопки, панель).
 *
 * Лёгкая реализация: рамка с тенью один раз рисуется в готовую картинку (при смене размера или цвета), а движение — только сдвиг
 * и масштаб этой картинки на видеокарте. На каждом кадре ничего не перерисовывается и не пересчитывается.
 */
public class HighlightView extends View {
    private final RectF rect = new RectF();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap bmp, bmp2;
    private android.animation.ValueAnimator anim;
    private float ph;
    private boolean cfgGlow;
    private float margin, radius;
    private int cfgW, cfgH, cfgTint;
    private boolean cfgSolid, dirty;

    public int tint = 0xFFFFFFFF;
    public boolean solid;
    /** Рамка лежит ПОВЕРХ плиток (кольцо): белая кромка перекрывает край плитки, а тень рисуется только снаружи. */
    public boolean over;
    /** Переливающееся белое свечение вместо белой кромки (кольцо плитки). */
    public boolean glow;

    public HighlightView(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVisibility(INVISIBLE);
    }

    /** target — прямоугольник рамки в координатах родителя. */
    public void moveTo(RectF target, float r) {
        if (target.width() < 8 || target.height() < 8) { setVisibility(INVISIBLE); return; }       // пустая цель (вид ещё не размещён) — рамку не показываем
        // всё в целых пикселях: иначе округление размера даёт лишний пиксель рамки справа и снизу
        int m = (int) Math.ceil(Math.max(glow && !solid ? 22f : 10f, target.height() * 0.17f));
        int l = Math.round(target.left), tp = Math.round(target.top), rt = Math.round(target.right), bt = Math.round(target.bottom);
        int w = rt - l + 2 * m, h = bt - tp + 2 * m;
        float x = l - m, y = tp - m;

        boolean changed = w != cfgW || h != cfgH || solid != cfgSolid || tint != cfgTint || glow != cfgGlow || Math.abs(r - radius) > 0.5f;
        if (changed) {
            margin = m;
            radius = r;
            cfgW = w; cfgH = h; cfgSolid = solid; cfgTint = tint; cfgGlow = glow;
            dirty = true;
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                setLayoutParams(lp);
            }
            setPivotX(w / 2f);
            setPivotY(h / 2f);
            invalidate();
        }
        setTranslationX(x);
        setTranslationY(y);
        setVisibility(VISIBLE);
        upd();
    }

    public void hide() {
        setVisibility(INVISIBLE);
    }

    private boolean glowOn() { return cfgGlow && !cfgSolid; }

    /** Анимация идёт только пока свечение включено и рамка видна на экране. */
    private void upd() {
        boolean run = glowOn() && isShown() && getWindowVisibility() == VISIBLE;
        if (run && anim == null) {
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2600);
            anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a -> { ph = (Float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        } else if (!run && anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); upd(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); upd(); }
    @Override protected void onVisibilityChanged(View v, int vis) { super.onVisibilityChanged(v, vis); upd(); }
    @Override protected void onWindowVisibilityChanged(int vis) { super.onWindowVisibilityChanged(vis); upd(); }

    private void bake() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        if (bmp != null && (bmp.getWidth() != w || bmp.getHeight() != h)) { bmp.recycle(); bmp = null; }
        if (bmp2 != null && (!glowOn() || bmp2.getWidth() != w || bmp2.getHeight() != h)) { bmp2.recycle(); bmp2 = null; }
        if (bmp == null) bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        draw(bmp, 0f);
        if (glowOn()) {
            if (bmp2 == null) bmp2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            draw(bmp2, 60f);
        }
    }

    private void draw(Bitmap b, float rot) {
        int w = b.getWidth(), h = b.getHeight();
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        float m = margin;
        rect.set(m, m, w - m, h - m);
        float rh = Math.max(1, rect.height());

        // небольшая мягкая тень без смещения: одинаковая со всех четырёх сторон (смещённая вниз тень выглядела как более толстая нижняя обводка) (размытие на CPU один раз — без ступенек)
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        RectF t = new RectF();
        if (over && !cfgSolid) {
            android.graphics.Path hole = new android.graphics.Path();
            hole.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(hole, android.graphics.Region.Op.DIFFERENCE);
        }
        float[][] layers = {{0.085f, 0f, 0x34}, {0.030f, 0f, 0x2A}};       // размытие, смещение вниз, прозрачность
        for (float[] l : layers) {
            p.setMaskFilter(new BlurMaskFilter(Math.max(2f, rh * l[0]), BlurMaskFilter.Blur.NORMAL));
            p.setColor(((int) l[2]) << 24);
            float dy = rh * l[1];
            t.set(rect.left, rect.top + dy, rect.right, rect.bottom + dy);
            c.drawRoundRect(t, radius, radius, p);
        }
        p.setMaskFilter(null);
        if (over && !cfgSolid) c.restore();

        if (cfgSolid) {
            p.setColor(0xF5FFFFFF);
            c.drawRoundRect(rect, radius, radius, p);
            return;
        }
        // тонкая белая кромка: снаружи торчит всего пара пикселей, а остальная толщина прячется под плиткой и надёжно закрывает любую щель у её края
        // белая кромка (снаружи ow, внутрь заходит на a — закрывает щель у края плитки) и за ней тёмный зазор шириной g:
        // на белых карточках кромка не сливается с самой карточкой
        float ow = Math.max(1.5f, rh / 72f), a = Math.max(1.5f, rh / 90f), g = Math.max(1.8f, rh / 80f);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(ColorUtil.alpha(cfgTint, 0xFF));
        p.setStrokeWidth(ow + a);
        float sh = (a - ow) / 2f;
        RectF r1 = new RectF(rect);
        r1.inset(sh, sh);
        float rr = Math.max(0f, radius - sh);
        if (glowOn()) {
            // нейтральный белый без оттенков: переливается только яркость (белый и светло-серый по кругу)
            android.graphics.SweepGradient sg = new android.graphics.SweepGradient(w / 2f, h / 2f,
                    new int[]{0xFFFFFFFF, 0xFFD6D6D6, 0xFFFFFFFF, 0xFFE8E8E8, 0xFFFFFFFF, 0xFFCCCCCC, 0xFFFFFFFF}, null);
            android.graphics.Matrix mx = new android.graphics.Matrix();
            mx.setRotate(rot, w / 2f, h / 2f);
            sg.setLocalMatrix(mx);
            p.setShader(sg);
            c.save();
            android.graphics.Path hole = new android.graphics.Path();
            hole.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(hole, android.graphics.Region.Op.DIFFERENCE);
            RectF r0 = new RectF(rect);
            r0.inset(-ow, -ow);
            p.setMaskFilter(new BlurMaskFilter(Math.max(8f, rh * 0.09f), BlurMaskFilter.Blur.NORMAL));
            p.setStrokeWidth(Math.max(6f, rh * 0.05f));
            p.setAlpha(0xFF);
            c.drawRoundRect(r0, radius + ow, radius + ow, p);
            p.setMaskFilter(null);
            c.restore();
            p.setStrokeWidth(ow + a);
            p.setAlpha(0xFF);
            c.drawRoundRect(r1, rr, rr, p);
            p.setShader(null);
        } else {
            c.drawRoundRect(r1, rr, rr, p);
        }
        p.setColor(0xF2101018);
        p.setStrokeWidth(g);
        float sh2 = a + g / 2f;
        RectF r2 = new RectF(rect);
        r2.inset(sh2, sh2);
        float rr2 = Math.max(0f, radius - sh2);
        c.drawRoundRect(r2, rr2, rr2, p);
    }

    @Override protected void onDraw(Canvas c) {
        if (cfgW == 0 || getWidth() == 0) return;
        if (getWidth() != cfgW || getHeight() != cfgH) return;      // размер ещё не применён (первый кадр после moveTo) — не рисуем обрывок
        if (dirty || bmp == null) { bake(); dirty = false; }
        c.drawBitmap(bmp, 0, 0, bp);
        if (bmp2 != null && glowOn()) {
            bp.setAlpha((int) (ph * 255));
            c.drawBitmap(bmp2, 0, 0, bp);
            bp.setAlpha(255);
        }
    }
}
