package com.callguard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 来电场景下的联网查询都从这里发起：
 * <ul>
 *   <li>用户点了通知上的「查询」（{@link Rules#QUERY_CONFIRM} 的付费询问，
 *       或 {@link Rules#QUERY_MANUAL} 的手动卡片）</li>
 *   <li>ScreenService 放行后直接广播过来（{@link Rules#QUERY_AUTO}，或拨号器要数据）</li>
 *   <li>拨号器通话界面的「用付费接口重查」按钮（{@code LabelProvider} 的 {@code lookup} 方法）</li>
 *   <li>主界面手动查询也会走同一条路径（保证结果和通知行为一致）</li>
 * </ul>
 *
 * 用 {@link #goAsync()} 换一个短生命周期的后台线程，绕开 Android 8+ 的后台服务启动限制；
 * 通知上的按钮属于用户主动操作，不会被限制。
 */
public class LookupReceiver extends BroadcastReceiver {

    static final String ACTION = "com.callguard.app.LOOKUP";
    static final String EXTRA_DIGITS = "digits";
    static final String EXTRA_REGION = "region";
    /** 跳过缓存重新查（拨号器的「查询」按钮）。 */
    static final String EXTRA_FORCE = "force";
    /** 不弹通知/悬浮窗（拨号器自己有界面，CallGuard 不该再打扰）。 */
    static final String EXTRA_QUIET = "quiet";

    private static final String TAG = "CallGuard";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String digits = intent.getStringExtra(EXTRA_DIGITS);
        if (digits == null || digits.length() < 5) return;
        final String region = intent.getStringExtra(EXTRA_REGION);
        final boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        final boolean quiet = intent.getBooleanExtra(EXTRA_QUIET, false);

        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                lookup(app, digits, region, force, quiet);
            } catch (Throwable t) {
                Log.w(TAG, "lookup crashed " + digits, t);
            } finally {
                try {
                    pr.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "callguard-lookup").start();
    }

    /** 拨号器通过 {@code LabelProvider} 发起的查询：强制重查、不弹任何界面。 */
    static void requestAsync(Context ctx, String digits) {
        fire(ctx, digits, null, true, true);
    }

    /** 触发一次查询（异步广播），force = 跳过缓存，quiet = 不弹通知/悬浮窗。 */
    static void fire(Context ctx, String digits, String region, boolean force, boolean quiet) {
        ctx.sendBroadcast(new Intent(ctx, LookupReceiver.class)
                .setAction(ACTION)
                .putExtra(EXTRA_DIGITS, digits)
                .putExtra(EXTRA_REGION, region)
                .putExtra(EXTRA_FORCE, force)
                .putExtra(EXTRA_QUIET, quiet));
    }

    static void lookup(Context ctx, String digits, String region) {
        lookup(ctx, digits, region, false, false);
    }

    static void lookup(Context ctx, String digits, String region, boolean force, boolean quiet) {
        Rules rules = new Rules(ctx);
        if (!rules.onlineActive()) return;
        final boolean useOverlay = !quiet && Overlay.shouldUse(ctx, rules);

        // 两种呈现方式都要先给个「查询中」，否则点了按钮像是没反应。
        // 拨号器发起的查询（quiet）由它自己的界面显示"查询中"，这里不重复。
        if (!quiet) {
            if (useOverlay) {
                Overlay.showBusy(ctx, ctx.getString(R.string.notify_querying), region);
            } else {
                Notifier.showText(ctx, digits, digits,
                        ctx.getString(R.string.notify_querying), null, null);
            }
        }

        Online.Result r = Online.query(ctx, rules.onlineKey(),
                rules.onlineFree(), rules.onlinePaid(), digits, force);

        if (!r.ok) {
            // 失败原因只进日志；界面（如果有）只说「免费查询失败」。
            // 失败已经写进缓存，拨号器会显示失败态；下一次来电自动重查。
            Log.w(TAG, "lookup failed " + digits + " -> " + r.error
                    + (force ? " (forced)" : ""));
            if (quiet) return;

            if (useOverlay) {
                Overlay.showResult(ctx, ctx.getString(R.string.notify_query_failed_short), region);
            } else {
                Notifier.showText(ctx, digits, digits,
                        ctx.getString(R.string.notify_query_failed_short), null, null);
            }
            return;
        }
        String title = (r.label == null || r.label.isEmpty()) ? digits : r.label;

        // 「下次来电系统界面直接显示」的唯一途径：把查到的标签写成联系人。
        // 但**只写公开确认的号码**——普通手机号哪怕被标成"骚扰电话"也不写：
        // 那条记录对用户没有价值，还会挤进通讯录、和来电界面的标签行重复。
        // 放到后台线程，用户看到的是即时结果，写库慢一点无所谓。
        if (rules.saveOnLookup() && r.label != null && !r.label.isEmpty()
                && AccountSetup.isPublicNumber(ctx, digits)) {
            final String nm = r.label;
            new Thread(() -> {
                boolean ok = AccountSetup.saveOne(ctx, digits, nm);
                if (ok) Log.i(TAG, "saved contact " + digits + " -> " + nm);
            }, "cg-save-contact").start();
        }

        // r.text 里已经带了归属地，这里不再重复拼号码
        if (!quiet) {
            // 没有拨号器时，付费重查的入口挂在结果通知 / 悬浮窗上：
            // 免费结果 + 有 Key + 非自动模式 → 给一个「用付费接口重查」按钮。
            String actionLabel = null;
            android.app.PendingIntent action = null;
            Runnable actionRunnable = null;
            Online.State st = Online.cachedState(ctx, digits);
            if (LabelProvider.canLookup(rules, st)) {
                actionLabel = ctx.getString(R.string.notify_paid_recheck);
                action = Notifier.forceLookupAction(ctx, digits, region);
                final String d = digits;
                final String rg = region;
                actionRunnable = () -> fire(ctx, d, rg, true, false);
            }
            if (useOverlay) {
                java.util.List<String> lines = (r.lines == null || r.lines.isEmpty())
                        ? java.util.Collections.singletonList(title) : r.lines;
                // 不重复号码：系统横幅上已经有了
                Overlay.showResult(ctx, title, region, actionRunnable,
                        LabelProvider.toneOf(lines, region));
            } else {
                Notifier.showText(ctx, digits, title, r.text, actionLabel, action);
            }
        }
        Log.i(TAG, "lookup " + digits + " -> " + title + (force ? " (forced)" : ""));
    }
}
