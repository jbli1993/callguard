package com.callguard.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读的「来电身份」供给端，供第三方拨号器（例如 fork 的 OpenDialer）在通话界面上
 * 显示号段归属地 / 在线标记。
 *
 * <h3>协议</h3>
 * 输出是**纯文本**，不是 JSON。这样两边的实现可以各自演进，看日志就能读懂。
 *
 * <pre>
 * v1
 * region=北京 移动
 * region_short=北京
 * line1=⚠ 被 3 家标记 · 骚扰电话 ×15
 * tone=warn
 * source=kit9
 * queried_at=1794746345110
 * lookup=1
 * </pre>
 *
 * 规则：
 * <ul>
 *   <li>第一行必须是协议版本 {@code v1}；不认识的版本，调用方应**整体忽略**本次回复。</li>
 *   <li>其余行是 {@code key=value}，按第一个 {@code =} 分割，允许任意顺序。</li>
 *   <li>未知的 key 一律忽略（向后兼容），因此可以随时新增字段。</li>
 *   <li>{@code line1..line9} 是要显示的文本行，从 1 开始连续，遇到空/缺省即停止。</li>
 *   <li>{@code tone} 可选：{@code neutral}（默认）/ {@code good} / {@code warn} / {@code bad}。</li>
 *   <li>{@code source} 可选：{@code kit9}（免费）/ {@code tmini}（付费）/ {@code directory}（内置表）。</li>
 *   <li>{@code queried_at} 可选：查询时间（毫秒时间戳），用来显示"几天前查的"。</li>
 *   <li>{@code failed=1} 表示上次查询失败（此时通常没有 {@code lineN}），调用方应显示失败提示。</li>
 *   <li>{@code lookup=1} 表示用户点了拨号器上的「查询」会有意义（有 Key、且不会自动查）。</li>
 * </ul>
 *
 * <h3>调用方式</h3>
 * <pre>
 * Bundle b = resolver.call(Uri.parse("content://com.callguard.app.provider/label"),
 *                          "label", "13800138150", null);
 * String text = b == null ? null : b.getString("text");
 * </pre>
 *
 * 调试时可以不用写代码：
 * <pre>
 * adb shell content call --uri content://com.callguard.app.provider/label --method label --arg 13800138150
 * </pre>
 *
 * <h3>范围</h3>
 * 只会返回**由号码本身推导出来**的信息（号段归属地、内置号码表、在线标记缓存），
 * 不读通讯录、不读通话记录，因此对调用方泄露的东西仅限于它自己传进来的号码。
 */
public class LabelProvider extends ContentProvider {

    private static final String TAG = "CallGuardLabel";

    public static final String AUTHORITY = "com.callguard.app.provider";
    public static final String URI_LABEL = "content://" + AUTHORITY + "/label";

    /** {@code ContentResolver.call} 的方法名：取标签。 */
    public static final String METHOD_LABEL = "label";
    /** {@code ContentResolver.call} 的方法名：请求立刻重查（拨号器的「查询」按钮）。 */
    public static final String METHOD_LOOKUP = "lookup";
    /** 返回 Bundle 里承载文本的 key。 */
    public static final String KEY_TEXT = "text";
    /** {@link #METHOD_LOOKUP} 的返回：是否受理。 */
    public static final String KEY_ACCEPTED = "accepted";

    /** 唯一被允许触发重查的应用。任何第三方都能调用 provider，不校验会刷光用户额度。 */
    private static final String TRUSTED_PACKAGE = "com.callguard.dialer";

    private static final String PROTOCOL = "v1";

    /** 标签来自内置号码表，而不是在线接口。 */
    static final String SOURCE_DIRECTORY = "directory";

    /** 通话界面最多几行：归属地 1 行 + 在线摘要 2 行，再多会挤掉头像。 */
    private static final int MAX_LINES = 3;

