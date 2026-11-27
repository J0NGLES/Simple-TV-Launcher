package com.simpletvlauncher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Стеклянные диалоги: меню действий и ввод текста. */
public class GlassDialog extends Dialog {
    public interface OnPick { void pick(int index); }
    public interface OnText { void text(String s); }

    private GlassDialog(Context c) { super(c, R.style.GlassDialog); }

    private static GlassDialog base(Context c, String title, View body, float s, int widthRef) {
        final GlassDialog d = new GlassDialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (c instanceof android.app.Activity) Backdrop.capture((android.app.Activity) c); else Backdrop.clear();
        final float cr = 40 * s;
        final android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF rr = new android.graphics.RectF();
        LinearLayout panel = new LinearLayout(c) {
            @Override protected void onDraw(android.graphics.Canvas cv) {
                float w = getWidth(), h = getHeight();
                rr.set(0, 0, w, h);
                tp.setStyle(android.graphics.Paint.Style.FILL);
                if (Backdrop.has()) {
                    Backdrop.draw(this, cv, w, h, cr, false);
                    tp.setColor(Backdrop.shade());
                } else tp.setColor(0xE61A1A1E);
                cv.drawRoundRect(rr, cr, cr, tp);
                tp.setColor(0x12FFFFFF);
                cv.drawRoundRect(rr, cr, cr, tp);
                tp.setStyle(android.graphics.Paint.Style.STROKE);
                tp.setStrokeWidth(Math.max(2f, 2 * (cr / 40f)));
                tp.setColor(0x44FFFFFF);
                rr.inset(tp.getStrokeWidth() / 2, tp.getStrokeWidth() / 2);
                cv.drawRoundRect(rr, cr, cr, tp);
            }
        };
        panel.setWillNotDraw(false);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(32 * s);
        panel.setPadding(pad, pad, pad, pad);
        TextView t = UI.text(c, title, 36 * s, 0xFFE6E0E9, GlassView.MED);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * s));
        tl.bottomMargin = Math.round(14 * s);
        panel.addView(t, tl);
        panel.addView(body);
        d.setContentView(panel, new ViewGroup.LayoutParams(Math.round(widthRef * s) + 2 * pad, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(Math.round(widthRef * s) + 2 * pad, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.30f);
            LiquidGlass.watch(w);
        }
        return d;
    }

    private static GlassView row(Context c, String text, float s) {
        GlassView g = new GlassView(c);
        g.left = true;
        g.label = text;
        g.material = true;
        g.blurBack = true;
        g.radius = 16 * s;
        return g;
    }

    public static GlassDialog menu(Context c, String title, String[] items, final OnPick cb) {
        return menu(c, title, items, null, 0, cb);
    }

    /** Меню, где при открытии выбран пункт defIdx (например «Отмена» в подтверждении). */
    public static GlassDialog menu(Context c, String title, String[] items, int defIdx, final OnPick cb) {
        return menu(c, title, items, null, defIdx, cb);
    }

    /** Меню с цветными кружками слева (выбор цвета). */
    public static GlassDialog menu(Context c, String title, String[] items, int[] swatches, final OnPick cb) {
        return menu(c, title, items, swatches, 0, cb);
    }

    /** Меню со значками слева (типы Icons.*), например источники сигнала. */
    public static GlassDialog menuIcons(Context c, String title, String[] items, int[] icons, final OnPick cb) {
        return build(c, title, items, null, icons, 0, cb);
    }

    public static GlassDialog menu(Context c, String title, String[] items, int[] swatches, final int defIdx, final OnPick cb) {
        return build(c, title, items, swatches, null, defIdx, cb);
    }

    private static GlassDialog build(Context c, String title, String[] items, int[] swatches, int[] icons, final int defIdx, final OnPick cb) {
        final float s = UI.scale(c);
        final LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        final GlassDialog[] ref = new GlassDialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            GlassView r = row(c, items[i], s);
            if (swatches != null) r.swatch = swatches[i];
            if (icons != null) r.icon = icons[i];
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ref[0].dismiss();
                    cb.pick(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(80 * s));
            lp.bottomMargin = Math.round(10 * s);
            col.addView(r, lp);
        }
        int room = UI.tvos ? Math.round(16 * s) : 0;
        col.setPadding(room, room / 2, room, 0); col.setClipToPadding(false);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(col);
        int maxH = Math.round(780 * s);
        int need = items.length * Math.round(90 * s) + room / 2;
        LinearLayout holder = new LinearLayout(c);
        holder.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, need)));
        ref[0] = base(c, title, holder, s, Math.max(760, Math.min(1500, title.length() * 21)));     // длинный заголовок — шире окно
        // фокус сразу стоит на нужном пункте, а не «нигде», пока не нажмёшь вниз
        ref[0].setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                View v = col.getChildAt(Math.max(0, Math.min(defIdx, col.getChildCount() - 1)));
                if (v != null) v.requestFocus();
            }
        });
        return ref[0];
    }
    /** Диалог с пояснением (текст) и кнопками под ним. */
    public static GlassDialog info(Context c, String title, String text, String[] buttons, final OnPick cb) {
        final float s = UI.scale(c);
        final LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = UI.text(c, text, 27 * s, 0xDDFFFFFF, GlassView.REG);
        tv.setSingleLine(false);
        tv.setMaxLines(30);
        tv.setEllipsize(null);
        tv.setLineSpacing(0, 1.15f);
        col.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final GlassDialog[] ref = new GlassDialog[1];
        for (int i = 0; i < buttons.length; i++) {
            final int idx = i;
            GlassView r = row(c, buttons[i], s);
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { ref[0].dismiss(); cb.pick(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(80 * s));
            lp.topMargin = Math.round(i == 0 ? 22 * s : 10 * s);
            col.addView(r, lp);
        }
        if (UI.tvos) { col.setPadding(Math.round(16 * s), 0, Math.round(16 * s), 0); col.setClipToPadding(false); }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.addView(col);
        ref[0] = base(c, title, sv, s, 900);
        ref[0].setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                View v = col.getChildAt(1);
                if (v != null) v.requestFocus();
            }
        });
        return ref[0];
    }

    public static GlassDialog input(Context c, String title, String hint, String initial, final OnText cb) {
        final float s = UI.scale(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        final EditText et = new EditText(c);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setSelection(et.getText().length());
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * s);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0x66FFFFFF);
        if (UI.contrast) { float d = Math.max(1, Math.round(2 * s)); et.setShadowLayer(0.01f, d, d, 0xFF000000); }
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable eb = new GradientDrawable();
        eb.setColor(0xFF2B2930);
        eb.setStroke(Math.max(2, Math.round(2 * s)), 0xFF938F99);
        eb.setCornerRadius(16 * s);
        et.setBackground(eb);
        int ep = Math.round(24 * s);
        et.setPadding(ep, ep / 2, ep, ep / 2);
        col.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(88 * s)));

        final GlassDialog[] ref = new GlassDialog[1];
        GlassView ok = row(c, L.t("Готово"), s);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ref[0].dismiss(); cb.text(et.getText().toString()); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(80 * s));
        lp.topMargin = Math.round(14 * s);
        col.addView(ok, lp);
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE) { ref[0].dismiss(); cb.text(et.getText().toString()); return true; }
                return false;
            }
        });
        ref[0] = base(c, title, col, s, 900);
        ref[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        return ref[0];
    }

    /** Проверка введённого PIN-кода: null — верно, иначе текст ошибки (диалог остаётся открытым). */
    public interface PinCheck { String check(String code); }

    private static class KeyCell extends GlassView {
        final boolean big;
        KeyCell(Context c, String t, boolean big) {
            super(c);
            label = t;
            this.big = big;
            material = true;
            blurBack = true;
            radius = 16 * UI.scale(c);
            focusScale = 1.05f;
        }
        @Override protected void drawContent(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            text(cv, label, w / 2f, h * (big ? 0.68f : 0.62f), h * (big ? 0.46f : 0.27f), isFocused() && !UI.tvos ? 0xFF1B1B22 : 0xF2FFFFFF,
                    big ? 1 : 2, android.graphics.Paint.Align.CENTER, w * 0.92f);
        }
    }

    /**
     * Ввод PIN-кода из 4 цифр прямо в окне лаунчера (без системной клавиатуры): цифры можно выбирать кнопками на экране
     * или набирать цифрами на пульте. Ввели четыре цифры — они проверяются; неверный код — надпись об ошибке, ввод заново.
     */
    public static GlassDialog pin(final Context c, String title, String hint, final PinCheck check, final Runnable ok) {
        final float s = UI.scale(c);
        final StringBuilder code = new StringBuilder();
        final boolean[] bad = {false};
        final String hintText = hint;
        final GlassDialog[] ref = new GlassDialog[1];

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView msg = UI.text(c, hint, 27 * s, 0xDDFFFFFF, GlassView.REG);
        msg.setGravity(Gravity.CENTER);
        col.addView(msg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * s)));

        final android.graphics.Paint dp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final View dots = new View(c) {
            @Override protected void onDraw(android.graphics.Canvas cv) {
                float r = 13 * s, step = 58 * s, x0 = getWidth() / 2f - 1.5f * step, y = getHeight() / 2f;
                dp.setStrokeWidth(Math.max(2f, 2.5f * s));
                dp.setColor(bad[0] ? 0xFFFF7A6B : 0xFFFFFFFF);
                for (int i = 0; i < 4; i++) {
                    dp.setStyle(i < code.length() ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
                    cv.drawCircle(x0 + i * step, y, r, dp);
                }
            }
        };
        col.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * s)));
        final int[] pending = {-1};
        final Runnable erase = new Runnable() {
            @Override public void run() {
                if (code.length() > 0) code.deleteCharAt(code.length() - 1);
                bad[0] = false;
                msg.setText(hintText);
                msg.setTextColor(0xDDFFFFFF);
                dots.invalidate();
            }
        };
        final Runnable digit = new Runnable() {
            @Override public void run() {
                if (bad[0]) { bad[0] = false; msg.setText(hintText); msg.setTextColor(0xDDFFFFFF); }
                if (code.length() < 4) code.append(pending[0]);
                dots.invalidate();
                if (code.length() < 4) return;
                String err = check.check(code.toString());
                if (err == null) {
                    ref[0].dismiss();
                    if (ok != null) ok.run();
                } else {
                    code.setLength(0);
                    bad[0] = true;
                    msg.setText(err);
                    msg.setTextColor(0xFFFF9A8A);
                    dots.invalidate();
                }
            }
        };

        final String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {L.t("Стереть"), "0", L.t("Отмена")}};
        final View[] cells = new View[12];
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 3; k++) {
                final String t = rows[r][k];
                final boolean isDigit = t.length() == 1;
                KeyCell cell = new KeyCell(c, isDigit ? t : L.t(t), isDigit);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (isDigit) { pending[0] = t.charAt(0) - '0'; digit.run(); }
                        else if (t.equals(rows[3][0])) erase.run();
                        else ref[0].dismiss();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(84 * s), 1f);
                lp.setMargins(Math.round(5 * s), Math.round(5 * s), Math.round(5 * s), Math.round(5 * s));
                row.addView(cell, lp);
                cells[r * 3 + k] = cell;
            }
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ref[0] = base(c, title, col, s, 540);
        ref[0].setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) { cells[4].requestFocus(); }
        });
        // цифры и «Стереть» с пульта
        ref[0].setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int kc, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return false;
                int n = -1;
                if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) n = kc - KeyEvent.KEYCODE_0;
                else if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9) n = kc - KeyEvent.KEYCODE_NUMPAD_0;
                if (n >= 0) { pending[0] = n; digit.run(); return true; }
                if (kc == KeyEvent.KEYCODE_DEL || kc == KeyEvent.KEYCODE_FORWARD_DEL) { erase.run(); return true; }
                return false;
            }
        });
        return ref[0];
    }
}
