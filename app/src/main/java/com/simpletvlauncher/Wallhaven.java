package com.simpletvlauncher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Онлайн-каталог обоев wallhaven.cc и папка скачанных обоев.
 * Все обращения к wallhaven.cc собраны здесь. Они происходят только на экране каталога и при включённой пользователем онлайн-автосмене.
 * Запрашиваются только изображения без «взрослой» пометки (purity=100), переключателя для этого нет.
 */
final class Wallhaven {
    private Wallhaven() {}

    /** Темы: название, категории сайта (общее / аниме / люди), поисковый запрос. */
    static final String[][] THEMES = {
            {"Все обои", "110", ""},
            {"Природа", "100", "nature"},
            {"Горы", "100", "mountains"},
            {"Космос", "100", "space"},
            {"Города", "100", "city"},
            {"Животные", "100", "animals"},
            {"Абстракция", "100", "abstract"},
            {"Минимализм", "100", "minimalism"},
            {"Аниме", "010", ""},
            {"Люди", "001", ""},
    };
    static final String[] SORT_NAMES = {"Популярные", "Случайные", "Новые"};

    /** Папка скачанных обоев в общей памяти: переживает удаление лаунчера. */
    static final String DIR = "SimpleTVLauncher";
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    static final class Item {
        String id, thumb, full;
        long size;
        Bitmap bmp;
    }

    static final class Page {
        final List<Item> items = new ArrayList<>();
        int page, last;
        String seed = "";
    }

    /** Скачанный файл. */
    static final class Saved {
        Uri uri;
        String name;
    }

    interface Progress { void at(int percent); }

    static final class HttpError extends Exception {
        final int code;
        HttpError(int code) { super("HTTP " + code); this.code = code; }
    }

