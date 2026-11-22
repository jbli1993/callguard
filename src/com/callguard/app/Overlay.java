package com.callguard.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 来电时的悬浮窗提示。
 *
 * <p>为什么需要它：Android 的 heads-up（横幅）同一时间只有一个坑位，来电时被系统来电通知占着，
 * 普通通知只能进通知栏。只有 {@code TYPE_APPLICATION_OVERLAY} 画在所有 Activity 之上，
 * 才能出现在来电界面里。
 *
 * <p><b>位置</b>：贴在系统来电横幅的正下方。偏移按当前默认拨号器算：
 * 我们自己的横幅约 104dp → 留一点空隙（120dp）；系统/Google 的约 143dp → 155dp。
 * 层级低于 SystemUI，盖不到横幅上面，所以只能往下让。宽度和来电框对齐（左右各 16dp）。
 *
 * <p><b>结果类</b>：一条很矮的横条（一行标签 + 色调圆点），点一下向下展开——
 * 展开后左边是归属地，右边两个纯图标按钮（查询 / 关闭）。号码不重复显示，
 * 系统横幅上已经有了。
 */
final class Overlay {

    /** 系统横幅（含按钮）大约占据的高度，留一点余量。实测 143dp。 */
    private static final int BELOW_BANNER_DP = 155;

    /** 结果条：存在 30 秒，点开以后重新计时。 */
    private static final long RESULT_TOTAL_MS = 30_000L;

    private static final int COLOR_BAD = 0xFFE5484D;
    private static final int COLOR_WARN = 0xFFFFB224;
    private static final int COLOR_GOOD = 0xFF2F9E44;
    private static final int COLOR_NEUTRAL = 0xFF9CD2F0;

    private static WindowManager wm;
    private static View current;
    private static WindowManager.LayoutParams currentLp;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // 结果条的状态：展开/收起、重新渲染时要用
    private static Context lastCtx;
    private static String lastTitle = "";
    private static String lastBody = "";
    private static Runnable lastAction;
    private static int lastTone = COLOR_NEUTRAL;

    private Overlay() {
    }

