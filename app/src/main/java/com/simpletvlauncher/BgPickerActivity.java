package com.simpletvlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Выбор картинки для фона из памяти устройства (через MediaStore) или системным выбором файла. */
public class BgPickerActivity extends Activity {
    private static final int REQ_PERM = 3, REQ_PICK = 4, COLS = 4, LIMIT = 60;

    private static class Img { Uri uri; String bucket, name; Bitmap thumb; }

    private Prefs prefs;
    private float s;
    private LinearLayout col;
    private TextView sub;
    private final List<Img> all = new ArrayList<>();
    private String bucket = null;
    private boolean video;
    /** Режим выбора папки с обоями для автосмены: вместо картинок показан список папок внутренней памяти. */
    private boolean folders;
    private static class Folder { String id, name; int n; }
    private final List<Folder> folderList = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        video = getIntent().getBooleanExtra("video", false);
        folders = !video && getIntent().getBooleanExtra("folders", false);
        s = UI.scale(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this);
        root.addView(new BgView(this, prefs.settingsAccent()), new FrameLayout.LayoutParams(-1, -1));
        TextView title = UI.text(this, folders ? L.t("Автосмена обоев: выберите папку") : video ? L.t("Фон: выберите видео (mp4)") : L.t("Фон: выберите изображение"), 54 * s, 0xFFFFFFFF, GlassView.MED);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(80 * s));
        tl.leftMargin = Math.round(96 * s);
        tl.topMargin = Math.round(50 * s);
        root.addView(title, tl);
        sub = UI.text(this, folders ? L.t("Папка должна лежать во внутренней памяти устройства. Не удаляйте её, пока включена автосмена") : video ? L.t("Показаны недавние видео из памяти устройства") : L.t("Показаны недавние изображения из памяти устройства"), 26 * s, 0xA6FFFFFF, GlassView.REG);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(40 * s));
        sl.leftMargin = Math.round(96 * s);
        sl.topMargin = Math.round(132 * s);
        root.addView(sub, sl);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        FrameLayout.LayoutParams svl = new FrameLayout.LayoutParams(Math.round(1760 * s), H - Math.round(190 * s));
        svl.leftMargin = Math.round(80 * s);
        svl.topMargin = Math.round(182 * s);
        root.addView(sv, svl);
        setContentView(root);
        LiquidGlass.watch(getWindow());

        String perm = Build.VERSION.SDK_INT >= 33 ? (video ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_MEDIA_IMAGES) : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) start();
        else requestPermissions(new String[]{perm}, REQ_PERM);
    }

    private void start() { if (folders) loadFolders(); else load(); }

    /** Папки внутренней памяти с изображениями и число картинок в каждой. */
    private void loadFolders() {
        Weather.IO.execute(() -> {
            java.util.LinkedHashMap<String, Folder> m = new java.util.LinkedHashMap<>();
            try (Cursor c = getContentResolver().query(Bg.imagesUri(),
                    new String[]{MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0);
                    if (id == null) continue;
                    Folder f = m.get(id);
                    if (f == null) {
                        f = new Folder();
                        f.id = id;
                        f.name = c.getString(1) == null ? L.t("Без папки") : c.getString(1);
                        m.put(id, f);
                    }
                    f.n++;
                }
            } catch (Throwable ignored) {}
            final List<Folder> l = new ArrayList<>(m.values());
            java.util.Collections.sort(l, (a, b) -> a.name.compareToIgnoreCase(b.name));
            runOnUiThread(() -> { folderList.clear(); folderList.addAll(l); populateFolders(); });
        });
    }

    private void populateFolders() {
        col.removeAllViews();
        if (!prefs.wpBucket().isEmpty()) {
            GlassView off = row(L.t("Выключить автосмену"), Icons.IMAGE);
            off.setOnClickListener(v -> {
                prefs.clearWpFolder();
                Toast.makeText(this, L.t("Автосмена обоев выключена"), Toast.LENGTH_SHORT).show();
                finish();
            });
        }
        for (final Folder f : folderList) {
            GlassView r = row(f.name + " (" + f.n + ")", Icons.LIST);
            r.setOnClickListener(v -> pickFolder(f));
        }
        if (folderList.isEmpty()) {
            TextView t = UI.text(this, L.t("Папки с изображениями не найдены"), 30 * s, 0x99FFFFFF, GlassView.REG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(70 * s));
            lp.leftMargin = Math.round(16 * s);
            col.addView(t, lp);
        } else {
            col.getChildAt(0).requestFocus();
        }
    }

    private void pickFolder(final Folder f) {
        if (f.n < 2) { Toast.makeText(this, L.t("В папке должно быть минимум 2 изображения"), Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, L.t("Применяю фон…"), Toast.LENGTH_SHORT).show();
        Weather.IO.execute(() -> {
            prefs.setWpFolder(f.id, f.name);
            prefs.setBgMode(1);
            SoftVideo.forget(this);
            final boolean ok = Bg.wpNext(this, prefs);       // первые обои из папки ставятся сразу
            if (!ok) prefs.clearWpFolder();
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? L.t("Автосмена обоев включена") : L.t("Не удалось прочитать файл"), Toast.LENGTH_SHORT).show();
                if (ok) finish();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_PERM) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start();
            else {
                sub.setText(L.t("Нет доступа к памяти. Разрешите доступ или используйте системный выбор файла."));
                if (folders) populateFolders(); else populate();
            }
        }
    }

    private void load() {
        Weather.IO.execute(() -> {
            final List<Img> res = new ArrayList<>();
            final Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            try (Cursor c = getContentResolver().query(base,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext() && res.size() < 2000) {
                    Img i = new Img();
                    i.uri = ContentUris.withAppendedId(base, c.getLong(0));
                    i.bucket = c.getString(1) == null ? L.t("Без папки") : c.getString(1);
                    i.name = c.getString(2);
                    res.add(i);
                }
            } catch (Throwable ignored) {}
            runOnUiThread(() -> { all.clear(); all.addAll(res); populate(); });
        });
    }

    private GlassView row(String label, int icon) {
        GlassView r = new GlassView(this);
        r.left = true;
        r.label = label;
        r.icon = icon;
        r.radius = 40 * s;
        r.focusScale = 1.03f;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(900 * s), Math.round(80 * s));
        lp.bottomMargin = Math.round(12 * s);
        lp.leftMargin = Math.round(16 * s);
        col.addView(r, lp);
        return r;
    }

    private void populate() {
        col.removeAllViews();
        GlassView folder = row(L.t("Папка: ") + (bucket == null ? L.t("все") : bucket), Icons.LIST);
        folder.setOnClickListener(v -> chooseBucket());
        Intent get = new Intent(Intent.ACTION_GET_CONTENT).setType(video ? "video/*" : "image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (get.resolveActivity(getPackageManager()) != null) {
            GlassView sys = row(L.t("Системный выбор файла…"), Icons.IMAGE);
            sys.setOnClickListener(v -> startActivityForResult(get, REQ_PICK));
        }
        final int hdr = col.getChildCount();        // строки над картинками (папка, системный выбор)
        List<Img> shown = new ArrayList<>();
        for (Img i : all) if (bucket == null || bucket.equals(i.bucket)) { shown.add(i); if (shown.size() >= LIMIT) break; }
        if (shown.isEmpty()) {
            TextView t = UI.text(this, L.t("Изображения не найдены"), 30 * s, 0x99FFFFFF, GlassView.REG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(70 * s));
            lp.leftMargin = Math.round(16 * s);
            col.addView(t, lp);
            return;
        }
        float gap = 20 * s, tw = (1760 * s - 40 * s - (COLS - 1) * gap) / COLS, th = tw * 9f / 16f;
        LinearLayout rowL = null;
        List<Img> toLoad = new ArrayList<>();
        for (int k = 0; k < shown.size(); k++) {
            if (k % COLS == 0) {
                rowL = new LinearLayout(this);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, Math.round(th + gap));
                rp.leftMargin = Math.round(16 * s);
                col.addView(rowL, rp);
            }
            final Img img = shown.get(k);
            final GlassView g = new GlassView(this);
            g.radius = 20 * s;
            g.focusScale = 1.05f;
            g.painter = (c, v) -> paintThumb(c, v, img);
            g.setOnClickListener(v -> choose(img.uri));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(tw), Math.round(th));
            lp.rightMargin = Math.round(gap);
            rowL.addView(g, lp);
            if (img.thumb == null) toLoad.add(img);
        }
        loadThumbs(toLoad, Math.round(tw));
        if (col.getChildCount() > hdr) col.getChildAt(hdr).requestFocus(); else col.getChildAt(0).requestFocus();
    }


    private void paintThumb(Canvas c, GlassView v, Img img) {
        int w = v.getWidth(), h = v.getHeight();
        if (img.thumb != null) {
            Bitmap b = img.thumb;
            float sc = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            c.save();
            c.translate((w - b.getWidth() * sc) / 2f, (h - b.getHeight() * sc) / 2f);
            c.scale(sc, sc);
            c.drawBitmap(b, 0, 0, v.tp);
            c.restore();
        }
        v.text(c, img.name == null ? "" : img.name, w / 2f, h * 0.94f, h * 0.09f, 0xFFFFFFFF, 1, Paint.Align.CENTER, w * 0.94f);    }

    private void loadThumbs(final List<Img> list, final int width) {
        Weather.IO.execute(() -> {
            for (Img i : list) {
                if (video) {
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            i.thumb = getContentResolver().loadThumbnail(i.uri, new android.util.Size(width, width * 9 / 16), null);
                            runOnUiThread(this::invalidateAll);
                        }
                    } catch (Throwable ignored) {}
                    continue;
                }
                try (InputStream in = getContentResolver().openInputStream(i.uri)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= width) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    o2.inPreferredConfig = Bitmap.Config.RGB_565;
                    try (InputStream in2 = getContentResolver().openInputStream(i.uri)) {
                        i.thumb = BitmapFactory.decodeStream(in2, null, o2);
                    }
                    runOnUiThread(() -> col.invalidate());
                    runOnUiThread(this::invalidateAll);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void invalidateAll() {
        for (int r = 0; r < col.getChildCount(); r++) {
            View v = col.getChildAt(r);
            if (v instanceof LinearLayout) for (int k = 0; k < ((LinearLayout) v).getChildCount(); k++) ((LinearLayout) v).getChildAt(k).invalidate();
        }
    }

    private void chooseBucket() {
        Set<String> set = new LinkedHashSet<>();
        for (Img i : all) set.add(i.bucket);
        final List<String> names = new ArrayList<>();
        names.add(L.t("Все папки"));
        names.addAll(set);
        GlassDialog.menu(this, L.t("Папка"), names.toArray(new String[0]), i -> {
            bucket = i == 0 ? null : names.get(i);
            populate();
        }).show();
    }

    private void choose(final Uri uri) {
        Toast.makeText(this, L.t("Применяю фон…"), Toast.LENGTH_SHORT).show();
        Weather.IO.execute(() -> {
            final boolean ok = video ? Bg.setVideo(this, uri) : Bg.setImage(this, uri);
            if (ok) prefs.setBgMode(video ? 2 : 1);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? L.t("Фон установлен") : L.t("Не удалось прочитать файл"), Toast.LENGTH_SHORT).show();
                if (ok) {
                    if (!video) SoftVideo.forget(this);
                    if (video) SoftVideo.request(this, f -> { });       // размытый кадр для меню считается один раз, сразу при выборе
                    finish();
                }
            });
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) choose(data.getData());
    }
}
