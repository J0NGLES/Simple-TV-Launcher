package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Неподвижные «портреты» видеофона, которые считаются один раз при выборе видео и хранятся в маленьких файлах:
 *  - размытый (усреднение нескольких кадров по всей длине ролика) — фон погоды, приложений и настроек;
 *  - детальный (один кадр с лёгким размытием) — подложка стеклянных меню на рабочем столе, чтобы сквозь них угадывалась картинка.
 * Пока человек в меню, фон неподвижен, поэтому нагрузки на приставку нет.
 */
final class SoftVideo {
    private SoftVideo() {}

    interface Cb { void done(Bitmap frame); }

    private static final int WIDTH = 160, BLUR = 5, BLUR_DETAIL = 2;
    private static final float[] AT = {0.2f, 0.5f, 0.8f};      // где в ролике брать кадры (доля длительности)
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Cb> WAITING = new ArrayList<>();
    private static boolean loading;
    private static Bitmap cache, detailCache;
    private static String cacheKey = "";

    /** Единственный файл: сверху размытый кадр, под ним детальный (одинаковой высоты). При смене видео перезаписывается. */
    private static File frameFile(Context c) { return new File(c.getFilesDir(), "bgv.png"); }

    /** Тема больше не видео — файл не нужен. */
    static void forget(Context ctx) {
        Context c = ctx.getApplicationContext();
        // уходим с видеофона (градиент, картинка, автосмена обоев): кадр и сам видеофайл больше не нужны — освобождаем место
        try { frameFile(c).delete(); new File(c.getFilesDir(), "bgvd.png").delete(); Bg.videoFile(c).delete(); } catch (Throwable ignored) {}
        cache = null; detailCache = null; cacheKey = "";
    }

    private static String keyOf(File video) { return video.lastModified() + ":" + video.length(); }

    /** Детальный кадр (после request): для подложки меню на рабочем столе. */
    static Bitmap detail() { return detailCache; }

    /** Вернёт (в основном потоке) готовый размытый кадр или null, если видео нет или оно не читается. */
    static void request(Context ctx, Cb cb) {
        final Context c = ctx.getApplicationContext();
        final File v = Bg.videoFile(c);
        if (!v.exists()) { cb.done(null); return; }
        final String key = keyOf(v);
        if (key.equals(cacheKey) && cache != null) { cb.done(cache); return; }
        WAITING.add(cb);
        if (loading) return;
        loading = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Bitmap[] r = null;
                try {
                    r = readSaved(c, v);
                    if (r == null) {
                        r = extract(v);
                        if (r != null) save(c, r[0], r[1]);
                    }
                } catch (Throwable ignored) {}
                final Bitmap[] res = r;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (res != null) { cache = res[0]; detailCache = res[1]; cacheKey = key; }
                        ArrayList<Cb> l = new ArrayList<>(WAITING);
                        WAITING.clear();
                        for (Cb w : l) w.done(res == null ? null : res[0]);
                    }
                });
            }
        }, "SoftVideo");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Запасной путь: кадр, снятый с идущего видео (если сам файл прочитать не удалось). Слишком тёмный (ещё не показан) не берём. */
    static Bitmap store(Context ctx, Bitmap raw) {
        Context c = ctx.getApplicationContext();
        int h = Math.max(2, Math.round(WIDTH * raw.getHeight() / (float) raw.getWidth()));
        Bitmap s = raw.getWidth() == WIDTH ? raw : Bitmap.createScaledBitmap(raw, WIDTH, h, true);
        long sum = 0;
        int[] px = new int[WIDTH * h];
        s.getPixels(px, 0, WIDTH, 0, 0, WIDTH, h);
        for (int p : px) sum += ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255);
        if (sum / (3L * px.length) < 6) return null;
        Bitmap out = Bg.blurSmall(s, BLUR), det = Bg.blurSmall(s, BLUR_DETAIL);
        save(c, out, det);
        cache = out;
        detailCache = det;
        cacheKey = keyOf(Bg.videoFile(c));
        return out;
    }

    private static Bitmap[] readSaved(Context c, File video) {
        File a = frameFile(c);
        if (!a.exists() || a.lastModified() < video.lastModified()) return null;      // нет или старый: видео выбрали заново
        Bitmap st = BitmapFactory.decodeFile(a.getAbsolutePath());
        if (st == null || st.getHeight() % 2 != 0) return null;
        int h = st.getHeight() / 2;
        return new Bitmap[]{Bitmap.createBitmap(st, 0, 0, st.getWidth(), h), Bitmap.createBitmap(st, 0, h, st.getWidth(), h)};
    }

    private static void save(Context c, Bitmap blur, Bitmap detail) {
        try (FileOutputStream out = new FileOutputStream(frameFile(c))) {
            Bitmap st = Bitmap.createBitmap(blur.getWidth(), blur.getHeight() * 2, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(st);
            cv.drawBitmap(blur, 0, 0, null);
            cv.drawBitmap(detail.getWidth() == blur.getWidth() && detail.getHeight() == blur.getHeight() ? detail
                    : Bitmap.createScaledBitmap(detail, blur.getWidth(), blur.getHeight(), true), 0, blur.getHeight(), null);
            st.compress(Bitmap.CompressFormat.PNG, 100, out);
            new File(c.getFilesDir(), "bgvd.png").delete();     // файл прежней версии
        } catch (Throwable ignored) {}
    }

    private static long num(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** {размытый усреднённый, детальный}. */
    private static Bitmap[] extract(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            long durUs = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000L;
            int vw = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int vh = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) { int t = vw; vw = vh; vh = t; }
            if (vw <= 0 || vh <= 0) { vw = 16; vh = 9; }
            int fh = Math.max(2, Math.round(WIDTH * vh / (float) vw));
            int n = durUs > 3000000 ? AT.length : 1;
            int[] acc = new int[WIDTH * fh * 3], px = new int[WIDTH * fh];
            int got = 0;
            Bitmap detail = null;
            for (int i = 0; i < n; i++) {
                long us = n == 1 ? 0 : (long) (durUs * AT[i]);
                Bitmap b = Build.VERSION.SDK_INT >= 27
                        ? r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, fh)
                        : r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) continue;
                if (b.getWidth() != WIDTH || b.getHeight() != fh) {
                    Bitmap s = Bitmap.createScaledBitmap(b, WIDTH, fh, true);
                    if (s != b) b.recycle();
                    b = s;
                }
                if (detail == null || i == n / 2) {         // детальный кадр — из середины ролика
                    if (detail != null) detail.recycle();
                    detail = Bg.blurSmall(b, BLUR_DETAIL);
                }
                b.getPixels(px, 0, WIDTH, 0, 0, WIDTH, fh);
                b.recycle();
                for (int k = 0; k < px.length; k++) {
                    acc[k * 3] += (px[k] >> 16) & 255;
                    acc[k * 3 + 1] += (px[k] >> 8) & 255;
                    acc[k * 3 + 2] += px[k] & 255;
                }
                got++;
            }
            if (got == 0 || detail == null) return null;
            for (int k = 0; k < px.length; k++) {
                px[k] = 0xFF000000 | ((acc[k * 3] / got) << 16) | ((acc[k * 3 + 1] / got) << 8) | (acc[k * 3 + 2] / got);
            }
            Bitmap avg = Bitmap.createBitmap(WIDTH, fh, Bitmap.Config.ARGB_8888);
            avg.setPixels(px, 0, WIDTH, 0, 0, WIDTH, fh);
            Bitmap out = Bg.blurSmall(avg, BLUR);
            avg.recycle();
            return new Bitmap[]{out, detail};
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }
}
