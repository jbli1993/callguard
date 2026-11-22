package com.callguard.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 规则存储与判定。全部数据在 SharedPreferences，与本机号段库比对，不联网。 */
public final class Rules {

    public static final int ACTION_REJECT = 0;   // 直接拒接（对方听到忙音/无法接通）
    public static final int ACTION_SILENCE = 1;  // 静音不显示（对方听到正常回铃）
    public static final int ACTION_DEFAULT = -1; // 用全局动作

    private static final String P = "rules";

    private final SharedPreferences sp;

    public Rules(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    // ---- 基本开关 ----
    public boolean enabled() { return sp.getBoolean("enabled", true); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public int action() { return sp.getInt("action", ACTION_REJECT); }
    public void setAction(int v) { sp.edit().putInt("action", v).apply(); }

    public boolean blockVirtual() { return sp.getBoolean("block_virtual", false); }
    public void setBlockVirtual(boolean v) { sp.edit().putBoolean("block_virtual", v).apply(); }

    public boolean blockIntl() { return sp.getBoolean("block_intl", false); }
    public void setBlockIntl(boolean v) { sp.edit().putBoolean("block_intl", v).apply(); }

    public boolean blockUnknown() { return sp.getBoolean("block_unknown", false); }
    public void setBlockUnknown(boolean v) { sp.edit().putBoolean("block_unknown", v).apply(); }

    /**
     * 是否允许第三方拨号器通过 {@link LabelProvider} 读取号码标签。
     *
     * <p>默认开：这个接口只会返回由号码本身推导的信息（号段归属地 / 内置表 / 在线标记缓存），
     * 不碰通讯录和通话记录。关掉之后 provider 一律返回空，拨号器会退回系统默认显示。
     */
    public boolean labelProvider() { return sp.getBoolean("label_provider", true); }
    public void setLabelProvider(boolean v) { sp.edit().putBoolean("label_provider", v).apply(); }

    /** 首屏「开始使用」卡片是否已经点过「知道了」。 */
    public boolean setupSeen() { return sp.getBoolean("setup_seen", false); }
    public void setSetupSeen(boolean v) { sp.edit().putBoolean("setup_seen", v).apply(); }

    /** 700 隐私号（快递/外卖/网约车）改为静音而不是放行。 */
    public boolean silenceRelay() { return sp.getBoolean("silence_relay", false); }
    public void setSilenceRelay(boolean v) { sp.edit().putBoolean("silence_relay", v).apply(); }

    /** 已选地区：黑名单模式下是"要拦的地区"，白名单模式下是"只放行的地区"。"省|市" 或 "省|*"。 */
    public Set<String> regions() {
        return new HashSet<>(sp.getStringSet("regions", new HashSet<>()));
    }

    public void setRegions(Set<String> v) {
        sp.edit().putStringSet("regions", new HashSet<>(v)).apply();
    }

    /** 写入的号码是否要从通讯录列表里隐藏（是否生效还要看 Contacts 的显示过滤）。 */
    public boolean contactsHidden() { return sp.getBoolean("contacts_hidden", true); }
    public void setContactsHidden(boolean v) { sp.edit().putBoolean("contacts_hidden", v).apply(); }

    /** 来电时用悬浮窗显示（需要 SYSTEM_ALERT_WINDOW，默认关）。 */
    public boolean overlayOn() { return sp.getBoolean("overlay_on", false); }
    public void setOverlayOn(boolean v) { sp.edit().putBoolean("overlay_on", v).apply(); }

    /**
     * 在线查到名字后，是否顺手写进通讯录。
     *
     * <p>这是"下次来电系统界面直接显示名字"的唯一途径：系统只认联系人，不认我们的数据库。
     * 只写有实际标签的号码（纯归属地的号码不写，否则通讯录会被"北京 移动"这种条目塞满）。
     */
    public boolean saveOnLookup() { return sp.getBoolean("save_on_lookup", true); }
    public void setSaveOnLookup(boolean v) { sp.edit().putBoolean("save_on_lookup", v).apply(); }

    /** 是否把号码写进系统通讯录（关掉就只靠悬浮窗显示名称）。 */
    public boolean contactsWrite() { return sp.getBoolean("contacts_write", true); }
    public void setContactsWrite(boolean v) { sp.edit().putBoolean("contacts_write", v).apply(); }

    // ---- 可选在线查询（默认关，拦截流程永不使用） ----
    public boolean onlineEnabled() { return sp.getBoolean("online_enabled", false); }
    public void setOnlineEnabled(boolean v) { sp.edit().putBoolean("online_enabled", v).apply(); }

    /** 允许免费接口（kit9，3 家，无 Key）。 */
    public boolean onlineFree() { return sp.getBoolean("online_free", true); }
    public void setOnlineFree(boolean v) { sp.edit().putBoolean("online_free", v).apply(); }

    /** 允许付费接口（Tmini，9 家，需要 Key）。 */
    public boolean onlinePaid() { return sp.getBoolean("online_paid", false); }
    public void setOnlinePaid(boolean v) { sp.edit().putBoolean("online_paid", v).apply(); }

    // 查询时机
    public static final int QUERY_MANUAL = 0;   // 只有手动点按钮才查
    public static final int QUERY_AUTO = 1;     // 来电放行后自动查（会花积分）
    public static final int QUERY_CONFIRM = 2;  // 来电时弹通知，点了才查

    public int queryMode() { return sp.getInt("query_mode", QUERY_MANUAL); }
    public void setQueryMode(int v) { sp.edit().putInt("query_mode", v).apply(); }

    /** 放行后是否可以真的发起查询（总开关 + 至少一个可用数据源）。 */
    public boolean onlineActive() {
        if (!onlineEnabled()) return false;
        if (onlineFree()) return true;
        return onlinePaid() && !onlineKey().trim().isEmpty();
    }

    public String onlineKey() { return sp.getString("online_key", ""); }
    public void setOnlineKey(String v) { sp.edit().putString("online_key", v == null ? "" : v.trim()).apply(); }

    public int onlineUsed() { return sp.getInt("online_used", 0); }
    public void bumpOnlineUsed() { sp.edit().putInt("online_used", onlineUsed() + 1).apply(); }

    /** 来电时用缓存里的标签弹通知（不联网）。 */
    public boolean notifyOnCall() { return sp.getBoolean("notify_on_call", false); }
    public void setNotifyOnCall(boolean v) { sp.edit().putBoolean("notify_on_call", v).apply(); }

    // ---- 判定 ----
    public static final class Verdict {
        public final boolean block;
        public final String reason;
        public final Db.Rec rec;
        /** ACTION_DEFAULT 表示沿用全局"拦截动作"。 */
        public final int action;

        Verdict(boolean block, String reason, Db.Rec rec) {
            this(block, reason, rec, ACTION_DEFAULT);
        }

        Verdict(boolean block, String reason, Db.Rec rec, int action) {
            this.block = block;
            this.reason = reason;
            this.rec = rec;
            this.action = action;
        }

        public String describe(Context ctx) {
            String region = rec == null ? ctx.getString(R.string.reason_unknown_blocked) : rec.label(ctx);
            return region + " · " + reason;
        }
    }

    /** 规范化：去掉国家码，返回纯数字；其他国际号码返回 null（由调用方单独判断）。 */
    static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[^0-9+]", "");
        if (s.startsWith("+86")) s = s.substring(3);
        else if (s.startsWith("0086")) s = s.substring(4);
        else if (s.startsWith("86") && s.length() == 13) s = s.substring(2);
        else if (s.startsWith("+")) return null;
        else if (s.startsWith("00")) return null;
        return s;
    }

    static boolean isIntl(String raw) {
        if (raw == null) return false;
        String s = raw.replaceAll("[^0-9+]", "");
        return s.startsWith("+") && !s.startsWith("+86");
    }

    /**
     * 系统白名单：用户自己的通讯录里有没有这个号码。
     *
     * <p>要求 READ_CONTACTS；没授权时返回 false（静默降级）。
     * CallGuard 自己写入的企业条目不算——{@link ContactsLookup} 会排除那个账户，
     * 否则"拦掉 95338"会因为是我们自己写的联系人而被反向绕过。
     */
    static boolean isKnownContact(Context ctx, String digits) {
        return isKnownContact(ctx, digits, null);
    }

    /**
     * @param raw 原始号码（可能带 `+`）。国际号码在通讯录里是 `+1415…`，
     *            而 {@code normalize()} 会去掉 `+`，所以两种写法都要试一次，
     *            否则"通讯录里的海外亲友"会漏掉豁免。
     */
    static boolean isKnownContact(Context ctx, String digits, String raw) {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (digits != null && digits.length() >= 5 && ContactsLookup.isKnown(ctx, digits)) {
            return true;
        }
        // 国际号码：normalize() 会返回 null（`+1…` 不是国内号段），这时只能用原始号码查。
        // 通讯录里存的是 `+1415…`，和来电号码一致，PhoneLookup 能匹配。
        return raw != null && (digits == null || !raw.equals(digits))
                && ContactsLookup.isKnown(ctx, raw);
    }

    public Verdict evaluate(Context ctx, String raw) {
        if (!enabled()) return new Verdict(false, ctx.getString(R.string.reason_disabled), null);

        String digits = normalize(raw);

        // 系统白名单（用户自己的通讯录）优先于**所有**规则：认识的人永远不拦。
        // 它豁免国际来电、未知号段、地区命中、700 静音——只有这一层能翻它们。
        // 注意系统黑名单在 Telecom 更上游：通讯录里的人如果被系统屏蔽，我们根本收不到这通电话。
        if (isKnownContact(ctx, digits, raw)) {
            return new Verdict(false, ctx.getString(R.string.reason_contacts), null);
        }

        if (isIntl(raw)) {
            if (blockIntl()) return new Verdict(true, ctx.getString(R.string.reason_intl_blocked), null);
            return new Verdict(false, ctx.getString(R.string.reason_intl_allowed), null);
        }
        if (digits == null || digits.isEmpty()) {
            if (blockUnknown()) return new Verdict(true, ctx.getString(R.string.reason_hidden), null);
            return new Verdict(false, ctx.getString(R.string.reason_empty), null);
        }

        // 700 号段：工信部「号码保护服务」专用码号（15 位），快递/外卖/网约车的中间号。
        // 必须在"未知号段"判定之前处理，否则开了「拦未知号段」会把快递一起拦掉。
        if (digits.length() == 15 && digits.startsWith("700")) {
            boolean silence = silenceRelay();
            return new Verdict(silence, ctx.getString(R.string.reason_relay), null,
                    silence ? ACTION_SILENCE : ACTION_DEFAULT);
        }

        // 短号/特服号（110/120/95588/10086 等）：号段库无法判断，默认放行
        if (digits.length() < 7) {
            return new Verdict(false, ctx.getString(R.string.reason_short), null);
        }

        // 通讯录白名单：**始终生效**（见 evaluate() 最前面），这里不再判断开关
        Db db = Db.get(ctx);
        Db.Rec rec = db == null ? null : db.lookup(Integer.parseInt(digits.substring(0, 7)));

        if (rec == null) {
            if (blockUnknown()) return new Verdict(true, ctx.getString(R.string.reason_unknown_blocked), null);
            return new Verdict(false, ctx.getString(R.string.reason_unknown_allowed), null);
        }

        Set<String> regions = regions();
        boolean regionHit = regions.contains(rec.province + "|*")
                || regions.contains(rec.province + "|" + rec.city);

        if (regionHit) {
            String place = Geo.provinceName(rec.province);
            if (!rec.province.equals(rec.city)) place = place + " " + Geo.cityName(rec.city);
            return new Verdict(true, ctx.getString(R.string.reason_region_hit, place), rec);
        }

        if (blockVirtual() && rec.isVirtual()) {
            return new Verdict(true, ctx.getString(R.string.reason_virtual), rec);
        }

        return new Verdict(false, ctx.getString(R.string.reason_allowed), rec);
    }

    public String summaryLine(Context ctx) {
        String a = action() == ACTION_SILENCE ? ctx.getString(R.string.action_silence_title)
                : ctx.getString(R.string.action_reject_title);
        return String.format(Locale.US, "%s / %s", a, regions().size());
    }
}
