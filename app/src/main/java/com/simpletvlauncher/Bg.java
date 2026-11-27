package com.simpletvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/** Пользовательский фон: копия картинки в памяти приложения + готовое размытие. */
public final class Bg {
    private Bg() {}

    public interface Cb { void done(Bitmap b); }

    /** Размытие фона на остальных экранах (погода, приложения, настройки), в процентах. */
    private static final int SOFT_BLUR = 55;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Готовые картинки по ключу «файл + степень размытия»: рабочий стол и остальные экраны не вытесняют друг друга. */
    private static final ConcurrentHashMap<String, Bitmap> CACHE = new ConcurrentHashMap<>();

    private static File file(Context c) { return new File(c.getFilesDir(), "bg.jpg"); }

    public static void clearCache() { CACHE.clear(); }

    public static boolean has(Context c) { return file(c).exists(); }

    /** Меняется, когда пользователь выбрал другую картинку или видео. */
    public static String sig(Context c) { return file(c).lastModified() + ":" + videoFile(c).lastModified(); }

    /** Вернёт (в основном потоке) фоновую картинку с заданным пользователем размытием или null, если фон не задан. */
    public static void request(Context ctx, Cb cb) {
        request(ctx, cb, new Prefs(ctx.getApplicationContext()).bgBlur());
    }

    /** То же, но с постоянным сильным размытием — для остальных экранов. */
    public static void requestSoft(Context ctx, Cb cb) {
        request(ctx, cb, SOFT_BLUR);
    }

