package com.callguard.app;

import android.content.Intent;
import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.util.Log;

/**
 * 来电筛选：拿到主叫号码 -> 查号段 -> 按规则决定放行/拒接/静音。
 * 一旦出现任何异常都放行（fail-open），不会因为判断失败而漏接重要电话。
 *
 * 网络：判定路径里没有任何网络调用；放行之后才可能触发
 * {@link LookupReceiver}（自动查）或弹一条带「查询」按钮的通知（问一下）。
 */
public class ScreenService extends CallScreeningService {

    private static final String TAG = "CallGuard";

    @Override
    public void onCreate() {
        super.onCreate();
        Db.preload(this);
    }

    @Override
    public void onScreenCall(Call.Details details) {
        try {
            if (details.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
                allow(details);
                return;
            }
            Rules rules = new Rules(this);
            if (!rules.enabled()) {
                allow(details);
                return;
            }
            Uri handle = details.getHandle();
            String number = handle == null ? null : handle.getSchemeSpecificPart();

            // 紧急豁免：最近打过 110/119/120 之类，全部放行——回电不能漏接。
            // 只影响"拦不拦"，后面的 afterAllow 照常跑，归属地和标签继续显示。
            if (Emergency.isActive(this)) {
                allow(details);
                HistoryLog.add(this, number, false, getString(R.string.reason_emergency));
                afterAllow(rules, number);
                Log.i(TAG, "emergency exemption, allow " + number);
                return;
            }

            Rules.Verdict v = rules.evaluate(this, number);
            String why = v.describe(this);
            // 直接写：文件只有几百行，几毫秒的事；用线程反而可能在服务被销毁时丢掉记录
            HistoryLog.add(this, number, v.block, why);

            if (!v.block) {
                allow(details);
                afterAllow(rules, number);
                return;
            }

            BlockLog.add(this, number, v.describe(this));
            Log.i(TAG, "block " + number + " -> " + v.describe(this));
            // 没有本项目拨号器时，系统界面里看不到这次拦截（call_log 的 type=6 被藏起来）
            // → 用通知留个痕迹。连续拦截只会覆盖最后一条，不会刷屏。
            if (rules.notifyOnCall()) {
                Notifier.showBlocked(this, number == null ? "" : number, why);
            }
            int action = v.action == Rules.ACTION_DEFAULT ? rules.action() : v.action;
            boolean reject = action == Rules.ACTION_REJECT;
            respondToCall(details, new CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(reject)
                    .setSilenceCall(!reject)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build());
        } catch (Throwable t) {
            Log.e(TAG, "screen failed, allow call", t);
            allow(details);
        }
    }

