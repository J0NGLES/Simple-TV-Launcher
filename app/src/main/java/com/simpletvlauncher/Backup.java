package com.simpletvlauncher;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Резервная копия: один zip-файл в папке «Загрузки» с настройками (prefs.json: порядок и скрытые плитки, обои-режим,
 * город, провайдеры и т. д.) и файлами фона (картинка/видео), если они заданы.
 */
public final class Backup {
    private Backup() {}

    public static final String PREFIX = "SimpleTVLauncher-backup";

    public static class Item {
        public Uri uri;
        public String name;
        public long size, time;
        public String bg = "", grid = "", pos = "", glow = "";
        public boolean ok = true;          // false: файл есть, но восстановить из него нельзя
        public String problem = "";
    }

    /** Создаёт копию в «Загрузках» (каждая — отдельным файлом с датой). Возвращает место сохранения. */
    public static String save(Context c) throws Exception {
        byte[] data = build(c);
        String name = PREFIX + "-" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(new java.util.Date()) + ".zip";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception(L.t("не удалось создать файл"));
            try (OutputStream o = cr.openOutputStream(u)) { o.write(data); }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(data); }
        }
        return L.t("Загрузки/") + name;
    }

    /** Список копий в «Загрузках», новые первыми. */
    public static java.util.List<Item> list(Context c) {
        java.util.List<Item> res = new java.util.ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED},
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{PREFIX + "%"}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (q != null && q.moveToNext()) {
                    Item it = new Item();
                    it.uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, q.getLong(0));
                    it.name = q.getString(1);
                    it.size = q.getLong(2);
                    it.time = timeOf(it.name, q.getLong(3) * 1000L);
                    if (seen.add(it.name)) res.add(it);
                }
            } catch (Throwable ignored) {}
        }
        try {
            File[] fs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles();
            if (fs != null) for (File f : fs) {
                if (!f.getName().startsWith(PREFIX) || !seen.add(f.getName())) continue;
                Item it = new Item();
                it.uri = Uri.fromFile(f);
                it.name = f.getName();
                it.size = f.length();
                it.time = timeOf(it.name, f.lastModified());
                res.add(it);
            }
        } catch (Throwable ignored) {}
        java.util.Collections.sort(res, (a, b) -> Long.compare(b.time, a.time));
        for (Item it : res) describe(c, it);
        return res;
    }

    private static long timeOf(String name, long fallback) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})").matcher(name);
            if (m.find()) return new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).parse(m.group(1)).getTime();
        } catch (Exception ignored) {}
        return fallback;
    }

    private static final String[] BG_NAMES = {"градиент", "картинка", "видеофайл"};

    /** Читает копию и заполняет описание: тип фона и сетка. Если прочитать нельзя — помечает, что восстановить её не получится. */
    /** Описание копии, выбранной вручную через системный проводник. */
    public static Item inspect(Context c, Uri uri) {
        Item it = new Item();
        it.uri = uri;
        it.name = "";
        try (Cursor q = c.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) it.name = q.getString(0);
        } catch (Throwable ignored) {}
        it.time = timeOf(it.name == null ? "" : it.name, System.currentTimeMillis());
        describe(c, it);
        return it;
    }

    private static void describe(Context c, Item it) {
        try (InputStream in = c.getContentResolver().openInputStream(it.uri); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            boolean jpg = false, mp4 = false;
            JSONObject root = null;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("prefs.json")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    copy(z, bo);
                    root = new JSONObject(bo.toString("UTF-8"));
                } else if (e.getName().equals("bg.jpg")) jpg = true;
                else if (e.getName().equals("bg.mp4")) mp4 = true;
            }
            if (root == null) {
                it.ok = false;
                it.problem = L.t("В файле нет настроек лаунчера. Возможно, это копия от очень старой версии");
                return;
            }
            int mode = 0;
            if (root.has("bgMode2")) mode = root.getJSONObject("bgMode2").optInt("v", 0);
            else if (root.has("bgMode")) { int o = root.getJSONObject("bgMode").optInt("v", 0); mode = o == 2 ? 1 : o == 3 ? 2 : 0; }
            mode = Math.max(0, Math.min(2, mode));
            String bg = L.t(BG_NAMES[mode]);
            if (mode == 1 && !jpg) bg += L.t(" (файла нет)");
            if (mode == 2 && !mp4) bg += L.t(" (файла нет)");
            int cols = root.has("cols") ? root.getJSONObject("cols").optInt("v", 7) : 7;
            cols = Math.max(4, Math.min(12, cols));
            if (mode == 1) bg += " \u00b7 " + L.t("размытие: ") + Math.max(0, Math.min(100, intOf(root, "bgBlur", 0))) + " %";
            it.bg = L.t("Фон: ") + bg;
            int tile = Math.max(0, Math.min(5, intOf(root, "tileSize", 0)));
            int pin = Math.max(0, Math.min(2, intOf(root, "gridPin", 0)));
            int al = intOf(root, "gridAlign", 0) == 1 ? 1 : 0;
            boolean gl = root.has("glow") && root.getJSONObject("glow").optBoolean("v", false);
            boolean showTitle = !root.has("showTitle") || root.getJSONObject("showTitle").optBoolean("v", true);
            int font = Math.max(0, Math.min(2, intOf(root, "font", 1)));
            java.util.Locale lc = L.locale();
            it.grid = L.t("Сетка: ") + cols + L.t(" в ряду") + " \u00b7 " + L.t("плитки: ") + L.t(Prefs.TILE_NAMES[tile]).toLowerCase(lc) + " \u00b7 " + L.t("названия: ") + L.t(showTitle ? "вкл" : "выкл");
            it.pos = L.t("Положение: ") + L.t(Prefs.PIN_NAMES[pin]).toLowerCase(lc) + " \u00b7 " + L.t("центровка: ") + L.t(Prefs.ALIGN_NAMES[al]).toLowerCase(lc);
            it.glow = L.t("Свечение: ") + L.t(gl ? "включено" : "выключено") + " \u00b7 " + L.t("шрифт: ") + L.t(Prefs.FONT_NAMES[font]).toLowerCase(lc);
        } catch (Throwable t) {
            it.ok = false;
            it.problem = L.t("Файл повреждён или создан другой версией, прочитать его не удалось");
        }
    }
    /** Целое значение настройки из копии (если её там нет — значение по умолчанию, как и при восстановлении). */
    private static int intOf(JSONObject root, String key, int def) throws Exception {
        return root.has(key) ? root.getJSONObject(key).optInt("v", def) : def;
    }

    private static byte[] build(Context c) throws Exception {
        SharedPreferences sp = new Prefs(c).raw();
        JSONObject root = new JSONObject();
        java.util.List<String> secure = java.util.Arrays.asList(Prefs.SECURE_KEYS);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (secure.contains(e.getKey())) continue;       // PIN-код и защита приложений в копию не попадают
            Object v = e.getValue();
            JSONObject o = new JSONObject();
            if (v instanceof Boolean) { o.put("t", "b"); o.put("v", v); }
            else if (v instanceof Integer) { o.put("t", "i"); o.put("v", v); }
            else if (v instanceof Long) { o.put("t", "l"); o.put("v", v); }
            else if (v instanceof Float) { o.put("t", "f"); o.put("v", (double) (Float) v); }
            else if (v instanceof String) { o.put("t", "s"); o.put("v", v); }
            else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object x : (Set<?>) v) a.put(String.valueOf(x));
                o.put("t", "set"); o.put("v", a);
            } else continue;
            root.put(e.getKey(), o);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("prefs.json"));
            z.write(root.toString().getBytes("UTF-8"));
            z.closeEntry();
            File[] extra = {new File(c.getFilesDir(), "bg.jpg"), Bg.videoFile(c)};
            for (File f : extra) {
                if (!f.exists()) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) { copy(in, z); }
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    /** Восстанавливает из zip. Старые файлы фона заменяются тем, что в копии. */
    public static void restore(Context c, Uri uri) throws Exception {
        JSONObject root = null;
        File jpg = new File(c.getFilesDir(), "bg.jpg.new"), mp4 = new File(c.getFilesDir(), "bg.mp4.new");
        boolean gotJpg = false, gotMp4 = false;
        try (InputStream in = c.getContentResolver().openInputStream(uri); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("prefs.json")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    copy(z, bo);
                    root = new JSONObject(bo.toString("UTF-8"));
                } else if (n.equals("bg.jpg")) {
                    try (FileOutputStream o = new FileOutputStream(jpg)) { copy(z, o); }
                    gotJpg = true;
                } else if (n.equals("bg.mp4")) {
                    try (FileOutputStream o = new FileOutputStream(mp4)) { copy(z, o); }
                    gotMp4 = true;
                }
            }
        }
        if (root == null) throw new Exception(L.t("это не резервная копия лаунчера"));

        SharedPreferences cur = new Prefs(c).raw();
        SharedPreferences.Editor ed = cur.edit();
        ed.clear();
        java.util.Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (java.util.Arrays.asList(Prefs.SECURE_KEYS).contains(k)) continue;
            JSONObject o = root.getJSONObject(k);
            switch (o.getString("t")) {
                case "b": ed.putBoolean(k, o.getBoolean("v")); break;
                case "i": ed.putInt(k, o.getInt("v")); break;
                case "l": ed.putLong(k, o.getLong("v")); break;
                case "f": ed.putFloat(k, (float) o.getDouble("v")); break;
                case "s": ed.putString(k, o.getString("v")); break;
                case "set": {
                    JSONArray a = o.getJSONArray("v");
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    ed.putStringSet(k, s);
                    break;
                }
                default: break;
            }
        }
        // текущие PIN-код, защита приложений и «режим для бабушек» восстановление не затрагивает
        for (String k : Prefs.SECURE_KEYS) {
            Object v = cur.getAll().get(k);
            if (v instanceof String) ed.putString(k, (String) v);
            else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
        }
        ed.commit();

        File bj = new File(c.getFilesDir(), "bg.jpg"), bv = Bg.videoFile(c);
        if (gotJpg) { bj.delete(); jpg.renameTo(bj); } else { bj.delete(); jpg.delete(); }
        if (gotMp4) { bv.delete(); mp4.renameTo(bv); } else { bv.delete(); mp4.delete(); }
        Bg.clearCache();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
}