    private static void request(Context ctx, final Cb cb, final int level) {
        final Context c = ctx.getApplicationContext();
        final File f = file(c);
        if (!f.exists()) { cb.done(null); return; }
        final String key = f.lastModified() + ":" + f.length() + ":" + level;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { cb.done(hit); return; }
        Weather.IO.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try { b = build(f, level); } catch (Throwable ignored) {}
                final Bitmap res = b;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (res != null) {
                            if (CACHE.size() >= 3) CACHE.clear();
                            CACHE.put(key, res);
                        }
                        cb.done(res);
                    }
                });
            }
        });
    }

    private static Bitmap build(File f, int level) {
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (src == null) return null;
        if (level <= 0) return src;
        int w = 640, h = Math.max(2, Math.round(640f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        if (small != src) src.recycle();
        return blurSmall(small, Math.max(1, Math.round(level * 0.28f)));
    }

    /** Размытие маленькой картинки (два прохода «ящиком» — мягкая колоколообразная кисть). Возвращает новую картинку. */
    static Bitmap blurSmall(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) line(a, b, y * w, 1, w, r);
            for (int x = 0; x < w; x++) line(b, a, x, w, h, r);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void line(int[] src, int[] dst, int start, int stride, int len, int r) {
        int div = 2 * r + 1, sr = 0, sg = 0, sb = 0;
        for (int i = -r; i <= r; i++) {
            int p = src[start + clamp(i, len) * stride];
            sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
        }
        for (int i = 0; i < len; i++) {
            dst[start + i * stride] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
            int add = src[start + clamp(i + r + 1, len) * stride];
            int sub = src[start + clamp(i - r, len) * stride];
            sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
            sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
            sb += (add & 255) - (sub & 255);
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }

    // ---- видео ----
    public static File videoFile(Context c) { return new File(c.getFilesDir(), "bg.mp4"); }
    public static boolean hasVideo(Context c) { return videoFile(c).exists(); }

    /** Копирует выбранный видеофайл во внутреннюю память (доступ по ссылке пропал бы после перезапуска). */
    public static boolean setVideo(Context ctx, Uri uri) {
        try {
            Context c = ctx.getApplicationContext();
            File tmp = new File(c.getFilesDir(), "bg.tmp");
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return false;
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            File dst = videoFile(c);
            dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Копирует выбранную картинку (уменьшив до 1920 по длинной стороне). Вызывать в фоне. */
    public static boolean setImage(Context ctx, Uri uri) {
        boolean ok = saveImage(ctx, uri, file(ctx.getApplicationContext()));
        if (ok) CACHE.clear();
        return ok;
    }

    // ---- автосмена обоев: обои берутся по очереди из выбранной папки внутренней памяти (через MediaStore), копий картинок не хранится ----
    static Uri imagesUri() {
        return android.os.Build.VERSION.SDK_INT >= 29
                ? android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)     // только внутренняя память
                : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    static boolean canReadImages(Context c) {
        String perm = android.os.Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Номера всех картинок папки (MediaStore _ID). */
    private static java.util.List<Long> folderIds(Context c, String bucket) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (android.database.Cursor q = c.getContentResolver().query(imagesUri(), new String[]{android.provider.MediaStore.MediaColumns._ID},
                android.provider.MediaStore.MediaColumns.BUCKET_ID + "=?", new String[]{bucket}, android.provider.MediaStore.MediaColumns._ID)) {
            while (q != null && q.moveToNext()) ids.add(q.getLong(0));
        } catch (Throwable ignored) {}
        return ids;
    }

    private static java.util.Set<Long> parseIds(String s) {
        java.util.Set<Long> r = new java.util.HashSet<>();
        for (String x : s.split(",")) if (!x.isEmpty()) { try { r.add(Long.parseLong(x)); } catch (NumberFormatException ignored) {} }
        return r;
    }

    private static String joinIds(java.util.Set<Long> s) {
        StringBuilder b = new StringBuilder();
        for (Long x : s) { if (b.length() > 0) b.append(','); b.append(x); }
        return b.toString();
    }

    /** Картинка становится обоем: уменьшается до 1920 и записывается в bg.jpg (через временный файл, чтобы не отдать недописанный). */
    static boolean applyUri(Context c, Uri u) {
        File tmp = new File(c.getFilesDir(), "bg.jpg.tmp"), dst = file(c);
        if (!saveImage(c, u, tmp)) { tmp.delete(); return false; }
        dst.delete();
        boolean ok = tmp.renameTo(dst);
        CACHE.clear();
        return ok;
    }

    /**
     * Следующие обои из папки: случайная картинка, которая в этом круге ещё не показывалась. Когда показаны все —
     * круг начинается заново (последняя картинка круга сразу не повторяется). Новые картинки в папке подхватываются сами.
     * Вызывать в фоне. true — обои сменились.
     */
    public static synchronized boolean wpNext(Context ctx, Prefs pr) {
        Context c = ctx.getApplicationContext();
        if (pr.wpOnline() >= 0) return Wallhaven.rotate(c, pr);
        String bucket = pr.wpBucket();
        if (bucket.isEmpty()) return false;
        pr.setWpLast(System.currentTimeMillis());          // отсчёт заново в любом случае, чтобы при сбое не повторять попытку без конца
        if (!canReadImages(c)) return false;
        java.util.List<Long> ids = folderIds(c, bucket);
        if (ids.size() < 2) return false;                  // папку удалили или в ней почти ничего нет — текущие обои остаются
        java.util.Set<Long> seen = parseIds(pr.wpSeen());
        seen.retainAll(ids);                               // удалённые из папки картинки из памяти убираем
        long cur = pr.wpCur();
        if (cur >= 0 && ids.contains(cur)) seen.add(cur);
        java.util.List<Long> cand = new java.util.ArrayList<>();
        for (Long id : ids) if (!seen.contains(id)) cand.add(id);
        if (cand.isEmpty()) {                              // круг пройден
            seen.clear();
            if (cur >= 0 && ids.contains(cur)) seen.add(cur);
            for (Long id : ids) if (!seen.contains(id)) cand.add(id);
        }
        java.util.Random rnd = new java.util.Random();
        boolean ok = false;
        while (!ok && !cand.isEmpty()) {
            long id = cand.remove(rnd.nextInt(cand.size()));
            seen.add(id);
            ok = applyUri(c, android.content.ContentUris.withAppendedId(imagesUri(), id));     // файл не читается — берём другой
            if (ok) pr.setWpCur(id);
        }
        pr.setWpSeen(joinIds(seen));
        return ok;
    }


    private static boolean saveImage(Context ctx, Uri uri, File dst) {
        try {
            Context c = ctx.getApplicationContext();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920 && o.outHeight / (sample * 2) >= 1080) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            if (bmp == null) return false;
            float sc = Math.min(1f, Math.min(1920f / bmp.getWidth() * 1f, 1920f / bmp.getHeight()));
            if (sc < 1f) {
                Bitmap s2 = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
                bmp.recycle();
                bmp = s2;
            }
            FileOutputStream out = new FileOutputStream(dst);
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            bmp.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
