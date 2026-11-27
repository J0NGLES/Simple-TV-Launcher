package com.simpletvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Главный экран: единственное место со «стеклянным» оформлением. */
public class MainActivity extends Activity {
    static final int SET_COUNT = 3;

    private Prefs prefs;
    private float s;
    private int rw, rh;
    private float clockTextW, botGap;
    private boolean recreating;
    private int W, H;
    private float mx, gap;
    private float pitch, rowPitch, tw, th, vy;
    private int cols;
    private int contentH;

    private FrameLayout root, content;
    private ScrollView sv;
    private BgView bg;
    private VideoBg video;
    private HighlightView hlTop, hlGrid, hlTopR, hlGridR;     // под плитками (таблетка, тень) и поверх (кольцо)
    private LiquidGlass liquid;                 // стекло анимаций tvOS
    private boolean liquidOn;
    private HighlightView[] hls() { return new HighlightView[]{hlTop, hlGrid, hlTopR, hlGridR}; }
    private GlassView weatherPill, capsule, btnVpn, btnNet, btnGear, btnSource, btnSearch, btnApps, btnLauncher;
    private int langV;                  // версия языка, с которой построен экран
    private float fsBuilt;              // размер шрифта, с которым построен экран
    private float tkBuilt;              // размер названия приложения, с которым построен экран
    private int shapeBuilt;       // форма плиток, с которой построен экран
    private long lastHome = -10000;
    private static final long UNLOCK_MS = 3 * 60 * 1000L;
    private static long unlockUntil;    // общее для всего приложения время, а не число входов: после жеста всё открыто ровно 3 минуты          // «режим для бабушек»: до этого момента меню временно разблокировано
    private final long[] seqT = new long[6];
    private final int[] seqK = new int[6];
    private android.widget.Toast kidToast;
    private FrostPanel frost;
    private final List<android.media.tv.TvInputInfo> inputs = new ArrayList<>();

    /** ScrollView, у которого верхний край прокрутки плавно уходит в прозрачность (маска DST_OUT). */
    static class FadeScroll extends ScrollView {
        private final Paint fp = new Paint();
        private final int fade;
        /** Сколько сверху занято названием приложения: прокрученные плитки туда не заезжают. */
        int cut;
        FadeScroll(Context c, int fade) {
            super(c);
            this.fade = fade;
            fp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            fp.setShader(new android.graphics.LinearGradient(0, 0, 0, fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }   // прокруткой при фокусе управляет лаунчер (ensureVisible), с отступами
        /** Фокус двигает только лаунчер (FocusFinder + ensureVisible): собственные «прыжки» ScrollView съедали нижний отступ. */
        @Override public boolean arrowScroll(int direction) { return false; }
        @Override protected void dispatchDraw(Canvas c) {
            int sy = getScrollY();
            if (sy <= 0) { super.dispatchDraw(c); return; }
            int w = getWidth(), hh = getHeight();
            int off = Math.min(Math.max(0, cut), sy);      // растёт вместе с прокруткой, без скачка в начале
            // основная часть рисуется как обычно, без верхней полосы
            c.save();
            c.clipRect(0, sy + off + fade, w, sy + hh);
            super.dispatchDraw(c);
            c.restore();
            // плавное затухание сверху: отдельный небольшой слой только под саму полосу (дешевле, чем слой на весь список)
            int sc = c.saveLayer(0, sy + off, w, sy + off + fade, null);
            c.clipRect(0, sy + off, w, sy + off + fade);
            super.dispatchDraw(c);
            c.save();
            c.translate(0, sy + off);
            fp.setAlpha(Math.round(255 * Math.min(1f, sy / (float) fade)));
            c.drawRect(0, 0, w, fade, fp);
            c.restore();
            c.restoreToCount(sc);
        }
    }

    /** Матовая панель за сеткой приложений (как «dock» в tvOS). */
    static class FrostPanel extends View {
        float radius = 40;
        FrostPanel(Context c) { super(c); setFocusable(false); }
        @Override protected void onDraw(Canvas c) {
            Frost.draw(this, c, getWidth(), getHeight(), radius);
        }
    }
    private final GlassView[] setTiles = new GlassView[SET_COUNT];
    private OutlineText clock, dateTv, shelfTitle, appsHdr, setHdr;

    private List<AppRepo.AppItem> all = new ArrayList<>(), visible = new ArrayList<>();
    private final List<AppTile> tiles = new ArrayList<>();
    private final Map<View, RectF> slots = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    private boolean moving;
    private int pendingFocus = -1;
    private String deletePkg;           // приложение, для которого открыт системный диалог удаления
    private int deleteIdx;              // его место в сетке: после удаления выбор переходит на соседнее приложение
    private long deleteAt;
    private AppTile movingTile;
    private boolean vpn, eth, wifi;
    private boolean noInet;                 // сеть подключена, но интернета нет (показываем перечёркнутый значок)
    private long badSince;                  // с какого момента система считает интернет недоступным (0 — доступен)
    private Weather.Data wd;
    private String sig = "";
    private Dialog dialog;
    private ConnectivityManager.NetworkCallback netCb;
    private boolean resumed;

    // ------------------------------------------------------------ плитка приложения
    static class AppTile extends GlassView {
        final AppRepo.AppItem item;
        final int fallback;
        boolean square;             // квадратный значок вместо баннера

        AppTile(Context c, AppRepo.AppItem it, int fallback) {
            super(c);
            item = it;
            this.fallback = fallback;
            floating = true;
        }

        /** Плитка увеличивается как в tvOS, без ограничения для крупных; при перемещении — крупнее обычного. */
        @Override float liquidScale() { return Math.max(focusScale, LiquidGlass.SCALE); }

        @Override protected void drawContent(Canvas c) {
            int w = getWidth(), hh = getHeight();
            if (square && item.icon != null) {
                drawSquare(c, w);
                return;
            }
            if (item.banner != null) {
                cover(c, item.banner, w, hh);
                return;
            }
            if (item.icon != null) drawIconCard(c, w, hh);
        }

        /** Квадратная плитка: картинка готовится один раз под размер плитки (см. SquareIcon). */
        private void drawSquare(Canvas c, int side) {
            int n = Math.max(96, Math.min(384, side));
            if (item.square == null || item.square.getWidth() != n) {
                try { item.square = SquareIcon.render(item.icon, n); } catch (Throwable t) { return; }
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0xFF000000);
            p.setFilterBitmap(true);
            squareDst.set(0, 0, side, side);
            c.drawBitmap(item.square, null, squareDst, p);
        }

        private final RectF squareDst = new RectF();

        /** Мобильная иконка без баннера: фон иконки растягивается на всю карточку, значок — по центру. */
        private void drawIconCard(Canvas c, int w, int hh) {
            android.graphics.drawable.Drawable ic = item.icon;
            if (item.color == 0) item.color = ColorUtil.dominant(ic, fallback);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            if (android.os.Build.VERSION.SDK_INT >= 26 && ic instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                android.graphics.drawable.AdaptiveIconDrawable ad = (android.graphics.drawable.AdaptiveIconDrawable) ic;
                android.graphics.drawable.Drawable back = ad.getBackground(), fore = ad.getForeground();
                int side = Math.max(w, hh);
                if (back != null) {
                    back.setBounds((w - side) / 2, (hh - side) / 2, (w + side) / 2, (hh + side) / 2);
                    back.draw(c);
                } else {
                    p.setColor(item.color | 0xFF000000);
                    c.drawRect(0, 0, w, hh, p);
                }
                if (fore != null) {
                    int fs = (int) (hh * 1.25f);
                    fore.setBounds((w - fs) / 2, (hh - fs) / 2 - (int) (hh * 0.04f), (w + fs) / 2, (hh + fs) / 2 - (int) (hh * 0.04f));
                    fore.draw(c);
                }
            } else {
                p.setColor(0xFF000000);     // кисть общая с рамкой/бликами: без этого градиент рисовался с их прозрачностью и плитка «выцветала» после первого фокуса
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, hh, ColorUtil.darker(item.color, 1f), ColorUtil.darker(item.color, 0.55f), android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, hh, p);
                p.setShader(null);
                int sz = (int) (hh * 0.7f);
                ic.setBounds((w - sz) / 2, (hh - sz) / 2 - (int) (hh * 0.04f), (w + sz) / 2, (hh + sz) / 2 - (int) (hh * 0.04f));
                ic.draw(c);
            }
        }

        private void cover(Canvas c, android.graphics.drawable.Drawable d, int w, int hh) {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = hh; }
            float sc = Math.max(w / (float) iw, hh / (float) ih);
            c.save();
            c.translate((w - iw * sc) / 2f, (hh - ih * sc) / 2f);
            c.scale(sc, sc);
            d.setBounds(0, 0, iw, ih);
            d.draw(c);
            c.restore();
        }
    }

    // ------------------------------------------------------------ жизненный цикл
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        L.init(this);
        langV = L.version;
        fsBuilt = UI.fs;
        tkBuilt = prefs.titleK();
        shapeBuilt = prefs.tileShape();
        Icons.init(this);
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        W = m.widthPixels;
        H = m.heightPixels;
        screenChanged();        // запоминаем реальный размер экрана
        s = UI.scale(this);
        mx = 96 * s;
        gap = 24 * s;
        geom();             // верх списка (vy) и отступ до нижних кнопок зависят от того, показаны ли название и подписи категорий

        // пока список приложений не прочитан, макет ещё не соответствует настройкам (виден лишний нижний ряд, фокус не там):
        // рисуется только фон, весь интерфейс появляется разом, уже готовым
        root = new FrameLayout(this) {
            @Override protected boolean drawChild(android.graphics.Canvas c, View child, long time) {
                return (ready || child == video || child == bg) && super.drawChild(c, child, time);
            }
        };
        video = new VideoBg(this);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        bg = new BgView(this, prefs.themeColor());
        bg.setLive(video);
        Frost.bg = bg;
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        hlTop = new HighlightView(this);
        hlGrid = new HighlightView(this);
        hlTopR = new HighlightView(this);
        hlGridR = new HighlightView(this);
        hlTopR.over = true;
        hlGridR.over = true;
        root.addView(hlTop, new FrameLayout.LayoutParams(-1, -1));      // рамка фокуса лежит под кнопками