    static final String TONE_NEUTRAL = "neutral";
    static final String TONE_GOOD = "good";
    static final String TONE_WARN = "warn";
    static final String TONE_BAD = "bad";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_LOOKUP.equals(method)) return lookup(arg);
        if (!METHOD_LABEL.equals(method)) return null;

        Bundle out = new Bundle();
        String text;
        try {
            text = render(getContext(), arg);
        } catch (Throwable t) {
            Log.w(TAG, "render failed", t);
            text = null;
        }
        if (text == null) return out;

        out.putString(KEY_TEXT, text);
        return out;
    }

    /**
     * 拨号器点「查询」时调用：让 CallGuard 立刻重查一次（跳过缓存）。
     *
     * <p>只接受 {@code com.callguard.dialer}。不校验的话，任何应用都能反复触发付费查询，
     * 把用户的额度刷光。这里是**异步受理**：立即返回，真正的查询在广播接收器里跑，
     * 查完写缓存 + {@code notifyChange}，拨号器的 ContentObserver 会自己刷新。
     */
    private Bundle lookup(String rawNumber) {
        Bundle out = new Bundle();
        out.putBoolean(KEY_ACCEPTED, false);

        Context ctx = getContext();
        if (ctx == null) return out;
        if (!isTrustedCaller(ctx)) {
            Log.w(TAG, "lookup rejected, uid=" + Binder.getCallingUid());
            return out;
        }

        Rules rules = new Rules(ctx);
        if (!rules.labelProvider() || !rules.onlineActive()) return out;

        String digits = Rules.normalize(rawNumber);
        if (digits == null || digits.length() < 5) return out;

        LookupReceiver.requestAsync(ctx, digits);
        out.putBoolean(KEY_ACCEPTED, true);
        Log.i(TAG, "lookup requested " + digits);
        return out;
    }

    private boolean isTrustedCaller(Context ctx) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) return false;
        for (String p : pkgs) {
            if (TRUSTED_PACKAGE.equals(p)) return true;
        }
        return false;
    }

    /**
     * 号码 -> 格式化文本。返回 null 表示"没有可说的"，调用方应保持原样。
     */
    static String render(Context ctx, String rawNumber) {
        if (ctx == null) return null;
        if (rawNumber == null) return null;

        Rules rules = new Rules(ctx);
        // 用户可能在设置里关掉对外服务
        if (!rules.labelProvider()) return null;

        String digits = Rules.normalize(rawNumber);
        if (digits == null || digits.length() < 5) return null;

        String region = regionOf(ctx, digits);
        String regionShort = regionShortOf(ctx, digits);

        // 在线查询的详细结果优先（付费接口能说清"被哪几家标记、各多少次"）；
        // 没有在线记录时才退回内置号码表。
        Online.State state = Online.cachedState(ctx, digits);
        List<String> lines = new ArrayList<>(state.lines);
        String source = state.source;
        boolean failed = state.failed;
        long queriedAt = state.queriedAt;
        if (lines.isEmpty()) {
            String known = Directory.label(ctx, digits);
            if (known != null && !known.isEmpty()) {
                lines.add(known);
                source = SOURCE_DIRECTORY;
                // 内置表是离线数据，不受在线查询失败影响：有它就不该显示"失败"
                failed = false;
                queriedAt = 0;
            }
        }

        boolean hasRegion = region != null && !region.isEmpty();
        // 什么都没有就不回，让调用方继续用系统默认显示；
        // 但"查询失败"本身也是信息，必须回。
        if (!hasRegion && lines.isEmpty() && !failed) {
            Log.i(TAG, "no info for " + digits);
            return null;
        }

        int index = 1;
        StringBuilder sb = new StringBuilder(200);
        sb.append(PROTOCOL).append('\n');
        if (hasRegion) {
            sb.append("region=").append(region).append('\n');
            if (regionShort != null && !regionShort.isEmpty()) {
                sb.append("region_short=").append(regionShort).append('\n');
            }
        }
        for (String line : lines) {
            if (index > MAX_LINES) break;
            sb.append("line").append(index++).append('=').append(line).append('\n');
        }
        sb.append("tone=").append(toneOf(lines, hasRegion ? region : null)).append('\n');
        if (source != null && !source.isEmpty()) {
            sb.append("source=").append(source).append('\n');
        }
        if (queriedAt > 0) {
            sb.append("queried_at=").append(queriedAt).append('\n');
        }
        if (failed) {
            sb.append("failed=1\n");
        } else if (canLookup(rules, state)) {
            sb.append("lookup=1\n");
        }

        // 被拦过的话，把**简短原因**带给拨号器：call_log 里只有 block_reason（谁拦的），
        // 说不出「按地区还是按号段」。
        String blockedReason = BlockLog.reasonOf(ctx, digits);
        if (blockedReason != null && !blockedReason.isEmpty()) {
            sb.append("blocked=1\n");
            sb.append("block_reason=").append(blockedReason).append('\n');
        }

        Log.i(TAG, "label " + digits + " -> " + (hasRegion ? region : "") + " | " + lines
                + (failed ? " | failed" : ""));
        return sb.toString();
    }

    /**
     * 界面上要不要显示「用付费接口重查」按钮。
     *
     * <p>免费查询本来就是自动的，所以按钮只为付费而存在：必须填了 Key、打开了付费接口、
     * 且查询时机不是「一律自动」（自动的话早就查过了，按钮没有意义）。
     * 已经拿到付费结果、或者上次查询失败时都不显示。
     */
    static boolean canLookup(Rules rules, Online.State state) {
        if (state.failed) return false;
        if (Online.SOURCE_TMINI.equals(state.source)) return false;
        if (rules.queryMode() == Rules.QUERY_AUTO) return false;
        if (!rules.onlinePaid()) return false;
        String key = rules.onlineKey();
        return key != null && !key.trim().isEmpty();
    }

    private static String regionOf(Context ctx, String digits) {
        if (digits.length() < 7) return "";
        try {
            Db db = Db.get(ctx);
            if (db == null) return "";
            Db.Rec rec = db.lookup(Integer.parseInt(digits.substring(0, 7)));
            return rec == null ? "" : rec.label(ctx);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 归属地短版本，给空间有限的地方用（比如通话记录列表的一行）。
     *
     * <p>"云南 曲靖 移动" → "曲靖"；直辖市用省名（"北京 移动" → "北京"）。
     */
    private static String regionShortOf(Context ctx, String digits) {
        if (digits.length() < 7) return "";
        try {
            Db db = Db.get(ctx);
            if (db == null) return "";
            Db.Rec rec = db.lookup(Integer.parseInt(digits.substring(0, 7)));
            if (rec == null) return "";
            if (rec.city != null && !rec.city.isEmpty() && !rec.city.equals(rec.province)) {
                return Geo.cityName(rec.city);
            }
            return Geo.provinceName(rec.province);
        } catch (Throwable t) {
            return "";
        }
    }

    static String toneOf(List<String> lines, String region) {
        String all = String.join(" ", lines);
        if (region != null) all = region + " " + all;
        if (all.trim().isEmpty()) return TONE_NEUTRAL;
        if (containsAny(all, "诈骗", "fraud", "scam")) return TONE_BAD;
        if (containsAny(all, "骚扰", "推销", "中介", "催收", "spam", "telemarket")) return TONE_WARN;
        // 有企业认证说明是可信来源
        if (all.contains("✅")) return TONE_GOOD;
        return TONE_NEUTRAL;
    }

    private static boolean containsAny(String haystack, String... needles) {
        String s = haystack.toLowerCase(java.util.Locale.ROOT);
        for (String n : needles) {
            if (s.contains(n.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // 纯为方便调试：content query --uri content://com.callguard.app.provider/label/13800138150
        String digits = uri == null ? null : uri.getLastPathSegment();
        if (digits == null || "label".equals(digits)) {
            return new MatrixCursor(new String[]{"text"});
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"text"});
        String text = render(getContext(), digits);
        if (text != null) cursor.addRow(new Object[]{text});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
