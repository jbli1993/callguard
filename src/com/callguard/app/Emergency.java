package com.callguard.app;

import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * 紧急呼叫豁免。
 *
 * <p>用户打过 110/119/120 之后，派出所或急救中心可能回电。这些号码对号段规则来说是
 * 彻底陌生的——如果被当成"未知号段"拦掉，后果比漏接骚扰电话严重得多。
 *
 * <p>所以：最近 {@link #WINDOW_MS}（2 小时）内有过**拨出**的紧急呼叫，就全部放行。
 * 豁免只影响"拦不拦"，不影响"显不显"——归属地和标签照常显示。
 *
 * <h3>为什么是 2 小时</h3>
 * Android 自己有一段自动放行：ECM（紧急回拨模式）期间 Telecom 会跳过整个过滤链。
 * 但那是 **CDMA 时代的机制**（`ro.cdma.ecmexittimer`，默认只 5 分钟），国内
 * GSM/LTE/NR 多数情况下根本不会进入 ECM，而且 5 分钟也盖不住"出警后回电"。
 * 2 小时能覆盖绝大多数回拨，同时把"报警后谁都能打进来"的窗口压到最小。
 *
 * <h3>为什么只在来电时查</h3>
 * 普通应用对"用户拨了电话"这件事**完全无感知**：
 * <ul>
 *   <li>{@code ACTION_NEW_OUTGOING_CALL} 自 Android 10 起废弃；</li>
 *   <li>{@code CallRedirectionService} 要求 {@code signature|privileged} 权限；</li>
 *   <li>{@code TelephonyCallback} 需要常驻组件，而 {@code CallScreeningService}
 *       只活一通电话——拨出时根本不运行（实测确认）。</li>
 * </ul>
 *
 * <p>所以唯一可靠的路径是：来电经过 {@code onScreenCall} 时，去通话记录里翻一下
 * "最近有没有拨出过紧急号码"。只查 OUTGOING，并且按时间过滤——
 * 40 条以内、5~20ms，远低于 5 秒预算。
 *
 * <p>判定用 {@link TelephonyManager#isEmergencyNumber}，因为紧急号码不是固定的
 * "110 这一类"——真机上还有 112 / 911 / 000 / 999 / 122 等，且不同设备列表不同。
 */
final class Emergency {

    private static final String TAG = "CallGuardEmergency";
    private static final String P = "rules";
    private static final String KEY_CLEARED_AT = "emergency_cleared_at";

    /** 默认豁免窗口：2 小时。 */
    static final long WINDOW_MS = 2L * 3600 * 1000;

    /** 时间过滤之外再限制条数，防止有人在这两小时里打了几百个电话。 */
    private static final int SCAN_LIMIT = 40;

    private Emergency() {}

    /**
     * 当前是否处于豁免状态。
     *
     * <p>每次都实查——不缓存，也就不会出现"缓存到旧值导致豁免不生效"。
     */
    static boolean isActive(Context ctx) {
        long last = lastEmergencyCallTime(ctx);
        if (last == 0) return false;
        // 用户手动清除过，且之后没有新的紧急呼叫
        if (last <= clearedAt(ctx)) return false;
        return System.currentTimeMillis() - last < WINDOW_MS;
    }

    /**
     * 最近一次**拨出**的紧急呼叫时间戳；没有（或没权限）返回 0。
     */
    static long lastEmergencyCallTime(Context ctx) {
        long since = System.currentTimeMillis() - WINDOW_MS;
        try (Cursor c = ctx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DATE},
                CallLog.Calls.TYPE + "=? AND " + CallLog.Calls.DATE + ">=?",
                new String[]{
                        String.valueOf(CallLog.Calls.OUTGOING_TYPE),
                        String.valueOf(since)
                },
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return 0;
            int left = SCAN_LIMIT;
            while (c.moveToNext() && left-- > 0) {
                String number = c.getString(0);
                long date = c.getLong(1);
                if (isEmergencyNumber(ctx, number)) return date;
            }
        } catch (Throwable t) {
            // 没有 READ_CALL_LOG 权限，或者 provider 不可用：当作没有紧急呼叫
            Log.w(TAG, "read call log failed: " + t.getClass().getSimpleName());
        }
        return 0;
    }

    /**
     * 是否是紧急号码。
     *
     * <p>优先用系统 API——它考虑当前 SIM 的国家和 modem 配置，覆盖
     * 110/119/120/122/112/911/000/999 等等。兜底只补最常见的几个。
     */
    static boolean isEmergencyNumber(Context ctx, String number) {
        if (number == null || number.isEmpty()) return false;
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && tm.isEmergencyNumber(number)) return true;
        } catch (Throwable ignored) {
        }
        String d = number.replaceAll("[^0-9]", "");
        return d.equals("110") || d.equals("119") || d.equals("120")
                || d.equals("122") || d.equals("112");
    }

    /** 手动解除：之后不再豁免，直到又打了新的紧急电话。 */
    static void clear(Context ctx) {
        ctx.getApplicationContext()
                .getSharedPreferences(P, Context.MODE_PRIVATE)
                .edit().putLong(KEY_CLEARED_AT, System.currentTimeMillis()).apply();
        Log.i(TAG, "exemption cleared by user");
    }

    /** 剩余豁免时间（毫秒），给界面显示；未生效返回 0。 */
    static long remainingMillis(Context ctx) {
        long last = lastEmergencyCallTime(ctx);
        if (last == 0) return 0;
        if (last <= clearedAt(ctx)) return 0;
        long left = WINDOW_MS - (System.currentTimeMillis() - last);
        return Math.max(0, left);
    }

    private static long clearedAt(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(P, Context.MODE_PRIVATE)
                .getLong(KEY_CLEARED_AT, 0);
    }
}
