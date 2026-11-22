package com.callguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 来电相关的通知。三种用途：
 * <ul>
 *   <li>{@link #show} 缓存命中的标记（不联网）</li>
 *   <li>{@link #askLookup} 未缓存时问一下要不要查（点了才联网，付费源不会静默扣费）</li>
 *   <li>{@link #showText} 查询结果 / 查询中</li>
 * </ul>
 * 没有通知权限时静默跳过（拦截逻辑不受影响）。
 */
final class Notifier {

    private static final String CHANNEL = "caller_label_v2";

    /**
     * 固定通知 id：每条来电标签**覆盖**上一条，而不是并排堆着。
     *
     * <p>用号码做 id 时，两三通电话之后系统会把它们自动归组成「来电拦截 2」，
     * 标签被折叠进分组里，响铃时反而看不见。一台手机同时只有一通来电，
     * 覆盖才是正确的语义。
     */
    private static final int NOTIFICATION_ID = 1001;
    private static final int ACTION_REQUEST = 1002;
    /** 被拦下的来电单独一个 id：它和"正在响的来电标签"不是一回事，别互相覆盖。 */
    private static final int BLOCKED_ID = 1003;

    private Notifier() {}

    static boolean canPost(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static void show(Context ctx, String digits, String label, String region) {
        if (!canPost(ctx) || label == null || label.isEmpty()) return;
        String text = (region == null || region.isEmpty()) ? digits : digits + " · " + region;
        showText(ctx, digits, label, text, null, null);
    }

    /**
     * 被拦下的来电。
     *
     * <p>不装本项目的拨号器时，**这是唯一能看见的痕迹**：通话记录里那条 `type=6`
     * 被系统拨号器藏起来了（见 README §3.9），我们也没有别的地方可画。
     *
     * <p>用固定 id，所以连续拦十个骚扰电话只会留下最后一条，不会刷屏。
     */
    static void showBlocked(Context ctx, String digits, String detail) {
        if (!canPost(ctx)) return;
        showText(ctx, digits, ctx.getString(R.string.notify_blocked_title, digits),
                detail, null, null, BLOCKED_ID);
    }

    /** 未缓存：弹一条带「查询」按钮的通知。queryMode=确认时用。 */
    static void askLookup(Context ctx, String digits, String region) {
        if (!canPost(ctx)) return;
        String title = (region == null || region.isEmpty()) ? digits : digits + " · " + region;
        showText(ctx, digits, title, ctx.getString(R.string.notify_ask_text),
                ctx.getString(R.string.notify_ask_action), lookupAction(ctx, digits, region));
    }

    /**
     * 未缓存 + 手动模式：弹一条带「查询」按钮的通知。
     *
     * <p>没有专用拨号器时，这条路是用户**唯一**能看到在线标记的入口——
     * 手动模式不自动联网，界面上就必须有个能点的东西。
     */
    static void showManual(Context ctx, String digits, String region, String body) {
        if (!canPost(ctx)) return;
        String title = (region == null || region.isEmpty()) ? digits : digits + " · " + region;
        showText(ctx, digits, title, body,
                ctx.getString(R.string.notify_ask_action), lookupAction(ctx, digits, region));
    }

    /** 「查询」按钮点击后触发的广播。 */
    private static PendingIntent lookupAction(Context ctx, String digits, String region) {
        Intent i = new Intent(ctx, LookupReceiver.class)
                .setAction(LookupReceiver.ACTION)
                .putExtra(LookupReceiver.EXTRA_DIGITS, digits)
                .putExtra(LookupReceiver.EXTRA_REGION, region);
        return PendingIntent.getBroadcast(ctx, ACTION_REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 「用付费接口重查」：跳过缓存、并且**要显示过程**（查询中 / 结果）。
     *
     * <p>这是没有专用拨号器时，用户唯一能"主动决定花钱"的入口——
     * 拨号器里的那个按钮走的是 {@code LabelProvider.lookup}，这里是通知上的 action。
     */
    static PendingIntent forceLookupAction(Context ctx, String digits, String region) {
        Intent i = new Intent(ctx, LookupReceiver.class)
                .setAction(LookupReceiver.ACTION)
                .putExtra(LookupReceiver.EXTRA_DIGITS, digits)
                .putExtra(LookupReceiver.EXTRA_REGION, region)
                .putExtra(LookupReceiver.EXTRA_FORCE, true)
                .putExtra(LookupReceiver.EXTRA_QUIET, false);
        return PendingIntent.getBroadcast(ctx, ACTION_REQUEST + 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void showText(Context ctx, String digits, String title, String text,
                         String actionLabel, PendingIntent action) {
        showText(ctx, digits, title, text, actionLabel, action, NOTIFICATION_ID);
    }

    private static void showText(Context ctx, String digits, String title, String text,
                                 String actionLabel, PendingIntent action, int id) {
        if (!canPost(ctx)) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel ch = new NotificationChannel(CHANNEL,
                ctx.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ctx.getString(R.string.notify_channel_desc));
        // 静音：来电本来就在响铃，拦截通知也不该再叫一声。
        // DEFAULT + 无声 = 只进通知栏/状态栏，不抢横幅（横幅本来也被系统来电占着）。
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Intent intent = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_cg_phone)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                // 5 分钟足够看完；再久只是噪音（通话记录和我们自己的日志里都还在）
                .setTimeoutAfter(300_000L);
        if (action != null && actionLabel != null) {
            b.addAction(new Notification.Action.Builder(null, actionLabel, action).build());
        }

        try {
            nm.notify(id, b.build());
        } catch (Throwable ignored) {
        }
    }
}