    /**
     * 放行之后才处理，绝不拖慢响铃。
     *
     * <p>顺序：本地缓存 / 内置号码表 → 命中就弹标记（不联网）；
     * 没命中且允许联网时，按 queryMode 决定「自动查」「弹通知问」还是「放一个查询按钮」。
     * 查询本身走 {@link LookupReceiver}，是放行之后才发生的，不影响接听。
     */
    private void afterAllow(Rules rules, String number) {
        try {
            String digits = Rules.normalize(number);
            if (digits == null || digits.length() < 5) return;
            String region = regionOf(digits);

            // 显示：通知 或 悬浮窗。查询：要显示，或者拨号器需要数据（对外接口开着）。
            // 两者分开——用户可能两个都不开，但拨号器仍然要读缓存。
            boolean useOverlay = Overlay.shouldUse(this, rules);
            boolean wantDisplay = rules.notifyOnCall() || useOverlay;
            boolean wantData = wantDisplay || rules.labelProvider();

            String label = Online.cachedLabel(this, digits);
            if (label == null) {
                // 查过但"各平台均无标记"时 short 是空的（那是写通讯录用的短标签），
                // 这时候要看结构化摘要，否则会把"已查过、干净"显示成"未缓存"。
                java.util.List<String> lines = Online.screenLines(this, digits);
                if (!lines.isEmpty()) label = lines.get(0);
            }
            String known = (label != null) ? label : Directory.label(this, digits);

            int mode = rules.queryMode();
            boolean wouldCost = rules.onlinePaid() && !rules.onlineKey().trim().isEmpty();
            Online.State state = Online.cachedState(this, digits);
            boolean lastFailed = known == null && state.failed;

            // 没有拨号器时，"要不要花钱再查一次"只能落在通知/悬浮窗上：
            // 有 Key、付费开着、又不是自动模式 → 给「用付费接口重查」。
            String paidAction = null;
            android.app.PendingIntent paidIntent = null;
            Runnable paidRunnable = null;
            if (LabelProvider.canLookup(rules, state)) {
                paidAction = getString(R.string.notify_paid_recheck);
                paidIntent = Notifier.forceLookupAction(this, digits, region);
                paidRunnable = () -> fireLookup(digits, region, false);
            }

            boolean manualCard = wantDisplay && !rules.labelProvider()
                    && rules.onlineActive() && mode == Rules.QUERY_MANUAL && known == null;

            if (wantDisplay && known != null) {
                if (useOverlay) {
                    String tone = LabelProvider.toneOf(
                            java.util.Collections.singletonList(known), region);
                    // 不重复号码：系统横幅上已经有了，这里只给归属地
                    Overlay.showResult(this, known, region, paidRunnable, tone);
                } else {
                    String text = (region == null || region.isEmpty())
                            ? digits : digits + " · " + region;
                    Notifier.showText(this, digits, known, text, paidAction, paidIntent);
                }
                Log.i(TAG, "notify " + digits + " -> " + known);
            } else if (manualCard) {
                String body = getString(lastFailed
                        ? R.string.notify_manual_failed_text
                        : R.string.notify_manual_text);
                if (useOverlay) {
                    Overlay.ask(this, withRegion(digits, region), body,
                            getString(R.string.notify_ask_action),
                            () -> fireLookup(digits, region, false), 60_000L);
                } else {
                    Notifier.showManual(this, digits, region, body);
                }
                Log.i(TAG, "manual lookup card " + digits + (lastFailed ? " (last failed)" : ""));
            }

            // 有标签就不再联网（缓存 30 天；失败记录不会被 cachedLabel 命中）
            if (known != null) return;

            // 既不用显示、也没人查询：没必要联网
            if (!wantData || !rules.onlineActive()) return;

            // 已经给用户按钮了，等他点——不自动查
            if (manualCard) return;

            // 有专用拨号器（label_provider 开着）：免费查询直接做，不看 queryMode。
            // queryMode 是"要不要给用户看"的策略，不该挡住拨号器拿数据。
            // 用户没开"来电时显示"（quiet）时，这条查询也不该弹任何通知。
            if (rules.labelProvider() && (!wouldCost || mode == Rules.QUERY_AUTO)) {
                fireLookup(digits, region, !wantDisplay);
                Log.i(TAG, "lookup for dialer " + digits);
                return;
            }

            // 以下是没有拨号器、或者"要花钱又需要用户确认"的路径
            if (!wantDisplay) {
                Log.i(TAG, "skip lookup, no display " + digits);
                return;
            }

            if (mode == Rules.QUERY_MANUAL) return;

            if (wouldCost && mode == Rules.QUERY_CONFIRM) {
                if (useOverlay) {
                    Overlay.ask(this, withRegion(digits, region),
                            getString(R.string.notify_ask_text), getString(R.string.notify_ask_action),
                            () -> fireLookup(digits, region, false), 60_000L);
                } else {
                    Notifier.askLookup(this, digits, region);
                }
                Log.i(TAG, "ask lookup " + digits);
            } else {
                fireLookup(digits, region, false);
                Log.i(TAG, (wouldCost ? "auto lookup (paid) " : "auto lookup (free) ") + digits);
            }
        } catch (Throwable t) {
            Log.w(TAG, "afterAllow failed", t);
        }
    }

    private void fireLookup(String digits, String region, boolean quiet) {
        Intent i = new Intent(this, LookupReceiver.class)
                .setAction(LookupReceiver.ACTION)
                .putExtra(LookupReceiver.EXTRA_DIGITS, digits)
                .putExtra(LookupReceiver.EXTRA_REGION, region);
        if (quiet) i.putExtra(LookupReceiver.EXTRA_QUIET, true);
        sendBroadcast(i);
    }

    private static String withRegion(String digits, String region) {
        return (region == null || region.isEmpty()) ? digits : digits + " · " + region;
    }

    /** 归属地：号段库里的省份 + 运营商。 */
    private String regionOf(String digits) {
        if (digits.length() < 7) return "";
        Db db = Db.get(this);
        Db.Rec rec = db == null ? null : db.lookup(Integer.parseInt(digits.substring(0, 7)));
        return rec == null ? "" : Geo.provinceName(rec.province) + " " + rec.carrierName(this);
    }

    private void allow(Call.Details details) {
        try {
            respondToCall(details, new CallResponse.Builder().build());
        } catch (Throwable t) {
            Log.e(TAG, "respond failed", t);
        }
    }
}