        content = new FrameLayout(this);
        content.setClipChildren(false);
        frost = new FrostPanel(this);
        frost.setVisibility(View.INVISIBLE);       // до размещения плиток панель была бы крошечным «пузырьком» у левого края
        content.addView(frost, new FrameLayout.LayoutParams(10, 10));
        content.addView(hlGrid, new FrameLayout.LayoutParams(-1, -1));
        content.addView(hlGridR, new FrameLayout.LayoutParams(-1, -1));
        sv = new FadeScroll(this, Math.round(56 * s));    // при прокрутке верх списка плавно гаснет, а не обрезается по линии
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setFocusable(false);
        sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        sv.addView(content, new ViewGroup.LayoutParams(-1, H));
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> { invalidateFrost(); fadeNotice(); });

        computeGrid();
        buildTop();
        buildTexts();
        buildPanel();
        place(sv, 0, vy, W, H - vy);
        root.addView(hlTopR, new FrameLayout.LayoutParams(-1, -1));      // кольцо верхних кнопок — поверх них
        setContentView(root);
        liquid = LiquidGlass.watch(getWindow());
        liquid.hold(true);
        // реальная высота окна может быть меньше экрана (системные панели): прокрутка должна доходить до конца
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (!recreating && (r - l != or - ol || bt - t != ob - ot) && ol != or && screenChanged()) { recreating = true; recreate(); return; }
            if (bt - t != laidH && !tiles.isEmpty()) root.post(this::layoutContent);      // высота окна стала другой — отступы считаются заново
            int want = Math.round((bt - t) - vy - pinOff);
            ViewGroup.LayoutParams lp = sv.getLayoutParams();
            if (want > 0 && lp.height != want) { lp.height = want; sv.setLayoutParams(lp); }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        registerReceiver(screenOnRx, new IntentFilter(Intent.ACTION_SCREEN_ON));

        applyPrefs();
        loadApps();
        h.postDelayed(reveal, 8000);        // страховка: если список прочитать не удалось, экран не останется пустым
        if (b == null) maybeAutostartApp();
    }

    private boolean ready;          // интерфейс собран по настройкам и может быть показан

    private final Runnable reveal = new Runnable() {
        @Override public void run() {
            if (ready) return;
            ready = true;
            liquid.hold(false);
            root.invalidate();
            View f = getCurrentFocus();
            if (!(f instanceof GlassView)) return;
            for (HighlightView hv : hls()) hv.hide();       // рамка сразу встаёт на место, а не «едет» от прежней кнопки
            liquid.reset();
            moveHighlight(f);
            ensureVisible(f);
        }
    };

    /**
     * Запускает выбранное в настройках приложение, когда устройство только что включили. Срабатывает один раз за загрузку:
     * если лаунчер потом выгрузился из памяти и стартовал заново, приложение повторно не открывается.
     */
    private void maybeAutostartApp() {
        final String pkg = prefs.autoApp();
        if (pkg.isEmpty()) return;
        long up = android.os.SystemClock.elapsedRealtime();
        if (up > 5 * 60 * 1000L) return;            // включили давно — это уже не старт устройства
        int boots = -1;
        try { boots = Settings.Global.getInt(getContentResolver(), "boot_count", -1); } catch (Throwable ignored) {}
        String id = boots != -1 ? "b" + boots : "t" + (System.currentTimeMillis() - up) / 60000L;     // номер загрузки, а если его нет — время старта системы
        if (id.equals(prefs.autoBootDone())) return;
        prefs.setAutoBootDone(id);                  // отмечаем до запуска, чтобы сбой приложения не вызвал повтор
        h.postDelayed(() -> Wake.openAuto(this, pkg), 800);
    }

    // ---- выход из сна: возврат на главный экран, по желанию — приложение автозапуска ----
    private final BroadcastReceiver screenOnRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Wake.wake(c); }
    };

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(screenOnRx); } catch (Throwable ignored) {}
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        L.init(this);           // язык системы сменился, а в лаунчере выбрано «Авто»
        if (L.version != langV || screenChanged()) recreate();
    }

    /** Разрешение экрана (или плотность) изменилось, пока лаунчер жив: масштаб считается один раз при создании, поэтому экран строится заново. */
    private boolean screenChanged() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        android.util.DisplayMetrics r = new android.util.DisplayMetrics();
        try { getWindowManager().getDefaultDisplay().getRealMetrics(r); } catch (Throwable t) { r = m; }
        if (rw == 0) { rw = r.widthPixels; rh = r.heightPixels; }
        return m.widthPixels != W || m.heightPixels != H || r.widthPixels != rw || r.heightPixels != rh;
    }

    @Override protected void onResume() {
        super.onResume();
        L.init(this);
        if (L.version != langV || screenChanged() || fsBuilt != UI.fs || tkBuilt != prefs.titleK() || shapeBuilt != prefs.tileShape()) { recreate(); return; }      // сменили язык, размер шрифта, форму плиток или разрешение — экран строится заново
        resumed = true;
        h.removeCallbacks(forgetReturn);        // стол снова на экране: запомненное приложение скоро забывается
        if (returnPkg != null) h.postDelayed(forgetReturn, FORGET_MS);
        Frost.bg = bg;      // другие экраны подменяют его своим фоном
        if (!sigOf().equals(sig)) {
            applyPrefs();
            if (!all.isEmpty()) applyApps();
        }
        applyBackground();
        bg.syncLook();
        h.removeCallbacks(tick);        // onPause останавливает часы, а onStart после короткой паузы не вызывается — запускаем их здесь
        h.post(tick);
        layoutTop();
        updateNotice();
        // всё тяжёлое (сеть, разбор погоды) — уже после первого кадра, чтобы рабочий стол появлялся мгновенно
        h.removeCallbacks(lateResume);
        h.postDelayed(lateResume, 80);
        // после возврата из другого экрана подсветка могла остаться на старом месте — ставим её заново без анимации
        h.post(() -> {
            invalidateFrost();
            View f = getCurrentFocus();
            if (!(f instanceof GlassView)) return;
            for (HighlightView hv : hls()) hv.hide();
            liquid.reset();
            moveHighlight(f);
            ensureVisible(f);
        });
    }

    // ---- автосмена обоев ----
    // Ничего не крутится в фоне: пока рабочий стол на экране, ставится ОДИН отложенный вызов на остаток интервала,
    // а при уходе с экрана он снимается. Если срок вышел, пока лаунчер был закрыт, обои меняются при возврате на рабочий стол.
    private final Runnable wpTick = new Runnable() {
        @Override public void run() { rotateWallpaper(); }
    };

    private void scheduleWallpaper() {
        h.removeCallbacks(wpTick);
        if (!resumed || !prefs.wpActive()) return;
        long every = prefs.wpIntervalMs();
        long left = prefs.wpLast() + every - System.currentTimeMillis();
        if (left <= 0 || left > every) rotateWallpaper();     // срок вышел (или часы переведены назад)
        else h.postDelayed(wpTick, left);
    }

    private boolean wpBusy;

    private void rotateWallpaper() {
        if (wpBusy || !resumed) return;
        // обои меняются только пока лаунчер на экране и в фокусе (не закрыт меню или другим окном); иначе чуть позже проверяем снова
        if (!hasWindowFocus() || (dialog != null && dialog.isShowing())) {
            h.removeCallbacks(wpTick);
            h.postDelayed(wpTick, 3000);
            return;
        }
        wpBusy = true;
        Weather.IO.execute(() -> {
            final boolean changed = Bg.wpNext(this, prefs);
            runOnUiThread(() -> {
                wpBusy = false;
                if (changed && resumed) {
                    bg.fadeNext();          // новая картинка проявляется плавно поверх прежней
                    applyBackground();
                }
                scheduleWallpaper();
            });
        });
    }

    private final Runnable lateResume = new Runnable() {
        @Override public void run() {
            scheduleWallpaper();
            updateNet();
            startNetPoll();
            registerNet();
            Weather.IO.execute(() -> {
                final Weather.Data d = Weather.load(prefs);
                runOnUiThread(() -> {
                    if (d != null || wd == null) wd = d;
                    weatherPill.invalidate();
                    fitWeather();
                    maybeRefreshWeather();
                });
            });
        }
    };

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        video.pause();
        h.removeCallbacks(netPoll);
        h.removeCallbacks(lateResume);
        h.removeCallbacks(wpTick);
        h.removeCallbacks(tick);
        h.removeCallbacks(forgetReturn);
        unregisterNet();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // одно нажатие Home на некоторых ТВ приходит дважды (от службы и от системы) — повтор в течение 2 секунд не обрабатываем,
        // иначе выбор плитки, сделанный сразу после Home, сбрасывался бы обратно на первую
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastHome < 1000) return;
        lastHome = now;
        // нажатие Home: закрыть всё лишнее и вернуться на плитку приложения, из которого вышли; на самом столе — к первой
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (moving) endMove();
        AppTile back = null;
        for (AppTile t : tiles) if (t.item.pkg.equals(returnPkg)) back = t;
        if (back == null) goFirst();      // открывали не с плитки (в том числе из «Всех приложений») или плитки уже нет
        else {
            selectTile(back);
            ensureVisible(back);
        }
    }

    // приложение, открытое плиткой с рабочего стола: пока лаунчер скрыт, оно помнится, чтобы «Домой» вернул выбор на ту же плитку.
    // Из «Всех приложений» (другой экран) запуск сюда не записывается.
    private String returnPkg;
    private final Runnable forgetReturn = () -> returnPkg = null;
    private static final long FORGET_MS = 1500;      // столько после возврата ждём запоздавшее «Домой» (на части ТВ оно приходит дважды)

    /** Выбор и прокрутка возвращаются к первой плитке (Home и Back). */
    private void goFirst() {
        sv.scrollTo(0, 0);
        selectTile(tiles.isEmpty() ? null : tiles.get(0));
    }

    private void selectTile(AppTile tile) {
        for (AppTile t : tiles) { t.animate().cancel(); t.setScaleX(1f); t.setScaleY(1f); }
        for (HighlightView hv : hls()) hv.hide();
        if (tile == null) return;
        tile.requestFocus();
        // если плитка уже была в фокусе, requestFocus ничего не делает — возвращаем ей масштаб выбранной
        // (раньше она «поджималась» до обычного размера)
        if (tile.isFocused()) tile.applyFocusScale();
        moveHighlight(tile);
    }

    @Override public void onBackPressed() {
        if (moving) { endMove(); return; }
        goFirst();      // лаунчер не закрывается кнопкой «Назад»: она возвращает выбор на первую плитку, как «Домой»
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            h.removeCallbacks(reloadRun);
            h.postDelayed(reloadRun, 800);
        }
    };
    private final Runnable reloadRun = new Runnable() { @Override public void run() { loadApps(); } };

    // ------------------------------------------------------------ геометрия сетки
    /** Матовые панели берут кусок фона по своему положению на экране; после прокрутки их надо перерисовать, иначе остаётся полоса от прежнего места. */
    private void invalidateFrost() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c instanceof FrostPanel || (c instanceof GlassView && ((GlassView) c).frosted)) c.invalidate();
        }
    }

    private float gridOff;      // сдвиг группы плиток от левого поля: ряд уже области, интервал ограничен, группа стоит по центру

    private void computeGrid() {
        int ts = prefs.tileSize();
        boolean sq = shapeBuilt == 1;
        float ratio = sq ? 1f : 9f / 16f;      // высота плитки к ширине: квадрат или баннер Android TV 16:9
        gridOff = 0;
        float availW = W - 2 * mx;
        float below = prefs.panelTop() ? pad + END_PAD * s : pad + botGap + 104 * s + BOTTOM_PAD * s;
        if (ts == 0) {
            cols = prefs.cols();
            gap = (cols >= 10 ? 12 : cols == 9 ? 15 : cols == 8 ? 19 : 24) * s;     // на плотной сетке интервал меньше
            // плитки прижаты к левому краю; при 100 % ряд занимает ширину до правой линии часов
            tw = (availW - (cols - 1) * gap) / cols;
            th = tw * ratio;
            // два ряда вместе с нижней частью обязаны помещаться без прокрутки на любом экране: при нехватке высоты плитки уменьшаются,
            // а свободная ширина уходит в интервал между ними
            // при самой крупной сетке (4 плитки в ряду) с нижней панелью «Настройки» без прокрутки помещается один ряд, с панелью наверху — два
            int fit = cols == 4 && !prefs.panelTop() ? 1 : 2;
            float maxTh = (H - vy - gridTop() - below - (fit - 1) * gap) / fit;
            if (maxTh > 0 && th > maxTh) { th = maxTh; tw = th / ratio; }
            if (sq) spreadGroup(availW);
            else pitch = cols > 1 ? (availW - tw) / (cols - 1) : tw + gap;
        } else {
            // заданный размер: плитка фиксирована, в ряд помещается столько, сколько влезет; остаток ширины распределяет выравнивание
            gap = 20 * s;
            tw = (sq ? Prefs.SQUARE_W : Prefs.TILE_W)[ts] * s;
            int want = prefs.cols();
            int fit = Math.max(1, (int) ((availW + gap) / (tw + gap)));
            cols = Math.min(fit, want);      // число плиток в ряду можно ограничить и при заданном размере
            // выбранному числу не хватает чуть-чуть: интервал уменьшается (не меньше, чем на самой плотной сетке «авто»), размер плиток не меняется
            float tight = 12 * s;
            int dense = Math.min((int) ((availW + tight) / (tw + tight)), want);
            if (dense > fit && dense > 1) {
                cols = dense;
                gap = (availW - dense * tw) / (dense - 1);
            }
            th = tw * ratio;
            float maxTh = H - vy - gridTop() - below;       // хотя бы один ряд должен помещаться без прокрутки
            if (maxTh > 0 && th > maxTh) { th = maxTh; tw = th / ratio; }
            spreadGroup(availW);
        }
        rowPitch = th + gap;
    }

    /**
     * Плитки заданного размера и квадратные значки: по левому краю растягиваются на всю ширину (отступы слева и справа равны),
     * по центру стоят группой с обычным интервалом. Интервал не больше 0,6 ширины плитки.
     */
    private void spreadGroup(float availW) {
        pitch = prefs.gridAlign() == 1 || cols < 2 ? tw + gap : Math.min((availW - tw) / (cols - 1), tw * 1.6f);
        if (prefs.gridAlign() == 0 && cols > 1) gridOff = Math.max(0, (availW - ((cols - 1) * pitch + tw)) / 2f);
    }

    private static final float BOTTOM_PAD = 46f;     // отступ под «Настройками» — такой же, как над верхними кнопками, в условных пикселях
    private static final float TOP_BAR_END = 130f;   // нижний край верхних кнопок и виджета погоды
    private static final float END_PAD = 40f;        // отступ под матовой панелью, когда нижней панели «Настройки» нет
    private static final float CAPS_HALF = 0.36f;    // половина высоты заглавных букв в долях размера шрифта
    private float topGap, setLabelH;                 // видимый просвет под верхними кнопками и высота подписи над нижними

    private float gridTop() { return 100 * s; }
    /** Положение плитки в ряду: неполный (и при заданном размере любой) ряд сдвигается по выравниванию — влево, по центру или вправо. */
    private float slotX(int i) {
        int rs = i / cols * cols, n = Math.min(cols, Math.max(1, visible.size() - rs));
        float free = Math.max(0, (W - 2 * mx) - ((n - 1) * pitch + tw)), al = prefs.gridAlign();
        return mx + gridOff + (i - rs) * pitch + free * al / 2f;
    }
    private float slotY(int i) { return gridTop() + (i / cols) * rowPitch; }

    // ------------------------------------------------------------ построение интерфейса
    private <T extends View> T place(T v, float x, float y, float w, float hh) {
        return placeIn(root, hlTop, v, x, y, w, hh);
    }

    private <T extends View> T placeIn(ViewGroup parent, View hl, T v, float x, float y, float w, float hh) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w), Math.round(hh));
        // позиция задаётся отступами, а не translation: FocusFinder translation не учитывает
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        parent.addView(v, lp);
        slots.put(v, new RectF(x, y, x + w, y + hh));
        return v;
    }

    private <T extends View> T placeC(T v, float x, float y, float w, float hh) {
        return placeIn(content, hlGrid, v, x, y, w, hh);
    }

    private void moveTo(View v, float x, float y) {
        RectF r = slots.get(v);
        float w = r == null ? v.getWidth() : r.width(), hh = r == null ? v.getHeight() : r.height();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        slots.put(v, new RectF(x, y, x + w, y + hh));
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        v.setLayoutParams(lp);
    }

    private final View.OnFocusChangeListener focusL = new View.OnFocusChangeListener() {
        @Override public void onFocusChange(View v, boolean has) { if (has) onFocused(v); }
    };

    private GlassView glass(float radius, int icon, String label) {
        GlassView g = new GlassView(this);
        g.radius = radius;
        g.icon = icon;
        g.label = label;
        g.floating = true;
        g.solidFocus = true;
        g.setOnFocusChangeListener(focusL);
        return g;
    }

    private void buildTop() {
        float topY = 46 * s, ph = 84 * s;

        // часы справа (24-часовой формат)
        clock = UI.style(new OutlineText(this), "00:00", 96 * s, 0xFFFFFFFF, GlassView.LIGHT, false);
        clock.setLetterSpacing(-0.02f);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        float cw = clock.getPaint().measureText("88:88") * 1.04f + 8 * s;
        place(clock, W - mx - cw, 31 * s, cw, 104 * s);

        dateTv = UI.style(new OutlineText(this), "", 23 * s, 0xA6FFFFFF, GlassView.REG, true);
        dateTv.setLetterSpacing(0.12f);
        dateTv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place(dateTv, W - mx - 700 * s, 140 * s, 700 * s, 36 * s);

        // капсула с кнопками слева от часов
        capsule = new GlassView(this);
        capsule.setFocusable(false);
        capsule.setClickable(false);
        capsule.frosted = true;
        capsule.radius = ph / 2;
        place(capsule, 0, topY, 100 * s, ph);
        root.removeView(hlTop);     // white focus pill must lie ABOVE the frosted capsule, but under the buttons
        root.addView(hlTop, new FrameLayout.LayoutParams(-1, -1));

        btnSearch = glass(ph / 2, Icons.SEARCH, null);
        btnSource = glass(ph / 2, Icons.INPUT, null);
        btnVpn = glass(ph / 2, Icons.VPN, null);
        btnNet = glass(ph / 2, Icons.WIFI, null);
        btnGear = glass(ph / 2, Icons.GEAR, null);
        btnApps = glass(ph / 2, Icons.GRID, null);
        btnLauncher = glass(ph / 2, Icons.TUNE, null);
        for (GlassView g : new GlassView[]{btnSearch, btnSource, btnVpn, btnNet, btnApps, btnGear, btnLauncher}) { g.plain = true; place(g, 0, topY, ph, ph); }
        // кнопки нижней панели «Настройки», когда она перенесена наверх (см. «Кнопки настроек вверху» в настройках)
        btnApps.title = L.t("Все приложения");
        btnApps.setOnClickListener(v -> onPanelTile(0));
        btnLauncher.title = L.t("Настройки лаунчера");
        btnLauncher.setOnClickListener(v -> onPanelTile(2));
        btnSearch.title = L.t("Поиск");
        btnSearch.setOnClickListener(v -> onSearchClick(false));
        btnSearch.setOnLongClickListener(v -> { onSearchClick(true); return true; });
        btnSource.title = L.t("Источник сигнала");
        btnSource.setOnClickListener(v -> pickSource());
        btnVpn.setOnClickListener(v -> onVpnClick());
        btnVpn.setOnLongClickListener(v -> { pickVpnClient(); return true; });
        btnGear.title = L.t("Настройки системы");
        btnNet.setOnClickListener(v -> { if (!kidBlocked()) UI.action(this, Settings.ACTION_WIFI_SETTINGS); });
        btnNet.setOnLongClickListener(v -> { if (!kidBlocked()) UI.action(this, Settings.ACTION_WIRELESS_SETTINGS); return true; });
        btnGear.setOnClickListener(v -> { if (!kidBlocked()) UI.actionFresh(this, Settings.ACTION_SETTINGS); });
        btnVpn.iconColor = 0x66FFFFFF;

        layoutTop();

        // погода слева
        weatherPill = glass(ph / 2, 0, null);
        weatherPill.title = L.t("Погода");
        weatherPill.frosted = true;
        weatherPill.focusScale = 1f;        // виджет погоды при наведении не увеличивается — только обводка
        weatherPill.solidFocus = false;     // цветные значки погоды плохо видны на белом: фокус — кольцо
        place(weatherPill, mx, topY, ph, ph);       // настоящую ширину задаёт fitWeather()
        weatherPill.painter = this::paintWeatherPill;
        weatherPill.setOnClickListener(v -> {
            if (!prefs.hasLocation()) { if (!kidBlocked()) startActivity(new Intent(this, SettingsActivity.class).putExtra("weather", true)); }
            else startActivity(new Intent(this, WeatherActivity.class));
        });
        weatherPill.setOnLongClickListener(v -> { refreshWeather(true); return true; });
        fitWeather();

        // ненавязчивое напоминание под датой (только в версии с фиксом «Домой»): служба спецвозможностей не включена или слетела
        final String msg = L.t("Дайте доступ к спецвозможностям для корректной работы приложения");
        float nh = 44 * s;
        android.graphics.Paint mp = new android.graphics.Paint();
        mp.setTextSize(nh * 0.46f * UI.fs);
        mp.setTypeface(GlassView.REG);      // тем же шрифтом, каким текст рисуется в рамке
        float nw = Math.min(W - 2 * mx, mp.measureText(msg) + nh * 0.9f);
        notice = glass(nh / 2, 0, null);
        notice.title = L.t("Нужны спецвозможности");
        notice.frosted = true;
        notice.focusScale = 1f;
        notice.solidFocus = false;
        place(notice, W - mx - nw, 182 * s, nw, nh);
        noticeW = nw;
        placeNotice();
        notice.painter = (c, v) -> v.text(c, msg, v.getWidth() / 2f, v.getHeight() * 0.66f, v.getHeight() * 0.46f, 0xE6FFFFFF, 1, Paint.Align.CENTER, v.getWidth() - v.getHeight() * 0.3f);
        notice.setOnClickListener(v -> { if (!kidBlocked()) HomeFix.showHelp(this, this::updateNotice); });
        updateNotice();
    }

    private GlassView notice, topLast;       // topLast — крайняя правая кнопка капсулы
    private float noticeW, frostTop;

    private static final float NOTICE_H = 44f, NOTICE_GAP = 8f;   // высота плашки и наименьший просвет до того, что под ней
    private static final float NOTICE_LEFT_Y = 142f;              // сразу под виджетом погоды (и под часами, если дата скрыта)

    /**
     * У плашки два места: справа сразу под датой (без даты — под временем) и слева сразу под виджетом погоды.
     * Основное — правое. Если туда достаёт название, подпись категории или сами плитки, плашка встаёт слева.
     */
    private void placeNotice() {
        if (notice == null) return;
        float y = rightNoticeY(), x = W - mx - noticeW;
        if (noticeBlocked(x, x + noticeW, y + NOTICE_H * s)) { x = mx; y = NOTICE_LEFT_Y * s; }
        moveTo(notice, x, y);
        fadeNotice();
    }

    private float rightNoticeY() {
        // нижний край букв даты: середина её строки плюс половина заглавной буквы
        return prefs.showDate() ? (158 + CAPS_HALF * 23 * UI.fs + 10) * s : NOTICE_LEFT_Y * s;
    }

    /** Достаёт ли что-нибудь из блока приложений до плашки, занимающей x0..x1 и заканчивающейся на высоте bottom. */
    private boolean noticeBlocked(float x0, float x1, float bottom) {
        float lim = bottom + NOTICE_GAP * s, air = 24 * s;
        float f = titleF();
        if (prefs.showTitle() && shelfTitle != null) {
            float capsTop = (197 + 56 * (f - 1f) + 45 * Math.max(1f, prefs.titleK()) - CAPS_HALF * 68 * f) * s + pinOff;
            if (capsTop < lim) {
                // берётся самое длинное название на экране: иначе плашка прыгала бы при каждом переходе между плитками
                float longest = 0;
                for (AppTile t : tiles) longest = Math.max(longest, shelfTitle.getPaint().measureText(t.item.label));
                float box = W - 2 * mx, bx = panelW > 0 ? labelX(panelX + pad, panelW - 2 * pad) : mx;
                float left = Math.max(mx, bx + (box - longest) * prefs.labelPos() / 2f);
                left = Math.max(mx, Math.min(left, W - mx - longest));
                if (left < x1 + air && left + longest > x0 - air) return true;
            }
        }
        if (prefs.showHdrs() && appsHdr != null && frostTop + pinOff - (30 + CAPS_HALF * 21 * UI.fs) * s < lim) {
            float tw0 = appsHdr.getPaint().measureText(appsHdr.getText().toString());
            float bw = panelW > 0 ? panelW - 2 * pad : W - 2 * mx;
            float left = (panelW > 0 ? panelX + pad : mx) + (bw - tw0) * prefs.labelPos() / 2f;
            if (left < x1 + air && left + tw0 > x0 - air) return true;
        }
        if (frostTop + pinOff < lim) {
            float l = panelW > 0 ? panelX : mx, r = panelW > 0 ? panelX + panelW : W - mx;
            if (l < x1 && r > x0) return true;
        }
        return false;
    }

    /** При прокрутке плитки уезжают вверх; пока они могут оказаться под плашкой (или идёт перестановка), её не видно. */
    private void fadeNotice() {
        if (notice == null || sv == null) return;
        boolean under = sv.getScrollY() > 0 && vy + pinOff < notice.getLayoutParams().height + slots.get(notice).top;
        notice.setAlpha(under || moving ? 0f : 1f);
    }

    private boolean noticeOn() { return notice != null && notice.getVisibility() == View.VISIBLE; }

    /** Показывает напоминание, если служба спецвозможностей (фикс «Домой», автозапуск) не включена. */
    private void updateNotice() {
        if (notice == null) return;
        boolean on = HomeFix.needed(this);
        if (!on && notice.hasFocus() && !tiles.isEmpty()) tiles.get(0).requestFocus();
        boolean changed = on != noticeOn();
        show(notice, on);
        if (changed && contentH > 0) layoutContent();       // от плашки зависит, насколько высоко можно поднять блок
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
        v.setFocusable(on);
    }

    private static final float CLOCK_AP = 0.34f;      // размер «AM/PM» относительно цифр часов

    /** Ширина часов: в 12-часовом формате добавляется место под мелкое «AM/PM»; правый край остаётся на месте. */
    private float clockWidth(boolean c12) {
        android.graphics.Paint pt = clock.getPaint();
        float w = pt.measureText("88:88") * 1.04f + 8 * s;
        if (c12) w += pt.measureText(" PM") * CLOCK_AP * 1.1f;
        return w;
    }

    private void layoutTop() {
        float ph = 84 * s, topY = 46 * s;
        float cw0 = clockWidth(prefs.clock12()), cx0 = W - mx - cw0;
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) clock.getLayoutParams();
        if (cl.width != Math.round(cw0)) {
            cl.width = Math.round(cw0);
            cl.leftMargin = Math.round(cx0);
            clock.setLayoutParams(cl);
            slots.put(clock, new RectF(cx0, 31 * s, cx0 + cw0, 31 * s + 104 * s));
        }
        float clockLeft = slots.get(clock).left;
        if (prefs.clock12()) {
            // часы прижаты вправо, поэтому капсула идёт вплотную к реальному тексту («9:05 AM» уже, чем «10:05 AM»), а не к запасу под самую широкую запись
            clockTextW = android.text.Layout.getDesiredWidth(clock.getText(), clock.getPaint());
            clockLeft = cx0 + cw0 - clockTextW - 12 * s;
        }
        List<GlassView> bs = new ArrayList<>();
        boolean src = showSource();
        boolean srch = prefs.showSearchBtn();
        show(btnSearch, srch);
        if (srch) bs.add(btnSearch);
        btnSource.setVisibility(src ? View.VISIBLE : View.GONE);
        btnSource.setFocusable(src);
        if (src) bs.add(btnSource);
        boolean pt = prefs.panelTop();
        boolean sv2 = prefs.showVpnBtn(), sn = prefs.showNetBtn(), sg = prefs.showGearBtn();
        show(btnVpn, sv2); show(btnNet, sn); show(btnGear, sg);
        show(btnApps, pt); show(btnLauncher, pt);
        if (sv2) bs.add(btnVpn);
        if (sn) bs.add(btnNet);        if (pt) { bs.add(btnApps); bs.add(btnLauncher); }
        if (sg) bs.add(btnGear);
        topLast = bs.isEmpty() ? null : bs.get(bs.size() - 1);
        capsule.setVisibility(bs.isEmpty() ? View.GONE : View.VISIBLE);
        float pad = 0;      // края капсулы совпадают с краями белого кружка
        float cw = bs.size() * ph + 2 * pad;
        float x0 = clockLeft - 34 * s - cw;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capsule.getLayoutParams();
        lp.width = Math.round(cw);
        lp.leftMargin = Math.round(x0);
        lp.topMargin = Math.round(topY);
        capsule.setLayoutParams(lp);
        slots.put(capsule, new RectF(x0, topY, x0 + cw, topY + ph));
        for (int i = 0; i < bs.size(); i++) moveTo(bs.get(i), x0 + pad + i * ph, topY);
        fitWeather();       // капсула могла сдвинуться — от неё зависит предел ширины виджета погоды
    }

    private void buildTexts() {
        shelfTitle = UI.style(new OutlineText(this), "", 68 * s, 0xFFFFFFFF, GlassView.LIGHT, true);
        shelfTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 68 * s * titleF());      // общий размер шрифта и размер названия перемножаются
        shelfTitle.setLetterSpacing(-0.02f);
        place(shelfTitle, mx, 197 * s, W - 2 * mx, 90 * s * Math.max(1f, prefs.titleK()));

        appsHdr = UI.style(new OutlineText(this), L.t("ПРИЛОЖЕНИЯ"), 21 * s, 0x99FFFFFF, GlassView.MED, true);
        appsHdr.setLetterSpacing(0.2f);
        placeC(appsHdr, mx, gridTop() - pad - 48 * s, W - 2 * mx, 36 * s);
        setHdr = UI.style(new OutlineText(this), L.t("НАСТРОЙКИ"), 21 * s, 0x99FFFFFF, GlassView.MED, true);
        setHdr.setLetterSpacing(0.2f);
        placeC(setHdr, mx, 500 * s, W - 2 * mx, 36 * s);
    }

    private void buildPanel() {
        String[] labels = {L.t("Все приложения"), L.t("Система"), L.t("Лаунчер")};
        String[] titles = {L.t("Все приложения"), L.t("Настройки системы"), L.t("Настройки лаунчера")};
        int[] icons = {Icons.GRID, Icons.GEAR, Icons.TUNE};
        for (int i = 0; i < SET_COUNT; i++) {
            final int idx = i;
            GlassView g = glass(22 * s, icons[i], labels[i]);
            g.title = titles[i];
            g.frosted = true;
            placeC(g, mx + i * (324 * s), 550 * s, 300 * s, 104 * s);
            g.setOnClickListener(v -> onPanelTile(idx));
            setTiles[i] = g;
        }
    }

    /**
     * «Режим для бабушек»: пока он включён и не разблокирован, меню приложений, системные настройки и настройки лаунчера
     * не открываются, а нажатие показывает подсказку. Возвращает true, если действие нужно отменить.
     */
    /** Режим для бабушек включён и сейчас не разблокирован (для других экранов, например погоды). */
    static boolean kidLocked(Prefs p) { return p.kid() && System.currentTimeMillis() >= unlockUntil; }

    private boolean kidBlocked() {
        if (!prefs.kid() || System.currentTimeMillis() < unlockUntil) return false;
        if (kidToast != null) kidToast.cancel();
        kidToast = android.widget.Toast.makeText(this, L.t("←←← →→→ для разблокировки"), android.widget.Toast.LENGTH_SHORT);
        kidToast.show();
        return true;
    }

    /** Следим за кнопками: «влево ×3, вправо ×3» подряд (в пределах 8 секунд) временно разблокирует меню на 3 минуты. */
    private void trackUnlockKeys(KeyEvent e) {
        if (!prefs.kid() || e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        long now = System.currentTimeMillis();
        if (now < unlockUntil) return;       // уже разблокировано — время не продлевается
        int kc = e.getKeyCode();
        if (kc != KeyEvent.KEYCODE_DPAD_LEFT && kc != KeyEvent.KEYCODE_DPAD_RIGHT) { java.util.Arrays.fill(seqK, 0); return; }
        System.arraycopy(seqK, 1, seqK, 0, 5);
        System.arraycopy(seqT, 1, seqT, 0, 5);
        seqK[5] = kc;
        seqT[5] = now;
        int kl = KeyEvent.KEYCODE_DPAD_LEFT, kr = KeyEvent.KEYCODE_DPAD_RIGHT;
        if (seqK[0] == kl && seqK[1] == kl && seqK[2] == kl && seqK[3] == kr && seqK[4] == kr && seqK[5] == kr && now - seqT[0] <= 8000) {
            java.util.Arrays.fill(seqK, 0);
            unlockUntil = now + UNLOCK_MS;
            android.widget.Toast.makeText(this, L.t("Меню разблокировано на 3 минуты"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void onPanelTile(int i) {
        if (kidBlocked()) return;
        switch (i) {
            case 0: startActivity(new Intent(this, ManageActivity.class)); break;
            case 1: UI.actionFresh(this, Settings.ACTION_SETTINGS); break;
            case 2: startActivity(new Intent(this, SettingsActivity.class)); break;
        }
    }

    /** Нижняя панель следует за сеткой: чем больше рядов, тем ниже она уезжает. */
    private void layoutContent() {
        laidH = root.getHeight();
        float winH = laidH > 0 ? laidH : H;      // окно бывает ниже экрана (системные панели): просветы считаются по нему
        int n = visible.size();
        int rows = Math.max(1, (n + cols - 1) / cols);
        float gridEnd = gridTop() + rows * rowPitch - gap;
        // матовая панель за сеткой: отступ от плиток до края со всех сторон одинаковый
        float px = mx - pad, py = gridTop() - pad, pw = W - 2 * mx + 2 * pad, ph = gridEnd + pad - py;
        final boolean ctr = prefs.gridAlign() == 1;
        if ((ctr || gridOff > 0.5f || pad == 0) && n > 0) {   // панель не шире самого длинного ряда: пустого стекла по бокам нет, а без подложки надписи ровняются по плиткам
            pw = (Math.min(cols, n) - 1) * pitch + tw + 2 * pad;
            px = ctr ? (W - pw) / 2f : mx + gridOff - pad;
        }
        panelX = px;
        panelW = pw;
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) frost.getLayoutParams();
        fl.leftMargin = Math.round(px);
        fl.topMargin = Math.round(py);
        fl.width = Math.round(pw);
        fl.height = Math.round(ph);
        frost.radius = 44 * s;
        frost.setLayoutParams(fl);
        frost.setVisibility(prefs.noPanel() ? View.INVISIBLE : View.VISIBLE);
        boolean pt = prefs.panelTop();
        int mode = prefs.gridPin();
        float panelEnd = gridEnd + pad, panelH = 104 * s;
        setHdr.setVisibility(pt || !prefs.showHdrs() ? View.GONE : View.VISIBLE);
        for (GlassView g : setTiles) show(g, !pt);
        // сетка стоит по центру (по настройке или потому, что «по ширине» уже не растягивается) — кнопки тоже по центру:
        // они бывают шире такой сетки, и от левого края первой плитки торчали бы вправо
        boolean midGroup = ctr || (gridOff > 0.5f && n >= cols);
        buttonsX = midGroup ? (W - (2 * 324 + 300) * s) / 2f : mx + gridOff;
        // appsH — видимая высота блока приложений: от названия до низа матовой панели
        float appsH = frostTop - TOP_BAR_END * s - topGap + ph;
        if (pt) {
            // нижней панели нет: ни заголовка, ни кнопок, ни отступа под ними — ниже матовой панели остаётся лишь небольшой край
            contentH = Math.round(panelEnd + END_PAD * s);
            float even = (winH - TOP_BAR_END * s - appsH) / 2f;     // «по центру»: над блоком и под ним поровну
            pinWant = mode == 0 ? 0 : Math.max(0, mode == 1 ? even - topGap : winH - vy - contentH);
        } else {
            // Симметрия — между самими блоками: просвет «верхние кнопки → приложения» равен просвету «приложения → настройки».
            // Отступ от нижних кнопок до края экрана не фиксирован: всё лишнее место уходит вниз.
            float rest = winH - TOP_BAR_END * s - appsH - setLabelH - panelH;      // на три просвета: два между блоками и нижний
            float below = botGap;       // не помещается — обычный отступ и прокрутка
            pinWant = 0;
            if (rest >= 2 * topGap + BOTTOM_PAD * s) {
                if (mode == 1) {        // «по центру»: все три просвета равны, но нижний не отбирает место у двух верхних
                    float g = Math.max(topGap, rest / 3f);
                    pinWant = g - topGap;
                    below = g + setLabelH;
                }
            } else {
                // обычные просветы не помещаются: оба сжимаются поровну, а блок приложений чуть поднимается
                float even = (rest - BOTTOM_PAD * s) / 2f;
                // без названия панель не должна наезжать на дату под часами
                float min = Math.max(30 * s, 52 * s - (frostTop - TOP_BAR_END * s - topGap));
                if (prefs.labelPos() == 2) min = Math.max(min, 52 * s);      // справа под датой стоят и надписи — выше неё не поднимаются
                // под плашкой спецвозможностей (в левом, самом высоком её месте) всегда остаётся просвет
                if (noticeOn()) min = Math.max(min, (NOTICE_LEFT_Y + NOTICE_H + NOTICE_GAP - TOP_BAR_END) * s);
                if (even >= min) {
                    below = even + setLabelH;
                    pinWant = even - topGap;
                }
            }
            float panelY = panelEnd + below, hdrY = panelY - 46 * s;
            moveTo(setHdr, labelX(buttonsX, (2 * 324 + 300) * s), hdrY);
            for (int i = 0; i < SET_COUNT; i++) moveTo(setTiles[i], buttonsX + i * (324 * s), panelY);
            contentH = (int) (panelY + panelH + BOTTOM_PAD * s);
            if (mode == 2 && winH - vy - contentH > 0) pinWant = winH - vy - contentH;       // «снизу»: весь блок прижат к низу
        }
        moveTo(appsHdr, labelX(panelX + pad, panelW - 2 * pad), py - 48 * s);
        // ScrollView меряет содержимое без учёта заданной высоты (по самому нижнему элементу), поэтому нижний отступ задаём минимальной высотой
        applyPin();
        content.setMinimumHeight(contentH);
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        lp.height = Math.max(contentH, Math.round(winH - vy - pinOff));
        content.setLayoutParams(lp);
        content.requestLayout();
    }

    private void show(Dialog d) {
        dialog = d;
        d.show();
    }

    // ------------------------------------------------------------ настройки / данные
    private String sigOf() {
        return prefs.hidden().toString() + prefs.order() + prefs.theme() + prefs.showDate()
                + prefs.cols() + prefs.bgMode() + prefs.showSourceBtn() + prefs.showSearchBtn() + prefs.showVpnBtn() + prefs.showNetBtn() + prefs.showGearBtn() + prefs.showWeather() + prefs.panelTop()
                + prefs.showTitle() + prefs.showHdrs() + prefs.clock12() + prefs.tileSize() + prefs.gridPin() + prefs.gridAlign() + prefs.glow() + prefs.readableText() + prefs.labelPos() + prefs.noPanel() + prefs.liquid();
    }

    /**
     * Вертикальная раскладка зависит от того, показаны ли название приложения и подписи категорий: скрытое место не остаётся пустым.
     * Без названия и подписей отступ от виджета погоды до матовой панели равен отступу от неё до нижних кнопок.
     */
    private void geom() {
        boolean ti = prefs.showTitle(), hd = prefs.showHdrs();
        float f = titleF(), top;
        if (!ti) top = 194 + (hd ? 48 : 0);
        else if (hd) top = 335 + (f > 1f ? 115 : 80) * (f - 1f);      // при крупном шрифте под названием больше места до подписи категории
        else {
            // подписей нет: панель начинается сразу под названием (низ выносных элементов букв плюс небольшой зазор)
            float titleTop = 197 + 56 * (f - 1f), box = 90 * Math.max(1f, prefs.titleK());
            top = titleTop + box / 2f + 0.586f * 68 * f + 12;
        }
        // отступ от плиток до края матовой панели; без подложки его нет, и краями блока становятся сами плитки
        pad = prefs.noPanel() ? 0 : (shapeBuilt == 1 ? 22 : 30) * s;
        vy = top * s - (gridTop() - pad);         // top — верх матовой панели от верха экрана, в условных пикселях
        frostTop = top * s;
        placeNotice();
        // видимый верх блока приложений: буквы названия, без него — подписи «Приложения», без подписей — сама панель
        float seen = ti ? 197 + 56 * (f - 1f) + 45 * Math.max(1f, prefs.titleK()) - CAPS_HALF * 68 * f
                : hd ? top - 30 - CAPS_HALF * 21 * UI.fs : top;
        topGap = (seen - TOP_BAR_END) * s;                  // видимый просвет под верхними кнопками
        setLabelH = (hd ? 28 + CAPS_HALF * 21 * UI.fs : 0) * s;     // от верха букв «Настройки» до нижних кнопок
        botGap = topGap + setLabelH;                        // под панелью виден такой же просвет, как под верхними кнопками
        applyPin();
    }

    /** Множитель размера названия приложения на главном экране: общий размер шрифта умножается на выбранное «меньше / стандарт / крупнее». */
    private float titleF() { return UI.fs * prefs.titleK(); }

    private float pinOff;       // на сколько сетка опущена вниз (положение «по центру» и «снизу»)
    private float pinWant;      // то же, посчитанное при последней раскладке плиток
    private int laidH;          // высота окна, по которой она посчитана
    private float pad, panelX, panelW, buttonsX;       // отступ внутри матовой панели, её место и левый край нижних кнопок

    /**
     * Сетку можно прижать к центру или низу экрана: весь блок (название, матовая панель, нижние кнопки) опускается на свободное место.
     * Если блок не помещается (много рядов), он стоит на своём обычном месте и прокручивается.
     */
    private void applyPin() {
        pinOff = contentH <= 0 ? 0 : pinWant;
        if (sv != null) {
            ViewGroup.LayoutParams lp = sv.getLayoutParams();
            lp.height = Math.round((root.getHeight() > 0 ? root.getHeight() : H) - vy - pinOff);
            moveTo(sv, 0, vy + pinOff);
        }
        if (sv instanceof FadeScroll) {
            float titleBottom = (197 + 56 * (titleF() - 1f) + 45 * Math.max(1f, prefs.titleK()) + 0.586f * 68 * titleF()) * s + pinOff;
            ((FadeScroll) sv).cut = prefs.showTitle() ? Math.max(0, Math.round(titleBottom - vy - pinOff)) : 0;
        }
        placeTitle();
        placeNotice();
    }

    /** Название выбранного приложения. Узкий блок (одна-две плитки) не должен уводить длинное название за край экрана. */
    private void placeTitle() {
        if (shelfTitle == null) return;
        float box = W - 2 * mx, x = panelW > 0 ? labelX(panelX + pad, panelW - 2 * pad) : mx;
        float text = shelfTitle.getPaint().measureText(shelfTitle.getText().toString());
        if (text < box) {
            float left = x + (box - text) * prefs.labelPos() / 2f;
            x += Math.max(0, mx - left) - Math.max(0, left + text - (W - mx));
        }
        // при крупном шрифте название чуть ниже, подальше от плашки
        moveTo(shelfTitle, x, 197 * s + 56 * (titleF() - 1f) * s + pinOff);
    }

    /**
     * Левый край надписи шириной во весь экран без полей. «Слева» — по левому краю блока (первой плитки или первой кнопки),
     * «по центру» — середина надписи над серединой блока, «справа» — по правому краю блока.
     * Блок сужается и расширяется вместе с числом плиток в ряду.
     */
    private float labelX(float blockLeft, float blockWidth) {
        int at = prefs.labelPos();      // 0 слева, 1 по центру, 2 справа: доля свободного места слева от надписи — 0, половина, всё
        return blockLeft + (blockWidth - (W - 2 * mx)) * at / 2f;
    }
    /** Название выбранного приложения и подписи категорий — по настройкам. */
    private void applyLabels() {
        shelfTitle.setVisibility(prefs.showTitle() ? View.VISIBLE : View.INVISIBLE);
        boolean hd = prefs.showHdrs();
        int at = prefs.labelPos();
        int gr = at == 1 ? Gravity.CENTER : (at == 2 ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        shelfTitle.setGravity(gr);
        appsHdr.setGravity(gr);
        setHdr.setGravity(gr);
        appsHdr.setVisibility(hd ? View.VISIBLE : View.INVISIBLE);
        setHdr.setVisibility(hd && !prefs.panelTop() ? View.VISIBLE : View.GONE);
    }

    private void applyPrefs() {
        sig = sigOf();
        hlGridR.glow = prefs.glow();
        setLiquidOn(prefs.liquid());
        geom();
        computeGrid();
        dateTv.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
        show(weatherPill, prefs.showWeather());
        updateClock();
        layoutTop();
        fitWeather();
        applyLabels();
    }

    private void setLiquidOn(boolean on) {
        if (on == liquidOn) return;
        liquidOn = on;
        liquid.reset();
        rescale(root);
    }

    /** Режим выделения сменился: у всех кнопок и плиток заново ставится размер и перерисовывается фокус. */
    private void rescale(View v) {
        if (v instanceof GlassView) {
            v.animate().cancel();
            v.setScaleX(1f);
            v.setScaleY(1f);
            ((GlassView) v).applyFocusScale();
            v.invalidate();
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) rescale(g.getChildAt(i));
        }
    }

    /** Режим фона: 0 градиент, 1 картинка, 2 видео. Без файла — фиксированный градиент. */
    private void applyBackground() {
        int mode = prefs.bgMode();
        if (mode == 1 && !Bg.has(this)) mode = 0;
        if (mode == 2 && !Bg.hasVideo(this)) mode = 0;
        bg.setMode(mode);
        bg.setAccent(prefs.themeColor());
        if (mode == 2) {
            video.setVisibility(View.VISIBLE);
            video.setSource(Bg.videoFile(this));
            if (resumed) video.play();
        } else {
            video.stop();
            video.setVisibility(View.GONE);
        }
    }

    private void loadApps() {
        Weather.IO.execute(() -> {
            final List<AppRepo.AppItem> l = AppRepo.loadAll(this);
            runOnUiThread(() -> {
                all = l;
                if (deletePkg != null) {
                    boolean gone = System.currentTimeMillis() - deleteAt < 120000;
                    for (AppRepo.AppItem x : l) if (x.pkg.equals(deletePkg)) gone = false;
                    if (gone) pendingFocus = deleteIdx;     // как при скрытии: на место удалённого встало правое соседнее, у крайнего правого — левое
                    deletePkg = null;
                }
                applyApps();
                if (!ready) h.postDelayed(reveal, 60);      // после того как плитки разложатся по местам
            });
        });
    }

    private void applyApps() {
        visible = AppRepo.visible(all, prefs);
        computeGrid();
        rebuildTiles();
        layoutContent();
        sig = sigOf();
        // нижние кнопки при смене числа рядов съезжают вниз/вверх, а подсветка — отдельный вид и остаётся на прежнем месте:
        // если фокус на одной из них (не на плитке приложения), ведём подсветку за ней
        View cf = getCurrentFocus();
        if (cf != null && !(cf instanceof AppTile) && cf instanceof GlassView && cf.getParent() == content) moveHighlight(cf);
        if (pendingFocus >= 0 && !tiles.isEmpty()) {
            int fi = Math.min(pendingFocus, tiles.size() - 1);
            pendingFocus = -1;
            tiles.get(fi).requestFocus();
        } else if (getCurrentFocus() == null || !getCurrentFocus().isFocused() || (!userMoved && !tiles.isEmpty())) {
            if (!tiles.isEmpty()) tiles.get(0).requestFocus(); else weatherPill.requestFocus();
        }
    }

    private void rebuildTiles() {
        boolean hadFocus = getCurrentFocus() instanceof AppTile;
        String focusedPkg = hadFocus ? ((AppTile) getCurrentFocus()).item.pkg : null;
        for (AppTile t : tiles) { content.removeView(t); slots.remove(t); }
        tiles.clear();
        AppTile toFocus = null;
        boolean sq = shapeBuilt == 1;
        float rad = sq ? th * 0.23f : 22 * s;
        for (int i = 0; i < visible.size(); i++) {
            final AppRepo.AppItem it = visible.get(i);
            final AppTile t = new AppTile(this, it, prefs.themeColor());
            t.radius = rad;
            t.square = sq;
            t.setOnFocusChangeListener(focusL);
            t.setOnClickListener(v -> launch(it));
            t.setOnLongClickListener(v -> { appMenu(t); return true; });
            placeC(t, slotX(i), slotY(i), tw, th);
            tiles.add(t);
            if (it.pkg.equals(focusedPkg)) toFocus = t;
        }
        hlGridR.bringToFront();       // кольцо должно лежать поверх только что добавленных плиток
        if (toFocus != null) toFocus.requestFocus();
    }

    private void launch(final AppRepo.AppItem it) {
        Dialog d = Parental.guard(this, prefs, it.pkg, it.label, () -> doLaunch(it));      // приложение под PIN-кодом — сначала спросим код
        if (d != null) show(d);
    }

    private void doLaunch(AppRepo.AppItem it) {
        returnPkg = it.pkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(it.component())
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (i != null) UI.start(this, i); else UI.start(this, new Intent());
        }
    }

    private void appMenu(final AppTile t) {
        if (kidBlocked()) return;
        String[] items = {L.t("Открыть"), L.t("Переместить"), L.t("Скрыть"), L.t("Удалить"), L.t("О приложении")};
        show(GlassDialog.menu(this, t.item.label, items, i -> {
            switch (i) {
                case 0: launch(t.item); break;
                case 1: startMove(t); break;
                case 2: {
                    java.util.Set<String> hs = prefs.hidden();
                    hs.add(t.item.pkg);
                    prefs.setHidden(hs);
                    pendingFocus = Math.max(0, tiles.indexOf(t));      // выбор переходит на соседнее приложение (то, что встало на место скрытого)
                    applyApps();
                    break;
                }
                case 3:
                    deletePkg = t.item.pkg;
                    deleteIdx = Math.max(0, tiles.indexOf(t));
                    deleteAt = System.currentTimeMillis();
                    UI.start(this, new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + t.item.pkg)));
                    break;
                case 4: UI.start(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + t.item.pkg))); break;
            }
        }));
    }

    // ------------------------------------------------------------ перемещение приложений
    private static final int MOVE_ACCENT = 0xFF6EC1FF;

    private void startMove(AppTile t) {
        moving = true;
        fadeNotice();
        movingTile = t;
        t.requestFocus();
        t.focusScale = 1.14f;
        t.applyFocusScale();
        hlGrid.tint = 0xFFFFD27A; hlGridR.tint = 0xFFFFD27A;
        liquid.setAccent(MOVE_ACCENT);
        moveHighlight(t);
        shelfTitle.setText(L.t("Перемещение: ") + t.item.label);
        placeTitle();
    }

    private void endMove() {
        moving = false;
        fadeNotice();
        if (movingTile != null) {
            movingTile.focusScale = 1.08f;
            movingTile.applyFocusScale();
        }
        hlGrid.tint = 0xFFFFFFFF; hlGridR.tint = 0xFFFFFFFF;
        liquid.setAccent(0);
        AppRepo.saveOrder(prefs, visible);
        sig = sigOf();
        movingTile = null;
        View f = getCurrentFocus();
        if (f != null) onFocused(f);
    }

    private boolean userMoved, longFired, swallowUp;
    private static boolean kcNotBack(KeyEvent e) { return e.getKeyCode() != KeyEvent.KEYCODE_BACK; }
    private Runnable longRun;

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();      // пока экран пуст, стрелки и ОК ни на что не действуют
        if (!ready && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN) userMoved = true;
        int dk = e.getKeyCode();     // выбор повели стрелками — следующее «Домой» снова вернёт на первую плитку
        if (resumed && e.getAction() == KeyEvent.ACTION_DOWN && dk >= KeyEvent.KEYCODE_DPAD_UP && dk <= KeyEvent.KEYCODE_DPAD_RIGHT) returnPkg = null;
        trackUnlockKeys(e);
        // Свой разбор «короткое / долгое нажатие ОК» на плитках: не зависит от того, как пульт шлёт повтор клавиши
        View fv = getCurrentFocus();
        int kc = e.getKeyCode();
        // из верхнего ряда в сторону «в никуда» выбор не уходит: на список приложений — только кнопкой «Вниз»
        if (!moving && ((fv == topLast && kc == KeyEvent.KEYCODE_DPAD_RIGHT) || (fv == weatherPill && kc == KeyEvent.KEYCODE_DPAD_LEFT))) return true;
        // переход через край ряда (по вертикали выбор ходит как обычно): включено «Циклический переход» — по кругу внутри ряда,
        // выключено — на соседний ряд (вправо с последней плитки на первую плитку следующего ряда, влево — на последнюю предыдущего)
        if (!moving && fv instanceof AppTile && e.getAction() == KeyEvent.ACTION_DOWN
                && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int i = tiles.indexOf(fv), rs = i / cols * cols, re = Math.min(rs + cols, tiles.size()) - 1, to = -1;
            boolean cyc = prefs.cycle();
            if (kc == KeyEvent.KEYCODE_DPAD_RIGHT && i == re) { if (cyc) to = rs; else if (re + 1 < tiles.size()) to = re + 1; else return true; }       // без цикла — на первую плитку следующего ряда, с самой последней остаёмся
            else if (kc == KeyEvent.KEYCODE_DPAD_LEFT && i == rs) to = cyc ? re : (rs > 0 ? rs - 1 : -1);
            if (to >= 0 && to != i) { tiles.get(to).requestFocus(); return true; }
            if (to < 0 && !cyc && (kc == KeyEvent.KEYCODE_DPAD_LEFT && i == 0)) return true;      // с первой плитки влево — остаёмся на ней
        }
        if (!moving && fv instanceof AppTile) {
            final AppTile ft = (AppTile) fv;
            if (kc == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) appMenu(ft);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        longFired = false;
                        if (longRun != null) h.removeCallbacks(longRun);
                        longRun = () -> { longFired = true; appMenu(ft); };
                        h.postDelayed(longRun, 450);
                    } else if (!longFired) {            // клавиша удерживается и шлёт повторы — это тоже долгое нажатие
                        if (longRun != null) h.removeCallbacks(longRun);
                        longFired = true;
                        appMenu(ft);
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (longRun != null) h.removeCallbacks(longRun);
                    if (!longFired && !swallowUp) launch(ft.item);   // отпускание ОК после подтверждения перестановки — не запуск
                    longFired = false;
                    swallowUp = false;
                }
                return true;
            }
        }
        if (moving) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int i = tiles.indexOf(movingTile), ni = i;
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: ni = i - 1; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: ni = i + 1; break;
                    case KeyEvent.KEYCODE_DPAD_UP: ni = i - cols; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: ni = i + cols; break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_BACK:
                        if (e.getRepeatCount() == 0) { endMove(); swallowUp = kcNotBack(e); }
                        return true;
                    default: return super.dispatchKeyEvent(e);
                }
                if (ni >= 0 && ni < tiles.size() && ni != i) {
                    visible.add(ni, visible.remove(i));
                    tiles.add(ni, tiles.remove(i));
                    for (int k = 0; k < tiles.size(); k++) moveTo(tiles.get(k), slotX(k), slotY(k));
                    moveHighlight(movingTile);
                    ensureVisible(movingTile);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ фокус и подсветка
    private void moveHighlight(final View v) {
        RectF r = slots.get(v);
        if (r == null) return;
        if (!v.isLaidOut()) {        // при запуске фокус ставится раньше, чем плитки размещены: подсветку покажем, когда будет готово
            for (HighlightView hv : hls()) hv.hide();
            v.post(() -> { if (v.hasFocus() && v.isLaidOut()) moveHighlight(v); });
            return;
        }
        // рамка строится по реальному (целому) положению плитки: слоты дробные, а сама плитка округляется отдельно по краям и размеру,
        // из-за чего рамка по высоте/ширине расходилась с плиткой на пиксель
        if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.width > 0 && lp.height > 0) r = new RectF(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height);
        }
        if (UI.tvos) {      // вместо рамок и белой таблетки за выделением летает стекло (LiquidGlass)
            for (HighlightView hv : hls()) hv.hide();
            return;
        }
        boolean inGrid = v.getParent() == content;
        boolean pill = v instanceof GlassView && ((GlassView) v).solidFocus;
        // таблетка (кнопки, панель) лежит под самой кнопкой, а кольцо плитки — поверх неё, чтобы закрыть сглаженный край плитки
        HighlightView hl = pill ? (inGrid ? hlGrid : hlTop) : (inGrid ? hlGridR : hlTopR);
        for (HighlightView hv : hls()) if (hv != hl) hv.hide();
        hl.solid = pill;
        float sc = v instanceof GlassView ? ((GlassView) v).focusScale : 1.08f;
        // рамка точно по краю увеличенной плитки, в целых пикселях: раньше +0.5*s с округлением вверх делало низ/право на пиксель больше
        int dx = Math.round(r.width() * (sc - 1f) / 2f), dy = Math.round(r.height() * (sc - 1f) / 2f);
        RectF t = new RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy);
        float rad = v instanceof GlassView ? ((GlassView) v).radius : 20 * s;
        hl.moveTo(t, Math.min(rad * (t.height() / r.height()), t.height() / 2f));
    }

    /** Подкручивает прокрутку так, чтобы плитка с подсветкой целиком была видна. */
    private void ensureVisible(View v) {
        RectF r = slots.get(v);
        if (r == null || v.getParent() != content) return;
        float pad = BOTTOM_PAD * s;
        int vh = Math.round(H - vy - pinOff), top = sv.getScrollY();
        if (root.getHeight() > 0) vh = Math.min(vh, Math.round(root.getHeight() - vy - pinOff));   // реально видимая область
        if (sv.getHeight() > 0) vh = Math.min(vh, sv.getHeight());
        int ch = Math.max(contentH, vh);
        int target = top;
        // сверху выбранной плитке нужно выйти из-под полосы, которую занимает название приложения
        float topPad = Math.max(pad, (sv instanceof FadeScroll ? ((FadeScroll) sv).cut : 0) + 28 * s);
        if (r.top - topPad < top) target = Math.round(r.top - topPad);
        else if (r.bottom + pad > top + vh) target = Math.round(r.bottom + pad - vh);
        if (r.top < 160 * s) target = 0;
        target = Math.max(0, Math.min(target, Math.max(0, ch - vh)));
        if (target != top) sv.scrollTo(0, target);
    }

    private void onFocused(View v) {
        if (v instanceof GlassView) moveHighlight(v);
        ensureVisible(v);
        if (moving) return;
        if (v instanceof AppTile) {
            setShelf(((AppTile) v).item.label);
        } else if (v == btnNet) {
            setShelf(netTitle());
        } else if (v instanceof GlassView && ((GlassView) v).title != null) {
            setShelf(((GlassView) v).title);
        }
    }

    private void setShelf(String title) {
        if (!title.contentEquals(shelfTitle.getText())) {
            shelfTitle.setText(title);
            placeTitle();
        }
    }

    // ------------------------------------------------------------ часы и статус сети
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            h.postDelayed(this, 60000 - now % 60000 + 30);
        }
    };

    private void updateClock() {
        Date d = new Date();
        if (prefs.clock12()) {
            // 12-часовой формат: «7:05», а «AM/PM» — мелким шрифтом справа (ширину часов см. в layoutTop)
            String t = new SimpleDateFormat("h:mm", Locale.getDefault()).format(d);
            String ap = new SimpleDateFormat("a", Locale.US).format(d).toUpperCase(Locale.US);
            android.text.SpannableString sp = new android.text.SpannableString(t + " " + ap);
            sp.setSpan(new android.text.style.RelativeSizeSpan(CLOCK_AP), t.length(), sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(sp);
            // ширина записи меняется только при переходе 9→10 и 12→1: тогда капсула один раз пересаживается к часам
            if (capsule != null && Math.abs(android.text.Layout.getDesiredWidth(sp, clock.getPaint()) - clockTextW) > 2) layoutTop();
        } else {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d));
        }
        String ds = new SimpleDateFormat(L.en() ? "EEEE, MMMM d" : "EEEE, d MMMM", L.locale()).format(d);
        dateTv.setText(ds.toUpperCase(L.locale()));
    }

    private int fastPolls;

    /** После возврата на рабочий стол первые ~40 с проверяем сеть каждые 1,5 с (VPN поднимается не сразу), потом реже. */
    private void startNetPoll() {
        fastPolls = 27;
        h.removeCallbacks(netPoll);
        h.postDelayed(netPoll, 800);
    }

    private final Runnable netPoll = new Runnable() {
        @Override public void run() {
            updateNet();
            h.postDelayed(this, fastPolls-- > 0 ? 1500 : 4000);
        }
    };

    /** Запасной признак VPN: поднятый туннельный интерфейс (tun/ppp/wg/tap). */
    private static boolean tunnelUp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                java.net.NetworkInterface i = e.nextElement();
                String n = i.getName();
                if (i.isUp() && (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("tap"))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { h.post(() -> updateNet()); }
                @Override public void onLost(Network n) { h.post(() -> updateNet()); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { h.post(() -> updateNet()); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), netCb);
        } catch (Throwable ignored) {
            netCb = null;
        }
    }

    private void unregisterNet() {
        try {
            if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
        } catch (Throwable ignored) {}
        netCb = null;
    }

    private String netTitle() {
        if (noInet) return vpn ? L.t("Нет доступа в интернет (проверьте VPN)") : L.t("Подключено, но нет доступа в интернет");
        return eth ? L.t("Проводное интернет-подключение") : wifi ? L.t("Беспроводное интернет-подключение") : L.t("Нет подключения");
    }

    private void updateNet() {
        boolean nv = false, ne = false, nw = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) nv = true;
                else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ne = true;
                    else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) nw = true;
                }
            }
        } catch (Throwable ignored) {}
        if (!nv && tunnelUp()) nv = true;
        // активная сеть (при включённом VPN — это сам VPN) не прошла проверку доступа в интернет
        boolean bad = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network an = cm.getActiveNetwork();
            NetworkCapabilities ac = an == null ? null : cm.getNetworkCapabilities(an);
            bad = (ne || nw || nv) && ac != null && !ac.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable ignored) {}
        long now = android.os.SystemClock.elapsedRealtime();
        if (!bad) badSince = 0;
        else if (badSince == 0) badSince = now;
        // сразу после подключения система ещё проверяет сеть — ждём несколько секунд, чтобы значок не мигал
        boolean nn = bad && now - badSince >= 5000;
        if (bad && !nn) h.postDelayed(this::updateNet, 5200 - (now - badSince));
        boolean changed = nv != vpn || ne != eth || nw != wifi || nn != noInet;
        vpn = nv; eth = ne; wifi = nw; noInet = nn;
        if (!changed && btnNet.icon != 0 && slots.get(capsule).width() > 0 && btnNet.title != null) return;
        btnVpn.iconColor = vpn ? 0xFFFFFFFF : 0x66FFFFFF;
        btnVpn.title = vpn ? L.t("VPN включён") : L.t("VPN выключен");
        btnVpn.invalidate();
        btnVpn.postInvalidateOnAnimation();
        if (btnVpn.getParent() instanceof View) ((View) btnVpn.getParent()).invalidate();
        btnNet.icon = eth ? (noInet ? Icons.ETH_NOINET : Icons.ETH) : wifi ? (noInet ? Icons.WIFI_OFF : Icons.WIFI) : Icons.WIFI_OFF;
        btnNet.title = netTitle();
        btnNet.invalidate();
        layoutTop();
        View f = getCurrentFocus();
        if (f == btnNet || f == btnVpn) onFocused(f);      // подпись внизу обновляется сразу, не дожидаясь, пока уйдёшь и вернёшься
    }

    // ------------------------------------------------------------ поиск
    /** Поиск в выбранном приложении; выбор запоминается (повторно — долгое нажатие на лупу или настройки лаунчера). */
    private void onSearchClick(boolean forcePick) {
        final List<SearchHelper.Provider> ps = SearchHelper.find(this);
        if (ps.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("Приложения для поиска не найдены. Установите приложение с поиском"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        SearchHelper.Provider ch = forcePick ? null : SearchHelper.chosen(prefs, ps);
        if (ch != null) { SearchHelper.launch(this, ch); return; }
        show(GlassDialog.menu(this, L.t("Приложение для поиска"), SearchHelper.names(ps), i -> {
            prefs.setSearchApp(ps.get(i).key());
            SearchHelper.launch(this, ps.get(i));
        }));
    }

    // ------------------------------------------------------------ VPN и источники сигнала
    private void onVpnClick() {
        String pkg = prefs.vpnPkg();
        boolean installed = false;
        if (!pkg.isEmpty()) {
            try { getPackageManager().getPackageInfo(pkg, 0); installed = true; } catch (Throwable ignored) {}
        }
        if (!installed) { pickVpnClient(); return; }
        VpnControl.toggle(this, pkg, !vpn);
        startNetPoll();
    }

    /** Выбор клиента VPN прямо с главного экрана (долгое нажатие на ключ) — то же, что в настройках лаунчера. */
    private void pickVpnClient() {
        final List<AppRepo.AppItem> cl = VpnControl.clients(this, all);
        if (cl.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("VPN-клиенты не найдены. Установите приложение и выберите его в настройках лаунчера"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[cl.size()];
        for (int i = 0; i < names.length; i++) names[i] = cl.get(i).label;
        show(GlassDialog.menu(this, L.t("Клиент для VPN"), names, i -> {
            prefs.setVpnPkg(cl.get(i).pkg);
            VpnControl.toggle(this, cl.get(i).pkg, !vpn);
        }));
    }

    /** Кнопка «Источник» нужна только телевизору: у приставки нет входных портов (HDMI/AV/антенна). */
    private void refreshInputs() {
        inputs.clear();
        try {
            android.media.tv.TvInputManager tm = (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                // HDMI-порт с подключённым CEC-устройством система показывает дважды: сам порт и отдельный вход самого устройства
                // (его родитель — этот порт). Оба ведут на одно и то же, поэтому порт, у которого есть такой дочерний вход, пропускаем.
                List<android.media.tv.TvInputInfo> all = tm.getTvInputList();
                java.util.Set<String> parents = new java.util.HashSet<>();
                for (android.media.tv.TvInputInfo i : all) {
                    if (i.isPassthroughInput() && i.getParentId() != null) parents.add(i.getParentId());
                }
                java.util.Map<String, String> labelById = new java.util.HashMap<>();
                for (android.media.tv.TvInputInfo i : all) labelById.put(i.getId(), rawLabel(i));
                inputLabel.clear();
                for (android.media.tv.TvInputInfo i : all) {
                    if (!i.isPassthroughInput() || parents.contains(i.getId())) continue;
                    String lb = rawLabel(i);
                    // у входа подключённого устройства название бывает пустым или «null» — тогда берём название порта (HDMI 1…)
                    if (lb.isEmpty() && i.getParentId() != null) lb = labelById.get(i.getParentId()) == null ? "" : labelById.get(i.getParentId());
                    if (lb.isEmpty()) continue;
                    int kind = sourceIcon(i, lb);
                    boolean hdmi = kind == Icons.SRC_HDMI;
                    boolean av = kind == Icons.SRC_AV || kind == Icons.SRC_SVIDEO || kind == Icons.SRC_COMPONENT
                            || kind == Icons.SRC_VGA || kind == Icons.SRC_DVI || kind == Icons.SRC_SCART;
                    if (!hdmi && !av) continue;            // только HDMI и AV: без антенны, USB и прочих служебных входов
                    try { if (i.isHidden(this)) continue; } catch (Throwable ignored) {}        // скрытые в настройках ТВ входы
                    if (av) {                                // разъёмы AV, которых у телевизора по факту нет, система тоже перечисляет
                        try { if (tm.getInputState(i.getId()) == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED) continue; } catch (Throwable ignored) {}
                    }
                    inputs.add(i);
                    // номер порта: из названия порта (у входа устройства — из названия его родителя), иначе из идентификатора (…/HW5)
                    String base = i.getParentId() != null && labelById.get(i.getParentId()) != null && !labelById.get(i.getParentId()).isEmpty()
                            ? labelById.get(i.getParentId()) : lb;
                    int num = hdmi ? hdmiNumber(i, lb, labelById.get(i.getParentId()), base) : portNumber(base, i.getId());
                    if (hdmi && num != 1000) lb = "HDMI " + num;        // названия, заданные в самом ТВ («Xbox», «Приставка»…), не используем
                    inputLabel.put(i.getId(), lb);
                    inputOrder.put(i.getId(), num);
                }
                // HDMI по возрастанию номера порта, под ними AV (тоже по порядку)
                java.util.Collections.sort(inputs, (a, b) -> {
                    int ka = sourceIcon(a, inputLabel.get(a.getId())) == Icons.SRC_HDMI ? 0 : 1;
                    int kb = sourceIcon(b, inputLabel.get(b.getId())) == Icons.SRC_HDMI ? 0 : 1;
                    if (ka != kb) return ka - kb;
                    int na = inputOrder.get(a.getId()), nb = inputOrder.get(b.getId());
                    return na != nb ? Integer.compare(na, nb) : inputLabel.get(a.getId()).compareToIgnoreCase(inputLabel.get(b.getId()));
                });
            }
        } catch (Throwable ignored) {}
    }

    private final java.util.Map<String, String> inputLabel = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> inputOrder = new java.util.HashMap<>();

    /** Номер в названии («HDMI 2» → 2); нет — число в конце идентификатора («…/HW5» → 5); иначе в конец списка. */
    private static int portNumber(String label, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(label == null ? "" : label.trim());
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(id == null ? "" : id);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        return 1000;
    }

    /** Номер порта HDMI без оглядки на имена, которые пользователь дал входам в ТВ: «HDMI N» в названии → номер порта из системы → число в идентификаторе. */
    private static int hdmiNumber(android.media.tv.TvInputInfo in, String own, String parent, String base) {
        java.util.regex.Pattern pt = java.util.regex.Pattern.compile("^HDMI\\s*(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String s : new String[]{own, parent}) {
            if (s == null) continue;
            java.util.regex.Matcher m = pt.matcher(s.trim());
            if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        }
        try {
            Object info = android.media.tv.TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(in);
            if (info != null) {
                int pid = (Integer) info.getClass().getMethod("getPortId").invoke(info);
                if (pid > 0) return pid;
            }
        } catch (Throwable ignored) {}
        return portNumber(null, in.getParentId() != null ? in.getParentId() : in.getId());
    }

    /** Название входа; пустая строка, если системе нечего показать (в том числе буквально «null»). */
    private String rawLabel(android.media.tv.TvInputInfo in) {
        CharSequence cs = null;
        // у HDMI имя, заданное пользователем в настройках ТВ, игнорируем — нужен системный «HDMI N»
        if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI) { try { cs = in.loadCustomLabel(this); } catch (Throwable ignored) {} }
        if (cs == null || cs.length() == 0) { try { cs = in.loadLabel(this); } catch (Throwable ignored) {} }
        String s = cs == null ? "" : cs.toString().trim();
        return s.equalsIgnoreCase("null") ? "" : s;
    }

    private boolean showSource() {
        return prefs.showSourceBtn();
    }

    /** Значок по типу разъёма; если система тип не сообщает (OTHER) — по названию. */
    private static int sourceIcon(android.media.tv.TvInputInfo in, String label) {
        switch (in.getType()) {
            case android.media.tv.TvInputInfo.TYPE_HDMI: return Icons.SRC_HDMI;
            case android.media.tv.TvInputInfo.TYPE_DISPLAY_PORT: return Icons.SRC_DP;
            case android.media.tv.TvInputInfo.TYPE_COMPOSITE: return Icons.SRC_AV;
            case android.media.tv.TvInputInfo.TYPE_SVIDEO: return Icons.SRC_SVIDEO;
            case android.media.tv.TvInputInfo.TYPE_COMPONENT: return Icons.SRC_COMPONENT;
            case android.media.tv.TvInputInfo.TYPE_VGA: return Icons.SRC_VGA;
            case android.media.tv.TvInputInfo.TYPE_DVI: return Icons.SRC_DVI;
            case android.media.tv.TvInputInfo.TYPE_SCART: return Icons.SRC_SCART;
            case android.media.tv.TvInputInfo.TYPE_TUNER: return Icons.SRC_TUNER;
            default: break;
        }
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("hdmi")) return Icons.SRC_HDMI;
        if (l.contains("displayport") || l.matches(".*\\bdp\\b.*")) return Icons.SRC_DP;
        if (l.contains("component") || l.contains("ypbpr")) return Icons.SRC_COMPONENT;
        if (l.contains("composite") || l.contains("cvbs") || l.matches(".*\\bav\\s*\\d?\\b.*")) return Icons.SRC_AV;
        if (l.contains("vga") || l.matches(".*\\bpc\\b.*")) return Icons.SRC_VGA;
        if (l.contains("dvi")) return Icons.SRC_DVI;
        if (l.contains("scart")) return Icons.SRC_SCART;
        if (l.contains("atv") || l.contains("dtv") || l.contains("tuner") || l.contains("антенн") || l.contains("кабел") || l.contains("эфир")) return Icons.SRC_TUNER;
        return Icons.INPUT;
    }

    /**
     * Системные панели выбора источника разных прошивок (компонент или действие). Перебираем по очереди и берём первую,
     * которая запустилась: у одного телевизора есть панель MediaTek, у другого Xiaomi, у третьего стандартная Google.
     */
    private static final String[][] SYSTEM_SOURCE_PANELS = {
            // действие + компонент (Xiaomi, панель из приложения Live TV: работает и при отключённом стоковом лаунчере)
            {"act+cmp", "com.android.tv.action.VIEW_INPUTS", "com.mitv.livetv", "com.mitv.livetv.input.SelectInputActivity"},
            {"act", "com.android.tv.action.VIEW_INPUTS"},
            {"com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.inputpanel.InputsPanelActivity"},
            {"act", "com.xiaomi.mitv.settings.INPUTSOURCE_POPUP"},
            {"act", "com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP"},
            {"com.formovie.fmmediaselect", "com.formovie.fmmediaselect.MainActivity"},
            {"com.google.android.tvlauncher", "com.google.android.tvlauncher.inputs.InputsPanelActivity"},
    };

    private boolean openSystemSourcePanel() {
        for (String[] p : SYSTEM_SOURCE_PANELS) {
            try {
                Intent i;
                if ("act+cmp".equals(p[0])) i = new Intent(p[1]).setClassName(p[2], p[3]);
                else if ("act".equals(p[0])) i = new Intent(p[1]);
                else i = new Intent().setClassName(p[0], p[1]);
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
                // такой панели в прошивке нет или она закрыта для сторонних приложений — пробуем следующую
            }
        }
        return false;
    }

    private void pickSource() {
        if (openSystemSourcePanel()) return;
        refreshInputs();
        if (inputs.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("Источники сигнала не найдены"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[inputs.size()];
        int[] kinds = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            android.media.tv.TvInputInfo in = inputs.get(i);
            names[i] = inputLabel.get(in.getId());
            kinds[i] = sourceIcon(in, names[i]);
        }
        show(GlassDialog.menuIcons(this, L.t("Источник сигнала"), names, kinds, i -> {
            try {
                Uri u = android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputs.get(i).getId());
                startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, L.t("Не удалось переключить источник"), android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
    }

    // ------------------------------------------------------------ погода
    private void maybeRefreshWeather() {
        if (!prefs.hasLocation()) return;
        if (wd == null || System.currentTimeMillis() - wd.fetched > 30 * 60 * 1000L) refreshWeather(false);
    }

    private void refreshWeather(final boolean toast) {
        Weather.refresh(this, new Weather.Callback<Weather.Data>() {
            @Override public void ok(Weather.Data d) {
                wd = d;
                weatherPill.invalidate();
                fitWeather();
                if (toast) android.widget.Toast.makeText(MainActivity.this, L.t("Погода обновлена"), android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override public void fail(String m) {
                if (toast) android.widget.Toast.makeText(MainActivity.this, m, android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private String weatherSub(Weather.Pt p) {
        String sb = wd.city == null ? "" : wd.city;
        if (p.feel != null) sb += (sb.isEmpty() ? "" : " · ") + L.t("ощущается как ") + Weather.temp(p.feel);
        return sb;
    }

    // Ширина виджета погоды по содержимому, крупнее значок и температура, текст по центру видимых букв.
    // Идея и расчёты — abekert, Pull Request «Fit the weather widget to its content».
    private static final float WEATHER_TEMP_SIZE = 0.67f;
    private static final float WEATHER_CONDITION_SIZE = 0.295f;
    private static final float WEATHER_DETAIL_SIZE = 0.25f;
    private static final float WEATHER_RIGHT_PADDING = 0.38f;
    private final android.graphics.Rect weatherTextBounds = new android.graphics.Rect();

    /** Виджет погоды занимает столько ширины, сколько нужно тексту; длинный текст обрезается у прежнего предела или у капсулы с кнопками. */
    private void fitWeather() {
        if (weatherPill == null || capsule == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) weatherPill.getLayoutParams();
        float height = lp.height;
        Paint paint = weatherPill.tp;
        float content;
        if (!prefs.hasLocation()) {
            content = height * 1.1f + Math.max(
                    measureWeatherText(paint, L.t("Погода"), height * 0.37f, GlassView.MED),
                    measureWeatherText(paint, L.t("Выберите город в настройках"), height * 0.26f, GlassView.REG));
        } else if (wd == null || wd.cur == null) {
            content = height * 0.5f + measureWeatherText(paint, L.t("Погода…"), height * 0.34f, GlassView.REG);
        } else {
            Weather.Pt p = wd.cur;
            float temp = measureWeatherText(paint, Weather.temp(p.t), height * WEATHER_TEMP_SIZE, GlassView.MED);
            float condition = measureWeatherText(paint, p.desc == null ? "" : p.desc, height * WEATHER_CONDITION_SIZE, GlassView.REG);
            float detail = measureWeatherText(paint, weatherSub(p), height * WEATHER_DETAIL_SIZE, GlassView.REG);
            content = height * 1.35f + temp + Math.max(condition, detail);
        }
        RectF next = slots.get(capsule.getVisibility() == View.VISIBLE ? capsule : clock);
        float available = next.left - lp.leftMargin - 24 * s;
        int limit = Math.max(1, (int) Math.min(660 * s * Math.max(1, UI.fs), available));
        int width = Math.min(limit, (int) Math.ceil(content + height * WEATHER_RIGHT_PADDING));
        if (lp.width == width) return;
        lp.width = width;
        weatherPill.setLayoutParams(lp);
        RectF slot = slots.get(weatherPill);
        slot.right = slot.left + width;
        weatherPill.invalidate();
        if (weatherPill.isFocused()) moveHighlight(weatherPill);
    }

    private static float measureWeatherText(Paint paint, String text, float size, android.graphics.Typeface typeface) {
        paint.setTypeface(typeface);
        paint.setTextSize(size * UI.fs);
        return paint.measureText(text);
    }

    /** Две строки текста одним блоком: по центру высоты ставятся видимые края букв. */
    private void twoLines(Canvas c, GlassView v, float x, String a, float sa, int ca, String b, float sb, int cb, float mw) {
        float hh = v.getHeight(), f = UI.fs;
        float gap = 0.58f * (sa + sb) * hh * f;
        v.tp.setTypeface(GlassView.REG);
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        if (!a.isEmpty()) {
            v.tp.setTextSize(hh * sa * f);
            v.tp.getTextBounds(a, 0, a.length(), weatherTextBounds);
            top = weatherTextBounds.top;
            bottom = weatherTextBounds.bottom;
        }
        if (!b.isEmpty()) {
            v.tp.setTextSize(hh * sb * f);
            v.tp.getTextBounds(b, 0, b.length(), weatherTextBounds);
            top = Math.min(top, gap + weatherTextBounds.top);
            bottom = Math.max(bottom, gap + weatherTextBounds.bottom);
        }
        float baseline = top <= bottom ? (hh - top - bottom) / 2f : hh / 2f;
        v.text(c, a, x, baseline, hh * sa, ca, 1, Paint.Align.LEFT, mw);
        v.text(c, b, x, baseline + gap, hh * sb, cb, 1, Paint.Align.LEFT, mw);
    }

    private void paintWeatherPill(Canvas c, GlassView v) {
        float hh = v.getHeight(), w = v.getWidth();
        boolean dark = v.isFocused() && v.solid();            // белая таблетка в фокусе — тёмный текст
        int main = dark ? 0xFF1B1B22 : 0xFFFFFFFF, sub = dark ? 0xFF55555F : 0xA6FFFFFF;
        if (!prefs.hasLocation()) {
            Icons.weather(c, "d_c1", hh * 0.58f, hh / 2f, hh * 0.3f, v.tp);
            twoLines(c, v, hh * 1.1f, L.t("Погода"), 0.37f, main, L.t("Выберите город в настройках"), 0.26f, sub, w - hh * 1.3f);
            return;
        }
        if (wd == null || wd.cur == null) {
            v.text(c, L.t("Погода…"), hh * 0.5f, hh * 0.64f, hh * 0.34f, dark ? main : 0xCCFFFFFF, 1, Paint.Align.LEFT, w);
            return;
        }
        Weather.Pt p = wd.cur;
        Icons.weather(c, p.icon, hh * 0.58f, hh * 0.475f, hh * 0.37f, v.tp);
        String t = Weather.temp(p.t);
        float x = hh * 1.1f;
        float tempSize = hh * WEATHER_TEMP_SIZE;
        v.tp.setTypeface(GlassView.MED);
        v.tp.setTextSize(tempSize * UI.fs);
        v.tp.getTextBounds(t, 0, t.length(), weatherTextBounds);
        v.text(c, t, x, hh / 2f - weatherTextBounds.exactCenterY(), tempSize, main, 2, Paint.Align.LEFT, 0);
        float x2 = x + v.tp.measureText(t) + hh * 0.25f, mw = w - x2 - hh * WEATHER_RIGHT_PADDING;
        if (mw <= 0) return;
        twoLines(c, v, x2, p.desc == null ? "" : p.desc, WEATHER_CONDITION_SIZE,
                dark ? main : 0xF2FFFFFF, weatherSub(p), WEATHER_DETAIL_SIZE, sub, mw);
    }
}