    /** Соединение только по HTTPS и только с wallhaven.cc и его поддоменами (адреса картинок приходят в ответе сайта). */
    private static HttpURLConnection open(String url) throws Exception {
        URL u = new URL(url);
        String host = u.getHost();
        if (!"https".equals(u.getProtocol()) || !(host.equals("wallhaven.cc") || host.endsWith(".wallhaven.cc")))
            throw new Exception("bad url");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "SimpleTVLauncher/1.6 (Android TV)");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new HttpError(code);
        }
        return c;
    }

    /** Текстовый ответ сайта (не больше 2 МБ). */
    static String text(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2_000_000) throw new Exception("too big");
            }
            return out.toString("UTF-8");
        }
    }

    /** Страница каталога. seed нужен для «случайной» сортировки, чтобы следующие страницы не повторяли предыдущие. */
    static Page search(int theme, int sort, int page, String seed) throws Exception {
        String[] t = THEMES[theme];
        StringBuilder u = new StringBuilder("https://wallhaven.cc/api/v1/search?purity=100&atleast=1920x1080&ratios=16x9");
        u.append("&categories=").append(t[1]);
        if (!t[2].isEmpty()) u.append("&q=").append(URLEncoder.encode(t[2], "UTF-8"));
        if (sort == 0) u.append("&sorting=toplist&topRange=1y");
        else if (sort == 1) u.append("&sorting=random");
        else u.append("&sorting=date_added");
        u.append("&page=").append(page);
        if (sort == 1 && seed.matches("[a-zA-Z0-9]{6}")) u.append("&seed=").append(seed);
        JSONObject root = new JSONObject(text(u.toString()));
        JSONArray data = root.getJSONArray("data");
        Page res = new Page();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            if (!"sfw".equals(o.optString("purity"))) continue;
            Item it = new Item();
            it.id = o.optString("id");
            it.full = o.optString("path");
            it.size = o.optLong("file_size");
            JSONObject th = o.optJSONObject("thumbs");
            it.thumb = th == null ? "" : th.optString("original", th.optString("large"));
            // идентификатор попадает в имя файла, поэтому только буквы и цифры
            if (it.id.matches("[a-zA-Z0-9]{1,16}") && !it.full.isEmpty() && !it.thumb.isEmpty()) res.items.add(it);
        }
        JSONObject meta = root.optJSONObject("meta");
        res.page = meta == null ? page : meta.optInt("current_page", page);
        res.last = meta == null ? page : meta.optInt("last_page", page);
        res.seed = meta == null || meta.isNull("seed") ? "" : meta.optString("seed", "");
        return res;
    }

    /** Миниатюра, уменьшенная под ширину плитки. */
    static Bitmap thumb(String url, int width) throws Exception {
        HttpURLConnection c = open(url);
        byte[] data;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 4_000_000) throw new Exception("too big");
            }
            data = out.toByteArray();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /**
     * Скачивает обои в «Pictures/SimpleTVLauncher» и возвращает ссылку на файл. Уже скачанные повторно не качаются.
     * Сайт отдаёт только оригинал, поэтому он скачивается во временный файл, а в папку попадает копия не крупнее Full HD.
     * Недокачанный файл в папку не попадает, временные удаляются в любом случае. Вызывать в фоне.
     */
    static synchronized Uri download(Context ctx, Item it, Progress p) throws Exception {
        Context c = ctx.getApplicationContext();
        String name = fileName(it), oldPng = "wallhaven-" + it.id + ".png";
        for (Saved s : saved(c)) if (name.equals(s.name) || oldPng.equals(s.name)) return s.uri;
        File tmp = new File(c.getCacheDir(), "wallhaven.tmp"), small = new File(c.getCacheDir(), "wallhaven.small");
        try {
            HttpURLConnection con = open(it.full);
            long total = con.getContentLength() > 0 ? con.getContentLength() : it.size, got = 0;
            int shown = -1;
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > MAX_BYTES) throw new Exception("too big");
                    out.write(buf, 0, n);
                    int pc = total > 0 ? (int) Math.min(100, got * 100 / total) : 0;
                    if (pc >= shown + 5) { shown = pc; p.at(pc); }
                }
            }
            if (con.getContentLength() > 0 && got != con.getContentLength()) throw new Exception("incomplete");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("not an image");
            // лёгкий JPEG не крупнее Full HD сохраняется как есть, всё остальное пережимается в JPEG не крупнее 1920 точек
            boolean fits = "image/jpeg".equals(o.outMimeType) && o.outWidth <= 1920 && o.outHeight <= 1920;
            if (fits && tmp.length() <= 700 * 1024) return store(c, tmp, name, "image/jpeg");
            shrink(tmp, small, o.outWidth, o.outHeight);
            return store(c, fits && tmp.length() <= small.length() ? tmp : small, name, "image/jpeg");
        } finally {
            tmp.delete();
            small.delete();
        }
    }

    static String fileName(Item it) { return "wallhaven-" + it.id + ".jpg"; }

    // ------------------------------------------------------------------ автосмена из интернета
    /** После неудачи (обычно нет сети) следующая попытка — через это время, а не через весь интервал. */
    private static final long RETRY_MS = 10 * 60 * 1000L;

    /** Очередная смена по расписанию. Вызывать в фоне. Без сети остаются прежние обои. */
    static boolean rotate(Context c, Prefs pr) {
        int theme = pr.wpOnline();
        if (theme < 0) return false;
        pr.setWpLast(System.currentTimeMillis());
        boolean ok = fetchAndApply(c, pr, theme);
        if (!ok) pr.setWpLast(System.currentTimeMillis() - Math.max(0, pr.wpIntervalMs() - RETRY_MS));
        return ok;
    }

    /** Скачивает случайные обои темы и ставит их фоном. Вызывать в фоне. */
    static synchronized boolean fetchAndApply(Context ctx, Prefs pr, int theme) {
        Context c = ctx.getApplicationContext();
        try {
            Page page = search(theme, 1, 1, "");
            Set<String> have = new HashSet<>();
            for (Saved s : saved(c)) have.add(s.name);
            String cur = pr.raw().getString("whCur", "");
            List<Item> fresh = new ArrayList<>(), other = new ArrayList<>();
            for (Item it : page.items) {
                if (!have.contains(fileName(it))) fresh.add(it);
                else if (!fileName(it).equals(cur)) other.add(it);
            }
            List<Item> from = fresh.isEmpty() ? other : fresh;
            if (from.isEmpty()) return false;
            Item it = from.get(new java.util.Random().nextInt(from.size()));
            Uri u = download(c, it, percent -> { });
            if (!Bg.applyUri(c, u)) return false;
            pr.raw().edit().putString("whCur", fileName(it)).apply();
            if (!have.contains(fileName(it))) remember(c, pr, fileName(it));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Имена файлов, скачанных автосменой, от старых к новым: только их лаунчер может удалять сам. */
    private static List<String> autoList(Prefs pr) {
        List<String> res = new ArrayList<>();
        for (String n : pr.raw().getString("whAuto", "").split(",")) if (!n.isEmpty()) res.add(n);
        return res;
    }

    private static void saveAuto(Prefs pr, List<String> l) {
        pr.raw().edit().putString("whAuto", android.text.TextUtils.join(",", l)).apply();
    }

    /** Файл больше не удаляется автоматически (пользователь выбрал его сам или нажал «Оставить»). true — он был в очереди на удаление. */
    static synchronized boolean keep(Prefs pr, String name) {
        List<String> l = autoList(pr);
        if (!l.remove(name)) return false;
        saveAuto(pr, l);
        return true;
    }

    /** Добавляет файл в очередь и удаляет самые старые сверх выбранного лимита. */
    private static void remember(Context c, Prefs pr, String name) {
        List<String> l = autoList(pr);
        l.remove(name);
        l.add(name);
        int limit = Prefs.WH_LIMITS[pr.whLimit()];
        if (limit > 0 && l.size() > limit) {
            List<Saved> files = saved(c);
            while (l.size() > limit) {
                String old = l.remove(0);
                for (Saved s : files) if (old.equals(s.name)) delete(c, s);
            }
        }
        saveAuto(pr, l);
    }

    private static void delete(Context c, Saved s) {
        try {
            if (Build.VERSION.SDK_INT >= 29) c.getContentResolver().delete(s.uri, null, null);
            else if (new File(s.uri.getPath()).delete())
                MediaScannerConnection.scanFile(c, new String[]{s.uri.getPath()}, null, null);
        } catch (Throwable ignored) {}        // например, файл остался от прежней установки: чужие файлы система удалять не даёт
    }

    private static void shrink(File src, File dst, int w, int h) throws Exception {
        int side = Math.max(w, h), sample = 1;
        while (side / (sample * 2) >= 1920) sample *= 2;        // огромный оригинал сразу читается уменьшенным
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
        if (b == null) throw new Exception("decode");
        float k = 1920f / Math.max(b.getWidth(), b.getHeight());
        if (k < 1f) {
            Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
            b.recycle();
            b = s;
        }
        try (OutputStream out = new FileOutputStream(dst)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new Exception("compress");
        } finally {
            b.recycle();
        }
    }

    /** Убирает временные файлы, если загрузку прервало закрытие приложения системой. */
    static void clearTemp(Context c) {
        new File(c.getCacheDir(), "wallhaven.tmp").delete();
        new File(c.getCacheDir(), "wallhaven.small").delete();
    }

    private static File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR);
    }

    private static Uri store(Context c, File src, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            File dir = legacyDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("mkdir");
            File dst = new File(dir, name);
            try (OutputStream out = new FileOutputStream(dst)) {
                copy(src, out);
            } catch (Exception e) {
                dst.delete();
                throw e;
            }
            MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);     // чтобы файл увидела галерея
            return Uri.fromFile(dst);
        }
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + DIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(Bg.imagesUri(), v);
        if (u == null) throw new Exception("insert");
        try (OutputStream out = cr.openOutputStream(u)) {
            if (out == null) throw new Exception("open");
            copy(src, out);
        } catch (Exception e) {
            cr.delete(u, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
        return u;
    }

    private static void copy(File src, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * Скачанные обои, новые сверху. Без доступа к памяти система показывает только файлы, скачанные этой установкой лаунчера;
     * после переустановки старые файлы появятся, когда доступ разрешён.
     */
    static List<Saved> saved(Context c) {
        List<Saved> res = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) {
            File[] files = legacyDir().listFiles();
            if (files == null) return res;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!f.isFile() || !(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue;
                Saved s = new Saved();
                s.uri = Uri.fromFile(f);
                s.name = f.getName();
                res.add(s);
            }
            return res;
        }
        try (Cursor q = c.getContentResolver().query(Bg.imagesUri(),
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{Environment.DIRECTORY_PICTURES + "/" + DIR + "/"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (q != null && q.moveToNext()) {
                Saved s = new Saved();
                s.uri = ContentUris.withAppendedId(Bg.imagesUri(), q.getLong(0));
                s.name = q.getString(1);
                res.add(s);
            }
        } catch (Throwable ignored) {}
        return res;
    }
}