    static boolean canShow(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** 此刻该不该用悬浮窗：开关 + 权限。 */
    static boolean shouldUse(Context ctx, Rules rules) {
        return rules.overlayOn() && canShow(ctx);
    }

    /** 打开系统的「显示在其他应用上层」授权页。 */
    static void requestPermission(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 结果提示（无查询按钮）：一条横条，点开看归属地。 */
    static void showResult(Context ctx, String title, String body) {
        showResult(ctx, title, body, null, null);
    }

    /**
     * 结果提示。
     *
     * @param body   归属地；会和标题拼在同一行（**不再重复号码**）
     * @param action 查询按钮的动作；null 表示不显示查询按钮
     * @param tone   {@code bad/warn/good/neutral}，决定左侧圆点颜色
     */
    static void showResult(Context ctx, String title, String body, Runnable action, String tone) {
        if (!canShow(ctx)) return;
        lastCtx = ctx.getApplicationContext();
        lastTitle = title == null ? "" : title;
        lastBody = body == null ? "" : body;
        lastAction = action;
        lastTone = toneColor(tone);
        render(lastCtx);

        MAIN.removeCallbacks(autoDismiss);
        MAIN.postDelayed(autoDismiss, RESULT_TOTAL_MS);
        android.util.Log.i("CallGuard", "overlay bar " + lastTitle);
    }

    /** 查询中：普通卡片，留 20 秒。 */
    static void showBusy(Context ctx, String title, String body) {
        if (!canShow(ctx)) return;
        showCardNow(ctx.getApplicationContext(), title, body, null, null, false, 20_000L);
    }

    /** 询问类提示：带一个文字按钮，点完就消失。 */
    static void ask(Context ctx, String title, String body, String actionLabel,
                    Runnable action, long timeoutMs) {
        if (!canShow(ctx)) return;
        showCardNow(ctx.getApplicationContext(), title, body, actionLabel, action, true, timeoutMs);
    }

    static void dismiss() {
        MAIN.post(() -> {
            dismissNow();
            MAIN.removeCallbacks(autoDismiss);
        });
    }

    // ---------------- 结果条（横条 ⇄ 展开） ----------------

    private static void render(Context app) {
        MAIN.post(() -> {
            try {
                if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                boolean dark = (app.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                int[] p = palette(app, dark);
                int bgColor = p[0];
                int titleColor = p[1];
                int iconColor = p[2];
                int accent = p[3];
                int strokeColor = p[4];
                int dotColor = lastTone == 0 ? accent : lastTone;

                int padH = dp(app, 14);
                LinearLayout card = new LinearLayout(app);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(padH, dp(app, 3), dp(app, 8), dp(app, 3));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(bgColor);
                bg.setCornerRadius(dp(app, 999));
                bg.setStroke(dp(app, 1), strokeColor);
                card.setBackground(bg);

                View dot = new View(app);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(dotColor);
                dot.setBackground(dotBg);
                int ds = dp(app, 8);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(ds, ds);
                dotLp.setMarginEnd(dp(app, 8));
                card.addView(dot, dotLp);

                TextView title = new TextView(app);
                title.setText(lastBody.isEmpty() ? lastTitle : lastTitle + " · " + lastBody);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                title.setTextColor(titleColor);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setMaxLines(1);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(title,
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                if (lastAction != null) {
                    card.addView(iconButton(app, R.drawable.ic_cg_search,
                            accent, dark ? 0x33FFFFFF : 0x22000000, v -> {
                                Runnable a = lastAction;
                                dismiss();
                                if (a != null) {
                                    try {
                                        a.run();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }));
                }
                ImageView close = iconButton(app, R.drawable.ic_cg_close,
                        iconColor, dark ? 0x33FFFFFF : 0x22000000, v -> dismiss());
                // 两个按钮之间留出"死区"：手滑一下什么都不会发生，不会顺手把卡片关掉
                LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                        close.getLayoutParams().width, close.getLayoutParams().height);
                closeLp.setMarginStart(dp(app, 14));
                card.addView(close, closeLp);

                WindowManager.LayoutParams lp = baseParams(app);
                lp.width = screenW(app) - dp(app, 32);
                replace(card, lp);
                android.util.Log.i("CallGuard", "overlay bar y=" + lp.y + " w=" + lp.width);
            } catch (Throwable t) {
                android.util.Log.w("CallGuard", "overlay bar failed", t);
            }
        });
    }

    /** 纯图标按钮：30dp 触摸区、18dp 图标、圆形 ripple。 */
    private static ImageView iconButton(Context app, int res, int tint, int rippleColor,
                                        View.OnClickListener onClick) {
        ImageView iv = new ImageView(app);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        int box = dp(app, 30);
        int icon = dp(app, 18);
        int pad = (box - icon) / 2;
        iv.setPadding(pad, pad, pad, pad);
        iv.setLayoutParams(new LinearLayout.LayoutParams(box, box));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        iv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor), null, mask));
        iv.setOnClickListener(onClick);
        return iv;
    }

    // ---------------- 通用卡片（查询中 / 付费询问） ----------------

    private static void showCardNow(Context app, String title, String body, String actionLabel,
                                    Runnable action, boolean touchable, long timeoutMs) {
        MAIN.post(() -> {
            try {
                if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                boolean dark = (app.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                int[] p = palette(app, dark);
                int bgColor = p[0];
                int titleColor = p[1];
                int bodyColor = p[2];
                int accent = p[3];
                int strokeColor = p[4];

                LinearLayout card = new LinearLayout(app);
                card.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(app, 13);
                card.setPadding(pad, dp(app, 10), pad, dp(app, 10));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(bgColor);
                bg.setCornerRadius(dp(app, 18));
                bg.setStroke(dp(app, 1), strokeColor);
                card.setBackground(bg);

                TextView t = new TextView(app);
                t.setText(title == null ? "" : title);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setTextColor(titleColor);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setMaxLines(1);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(t);

                if (body != null && !body.isEmpty()) {
                    TextView b = new TextView(app);
                    b.setText(body);
                    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    b.setTextColor(bodyColor);
                    b.setPadding(0, dp(app, 2), 0, 0);
                    b.setMaxLines(3);
                    b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    card.addView(b);
                }

                if (actionLabel != null && action != null) {
                    Button btn = new Button(app);
                    btn.setText(actionLabel);
                    btn.setAllCaps(false);
                    btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    btn.setTextColor(0xFF0B1B24);
                    GradientDrawable bb = new GradientDrawable();
                    bb.setColor(accent);
                    bb.setCornerRadius(dp(app, 999));
                    btn.setBackground(bb);
                    btn.setStateListAnimator(null);
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(app, 36));
                    blp.topMargin = dp(app, 8);
                    btn.setLayoutParams(blp);
                    btn.setOnClickListener(v -> {
                        dismiss();
                        try {
                            action.run();
                        } catch (Throwable ignored) {
                        }
                    });
                    card.addView(btn);
                }

                WindowManager.LayoutParams lp = baseParams(app);
                lp.width = Math.min(dp(app, 300), screenW(app) - dp(app, 32));
                replace(card, lp);
                android.util.Log.i("CallGuard", "overlay card y=" + lp.y + " touchable=" + touchable);

                MAIN.removeCallbacks(autoDismiss);
                MAIN.postDelayed(autoDismiss, timeoutMs);
            } catch (Throwable t) {
                android.util.Log.w("CallGuard", "overlay card failed", t);
            }
        });
    }

    private static WindowManager.LayoutParams baseParams(Context app) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int screenH = app.getResources().getDisplayMetrics().heightPixels;
        int topInset = topInsetPx(app);
        int below = belowBannerDp(app);
        lp.y = Math.min(topInset + dp(app, below), (int) (screenH * 0.55f));
        return lp;
    }

    /**
     * 横幅下沿大概在多少 dp（相对状态栏）。
     *
     * <p>两套横幅高度差很多：
     * <ul>
     *   <li>我们的拨号器：自定义 RemoteViews，两行文字 + 52dp 按钮，约 <b>104dp</b>；</li>
     *   <li>系统/Google 拨号器：约 <b>143dp</b>（展开还会再长一行）。</li>
     * </ul>
     * 所以按当前默认拨号器选偏移：我们自己的留一点空隙（120dp，约 14dp），
     * 别家的保持原来的距离（155dp）。
     */
    private static int belowBannerDp(Context app) {
        try {
            Object tm = app.getSystemService(Context.TELECOM_SERVICE);
            if (tm instanceof android.telecom.TelecomManager) {
                String pkg = ((android.telecom.TelecomManager) tm).getDefaultDialerPackage();
                if ("com.callguard.dialer".equals(pkg)) return 120;
            }
        } catch (Throwable ignored) {
        }
        return BELOW_BANNER_DP;
    }

    private static void replace(View v, WindowManager.LayoutParams lp) {
        dismissNow();
        wm.addView(v, lp);
        current = v;
        currentLp = lp;
    }

    private static int screenW(Context app) {
        return app.getResources().getDisplayMetrics().widthPixels;
    }

    /** 状态栏高度，用来把横条压在系统横幅下面。 */
    private static int topInsetPx(Context ctx) {
        try {
            WindowManager w = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) return 0;
            android.view.WindowMetrics metrics = w.getCurrentWindowMetrics();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars());
            return insets.top;
        } catch (Throwable t) {
            return (int) (24 * ctx.getResources().getDisplayMetrics().density);
        }
    }

    /** 圆点颜色：语义色固定，普通状态用 0 表示"用主题 accent"。 */
    private static int toneColor(String tone) {
        if (tone == null) return 0;
        switch (tone) {
            case "bad":
                return COLOR_BAD;
            case "warn":
                return COLOR_WARN;
            case "good":
                return COLOR_GOOD;
            default:
                return 0;
        }
    }

    /**
     * 主题配色。和 MainActivity 一样从 {@code Theme.DeviceDefault.DayNight} 取：
     * Android 12+ 下这个主题本身就是 Material You 动态色。
     *
     * <p>表面色不自己混，直接用系统的 neutral 调色板：
     * 浅色取 `neutral1_100` 和 `neutral1_50` 的中间值（≈ #E8E7EF），
     * 深色取 `neutral1_900` 掺 15% 的 `neutral1_500`（≈ #282A2F）。
     * 这两个值就是系统来电框/设置卡片的表面色，比自己混出来的纯白或偏色准确。
     */
    private static int[] palette(Context app, boolean dark) {
        int accent, onSurface, onSurfaceVariant, bg;
        try {
            Context cw = new android.view.ContextThemeWrapper(app, R.style.AppTheme);
            android.content.res.TypedArray ta = cw.getTheme().obtainStyledAttributes(new int[]{
                    android.R.attr.colorAccent,
                    android.R.attr.textColorPrimary,
                    android.R.attr.textColorSecondary});
            accent = ta.getColor(0, 0xFF1565C0);
            onSurface = ta.getColor(1, dark ? 0xFFE6E1E5 : 0xFF1B1B1F);
            onSurfaceVariant = ta.getColor(2, dark ? 0xFFC4C7C5 : 0xFF44474E);
            ta.recycle();
            android.content.res.TypedArray tb = cw.getTheme().obtainStyledAttributes(
                    new int[]{android.R.attr.colorBackground});
            bg = tb.getColor(0, dark ? 0xFF141218 : 0xFFFEFBFF);
            tb.recycle();
        } catch (Throwable t) {
            accent = 0xFF1565C0;
            onSurface = dark ? 0xFFE6E1E5 : 0xFF1B1B1F;
            onSurfaceVariant = dark ? 0xFFC4C7C5 : 0xFF44474E;
            bg = dark ? 0xFF141218 : 0xFFFEFBFF;
        }
        // 表面色直接用系统 neutral 调色板，和来电框/设置卡片同一套：
        // 浅色 ≈ #E8E7EF（n1_100 与 n1_50 之间），深色 ≈ #282A2F（n1_900 掺一点 n1_500）。
        int surface = dark
                ? blend(0xFF76777D, 0xFF1A1B20, 0.15f)
                : blend(0xFFE2E2E9, 0xFFF1F0F7, 0.55f);
        int outline = dark ? 0xFF76777D : 0xFFC6C6CD;
        try {
            int n50 = app.getColor(android.R.color.system_neutral1_50);
            int n100 = app.getColor(android.R.color.system_neutral1_100);
            int n200 = app.getColor(android.R.color.system_neutral1_200);
            int n500 = app.getColor(android.R.color.system_neutral1_500);
            int n900 = app.getColor(android.R.color.system_neutral1_900);
            surface = dark ? blend(n500, n900, 0.15f) : blend(n100, n50, 0.55f);
            outline = dark ? n500 : n200;
        } catch (Throwable ignored) {
        }
        return new int[]{surface, onSurface, onSurfaceVariant, accent, outline};
    }

    private static int blend(int fg, int bg, float a) {
        int r = (int) (Color.red(fg) * a + Color.red(bg) * (1 - a));
        int g = (int) (Color.green(fg) * a + Color.green(bg) * (1 - a));
        int b = (int) (Color.blue(fg) * a + Color.blue(bg) * (1 - a));
        return Color.rgb(r, g, b);
    }

    private static final Runnable autoDismiss = Overlay::dismiss;

    private static void dismissNow() {
        try {
            if (current != null && wm != null) wm.removeViewImmediate(current);
        } catch (Throwable ignored) {
        }
        current = null;
        currentLp = null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
