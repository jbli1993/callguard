package com.callguard.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 可选的在线标记查询。
 *
 * - 填了 Key → Tmini 聚合接口（9 家，含快递外卖标签，300 积分/次）
 * - 没填 Key → kit9 免费接口（搜狗/百度/360 三家，无 Key、无费用，稳定性不保证）
 *
 * 设计约束：只在用户手动点「在线查标记」时调用；拦截流程永远不联网；
 * 结果本地缓存 30 天，命中缓存不产生费用；任何失败都不影响离线拦截。
 */
final class Online {

    private static final String TMini = "https://api.tmini.net/apis/checkmark";
    private static final String KIT9 = "https://apis.kit9.cn/api/phone_harass/api.php?tel=";

    private static final String CACHE = "online_cache";
    private static final long TTL_MS = 30L * 24 * 3600 * 1000;

    static final String SOURCE_KIT9 = "kit9";
    static final String SOURCE_TMINI = "tmini";

    static final class Result {
        final boolean ok;
        final String text;
        final boolean fromCache;
        final String error;
        final String label;
        /** 结果来源：{@link #SOURCE_KIT9} / {@link #SOURCE_TMINI}，未知时为 null。 */
        String source;
        /** 查询发生的时间（毫秒），0 表示未知。 */
        long queriedAt;
        /**
         * 通话界面用的结构化摘要（1~3 行）：认证企业名、被哪些平台标记、各多少次。
         *
         * <p>{@link #label} 是压成一行、用来写通讯录名字的短标签；这里保留细节，
         * 因为拨号器要把"哪几家、标了什么、多少次"画在通话界面上。
         */
        final List<String> lines;

        Result(boolean ok, String text, boolean fromCache, String error) {
            this(ok, text, fromCache, error, null, null);
        }

        Result(boolean ok, String text, boolean fromCache, String error, String label) {
            this(ok, text, fromCache, error, label, null);
        }

        Result(boolean ok, String text, boolean fromCache, String error, String label, List<String> lines) {
            this.ok = ok;
            this.text = text;
            this.fromCache = fromCache;
            this.error = error;
            this.label = label;
            this.lines = lines;
        }
    }

    /**
     * 缓存里的一条记录，给 {@link LabelProvider} 用。
     *
     * <p>{@code failed} 是「上次查询失败」的标记。它**没有过期时间**：下一次来电
     * {@link #query} 会跳过失败记录重新查，所以它只影响当前这一通电话的显示。
     */
    static final class State {
        final List<String> lines;
        final String source;
        final long queriedAt;
        final boolean failed;

        State(List<String> lines, String source, long queriedAt, boolean failed) {
            this.lines = lines;
            this.source = source;
            this.queriedAt = queriedAt;
            this.failed = failed;
        }

        static final State EMPTY =
                new State(java.util.Collections.emptyList(), null, 0L, false);
    }

    private Online() {}


    /** 来电时用：只读缓存，返回一行短标签，没有就返回 null。 */
    static String cachedLabel(Context ctx, String digits) {
        try {
            SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
            String raw = cache.getString(digits, null);
            if (raw == null) return null;
            JSONObject o = new JSONObject(raw);
            if (System.currentTimeMillis() - o.optLong("ts") >= TTL_MS) return null;
            String label = o.optString("short", "");
            // 老缓存里可能还存着旧的长措辞（TTL 30 天），读的时候换掉
            if ("No platform has marked this number".equals(label) || "各平台均无标记".equals(label)) {
                label = ctx.getString(R.string.online_clean);
            }
            return label.isEmpty() ? null : label;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通话界面用的在线摘要（只读缓存，绝不联网）。
     *
     * <p>与 {@link #cachedLabel} 的区别：那个要压成一行去写通讯录名字，只能留第一个标记；
     * 这里保留"被哪几家平台标记、标记成什么、各多少次、哪家企业认证过"。
     *
     * <p>新缓存直接读结构化的 {@code lines}；老缓存只有 {@code text}/{@code short}，
     * 退回那条压扁的短标签（信息少但不会没有）。
     */
    static List<String> screenLines(Context ctx, String digits) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
            String raw = cache.getString(digits, null);
            if (raw == null) return out;
            JSONObject o = new JSONObject(raw);
            if (System.currentTimeMillis() - o.optLong("ts") >= TTL_MS) return out;
            out = migrateLines(ctx, linesOf(o));
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 老缓存里可能存着旧的长措辞（TTL 30 天）。
     *
     * <p>"各平台均无标记" 改成 "未标记" 之后，缓存里的旧文案不该继续显示，
     * 否则用户改了版本还以为没生效。
     */
    private static List<String> migrateLines(Context ctx, List<String> lines) {
        if (lines.isEmpty()) return lines;
        boolean changed = false;
        String clean = ctx.getString(R.string.online_clean);
        List<String> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            if ("No platform has marked this number".equals(s) || "各平台均无标记".equals(s)) {
                out.add(clean);
                changed = true;
            } else {
                out.add(s);
            }
        }
        return changed ? out : lines;
    }

    /** 通话界面要的完整状态：成功给标签行，失败给 failed 标记。只读缓存，不联网。 */
    static State cachedState(Context ctx, String digits) {
        try {
            SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
            String raw = cache.getString(digits, null);
            if (raw == null) return State.EMPTY;
            JSONObject o = new JSONObject(raw);
            long ts = o.optLong("ts");
            if (o.optBoolean("failed", false)) {
                // 失败记录不设 TTL，只等下一次来电重新查
                return new State(java.util.Collections.emptyList(),
                        o.optString("source", ""), ts, true);
            }
            if (System.currentTimeMillis() - ts >= TTL_MS) return State.EMPTY;
            return new State(migrateLines(ctx, linesOf(o)), o.optString("source", ""), ts, false);
        } catch (Exception e) {
            return State.EMPTY;
        }
    }

    private static List<String> linesOf(JSONObject o) {
        List<String> out = new ArrayList<>();
        JSONArray lines = o.optJSONArray("lines");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                String s = lines.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        if (out.isEmpty()) {
            String shortLabel = o.optString("short", "").trim();
            if (!shortLabel.isEmpty()) out.add(shortLabel);
        }
        return out;
    }

    /** 兼容旧调用：按「有 Key 用付费、否则免费」的老规则。 */
    static Result query(Context ctx, String key, String digits) {
        boolean hasKey = key != null && !key.trim().isEmpty();
        return query(ctx, key, !hasKey, hasKey, digits);
    }

    /**
     * @param allowFree 允许免费接口（kit9）
     * @param allowPaid 允许付费接口（Tmini，需要 Key）
     */
    static Result query(Context ctx, String key, boolean allowFree, boolean allowPaid, String digits) {
        return query(ctx, key, allowFree, allowPaid, digits, false);
    }

    /**
     * @param allowFree 允许免费接口（kit9）
     * @param allowPaid 允许付费接口（Tmini，需要 Key）
     * @param force     跳过缓存。拨号器上的「查询」按钮用它——用户点了就一定要重新查。
     */
    static Result query(Context ctx, String key, boolean allowFree, boolean allowPaid,
                        String digits, boolean force) {
        SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);

        if (!force) {
            String raw = cache.getString(digits, null);
            if (raw != null) {
                try {
                    JSONObject o = new JSONObject(raw);
                    // 失败记录不参与命中：它只是给界面看的提示，下一次来电必须重查。
                    // 不设 TTL——重复查询由"新来电"这个事件触发，比时间更准。
                    boolean failed = o.optBoolean("failed", false);
                    if (!failed && System.currentTimeMillis() - o.optLong("ts") < TTL_MS) {
                        Result hit = new Result(true, o.optString("text"), true, null,
                                o.optString("short", ""), linesOf(o));
                        hit.source = o.optString("source", "");
                        hit.queriedAt = o.optLong("ts");
                        return hit;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        boolean usePaid = allowPaid && key != null && !key.trim().isEmpty();
        String source;
        Result r;
        if (usePaid) {
            r = fetchTmini(ctx, key.trim(), digits);
            source = SOURCE_TMINI;
            // 付费失败（余额不足/超时）且允许免费时，退回免费结果，总比没有强
            if (!r.ok && allowFree) {
                Result f = fetchKit9(ctx, digits);
                if (f.ok) {
                    r = f;
                    source = SOURCE_KIT9;
                }
            }
        } else if (allowFree) {
            r = fetchKit9(ctx, digits);
            source = SOURCE_KIT9;
        } else {
            return new Result(false, null, false, ctx.getString(R.string.online_disabled));
        }

        long now = System.currentTimeMillis();
        try {
            JSONObject cached = new JSONObject();
            cached.put("ts", now);
            cached.put("source", source);
            if (!r.ok) {
                // 失败也写缓存：否则拨号器只看到"没有标签"，会和"查过、号码干净"混淆。
                // 失败原因只进日志，不进缓存、不上界面。
                cached.put("failed", true);
                cache.edit().putString(digits, cached.toString()).apply();
                notifyLabelsChanged(ctx);
                return r;
            }
            cached.put("text", r.text);
            cached.put("short", r.label == null ? "" : r.label);
            // 结构化摘要：给拨号器的通话界面用，保留"哪几家、标了什么、多少次"
            JSONArray lines = new JSONArray();
            if (r.lines != null) {
                for (String line : r.lines) lines.put(line);
            }
            cached.put("lines", lines);
            cache.edit().putString(digits, cached.toString()).apply();

            notifyLabelsChanged(ctx);
        } catch (Exception ignored) {
        }
        r.queriedAt = now;
        r.source = source;
        return r;
    }

    /**
     * 通知正在通话界面上的拨号器：这个号码的标签刚变了（查到 / 查失败），可以刷了。
     * 时序上拨号器 300ms 就起来了，而网络要 1~6 秒，所以它第一次读必然是空的。
     */
    private static void notifyLabelsChanged(Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(
                    android.net.Uri.parse(LabelProvider.URI_LABEL), null);
        } catch (Throwable t) {
            // provider 没注册 / 被禁用都不影响缓存本身
        }
    }

    /** 缓存里还没过期的条目，按时间倒序：{号码, 短标签, 全文, 时间戳}。 */
    static java.util.List<String[]> cacheEntries(Context ctx) {
        SharedPreferences cache = ctx.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        java.util.List<String[]> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, ?> e : cache.getAll().entrySet()) {
            try {
                JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                if (o.optBoolean("failed", false)) continue;
                long ts = o.optLong("ts");
                if (now - ts >= TTL_MS) continue;
                out.add(new String[]{e.getKey(), o.optString("short", ""),
                        o.optString("text", ""), String.valueOf(ts)});
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(parseLong(b[3]), parseLong(a[3])));
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- Tmini（付费，9 家） ----------------

    private static Result fetchTmini(Context ctx, String key, String digits) {
        HttpURLConnection conn = null;
        try {
            String url = TMini + "?key=" + URLEncoder.encode(key, "UTF-8")
                    + "&phone=" + URLEncoder.encode(digits, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            JSONObject o = new JSONObject(read(in));
            if (o.optInt("code", -1) != 200) {
                return new Result(false, null, false, o.optString("msg", "code=" + o.optInt("code", -1)));
            }
            return new Result(true, renderTmini(ctx, o), false, null, labelTmini(o), linesTmini(o));
        } catch (Exception e) {
            return new Result(false, null, false, e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- kit9（免费，3 家） ----------------

    private static Result fetchKit9(Context ctx, String digits) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(KIT9 + URLEncoder.encode(digits, "UTF-8")).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            JSONObject o = new JSONObject(read(in));
            if (o.optInt("code", -1) != 200) {
                return new Result(false, null, false, o.optString("msg", "code=" + o.optInt("code", -1)));
            }
            return new Result(true, renderKit9(ctx, o), false, null, labelKit9(o), linesKit9(ctx, o));
        } catch (Exception e) {
            return new Result(false, null, false, e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String renderKit9(Context ctx, JSONObject o) {
        StringBuilder sb = new StringBuilder();
        JSONObject info = o.optJSONObject("info");
        if (info != null) {
            String prov = info.optString("province", "");
            String isp = info.optString("isp", "");
            if (!prov.isEmpty() || !isp.isEmpty()) {
                sb.append(prov);
                if (!isp.isEmpty()) sb.append(' ').append(isp);
                sb.append('\n');
            }
        }
        sb.append(ctx.getString(R.string.kit9_source)).append('\n');

        List<String> marked = markedKit9(o);
        if (marked.isEmpty()) {
            sb.append(ctx.getString(R.string.online_clean));
        } else {
            sb.append('⚠').append(' ').append(ctx.getString(R.string.online_marked)).append('\n');
            for (String m : marked) sb.append("  ").append(m).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<String> markedKit9(JSONObject o) {
        List<String> marked = new ArrayList<>();
        JSONArray data = o.optJSONArray("data");
        if (data == null) return marked;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "?");
            String result = item.optString("result", "");
            if (result.isEmpty() || result.startsWith("正常")) continue;
            marked.add(name + " · " + result);
        }
        return marked;
    }

    private static String labelKit9(JSONObject o) {
        List<String> marked = markedKit9(o);
        if (marked.isEmpty()) return null;
        String first = marked.get(0);
        int dot = first.lastIndexOf(" · ");
        return dot > 0 ? first.substring(dot + 3) + "（" + first.substring(0, dot) + "）" : first;
    }

    /**
     * kit9 的通话界面摘要：一行。
     *
     * <p>丢平台名（"搜狗号码通 · 骚扰电话" → "骚扰电话"），但保留"被几家平台标记"。
     * 平台名占的宽度大、区分度低；"被 3 家标记"才是用户真正在意的信号。
     */
    private static List<String> linesKit9(Context ctx, JSONObject o) {
        List<String> out = new ArrayList<>();
        List<String> marked = markedKit9(o);
        if (marked.isEmpty()) {
            out.add(ctx.getString(R.string.online_clean));
            return out;
        }

        java.util.LinkedHashSet<String> results = new java.util.LinkedHashSet<>();
        for (String m : marked) {
            int dot = m.lastIndexOf(" · ");
            results.add(dot > 0 ? m.substring(dot + 3) : m);
        }

        StringBuilder sb = new StringBuilder("⚠ ");
        if (marked.size() > 1) sb.append("被 ").append(marked.size()).append(" 家标记 · ");
        sb.append(String.join("、", results));
        out.add(sb.toString());
        return out;
    }

    // ---------------- Tmini 渲染 ----------------

    private static String renderTmini(Context ctx, JSONObject o) {
        StringBuilder sb = new StringBuilder();
        String region = o.optString("region", "");
        if (!region.isEmpty()) sb.append(region).append('\n');

        List<String> marked = new ArrayList<>();
        List<String> verified = new ArrayList<>();
        collectTmini(o, verified, marked);

        if (verified.size() > 0) {
            sb.append('✅').append(' ').append(ctx.getString(R.string.online_verified)).append('\n');
            for (String v : verified) sb.append("  ").append(v).append('\n');
        }
        if (marked.size() > 0) {
            sb.append('⚠').append(' ').append(ctx.getString(R.string.online_marked)).append('\n');
            for (String m : marked) sb.append("  ").append(m).append('\n');
        }
        if (verified.isEmpty() && marked.isEmpty()) {
            sb.append(ctx.getString(R.string.online_clean));
        }
        return sb.toString().trim();
    }

    private static String labelTmini(JSONObject o) {
        List<String> verified = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        collectTmini(o, verified, marked);
        String pick = !verified.isEmpty() ? verified.get(0) : (!marked.isEmpty() ? marked.get(0) : null);
        if (pick == null) return null;
        int dot = pick.lastIndexOf(" · ");
        return dot > 0 ? pick.substring(dot + 3) : pick;
    }

    /**
     * Tmini（付费接口）的通话界面摘要：最多两行。
     *
     * <ul>
     *   <li>第一行：认证企业名。9 家平台常常认证同一家企业，去重后只显示一次。</li>
     *   <li>第二行：被几家平台标记 + 每类标记的累计次数。**不含平台名**——平台名占宽度、
     *       区分度低，而"被 3 家标记、骚扰电话 15 次"才是用户要的判断依据。</li>
     * </ul>
     *
     * <p>次数会跨平台累加：360 标「骚扰电话 ×12」+ 百度标「骚扰电话 ×3」→「骚扰电话 ×15」。
     */
    private static List<String> linesTmini(JSONObject o) {
        List<String> out = new ArrayList<>();
        List<String> verified = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        collectTmini(o, verified, marked);

        if (!verified.isEmpty()) {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (String v : verified) {
                int dot = v.lastIndexOf(" · ");
                names.add(dot > 0 ? v.substring(dot + 3) : v);
            }
            out.add("✅ " + String.join("、", names));
        }

        if (marked.isEmpty()) return out;

        // marked 的元素形如「平台 · 标签A ×3、标签B ×1」，这里拆开累加同名标签的次数
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String m : marked) {
            int dot = m.lastIndexOf(" · ");
            String tags = dot > 0 ? m.substring(dot + 3) : m;
            for (String tag : tags.split("、")) {
                String name = tag.trim();
                if (name.isEmpty()) continue;
                int count = 0;
                int times = name.lastIndexOf(" ×");
                if (times > 0) {
                    try {
                        count = Integer.parseInt(name.substring(times + 2).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    name = name.substring(0, times).trim();
                }
                if (name.isEmpty()) continue;
                Integer prev = counts.get(name);
                counts.put(name, prev == null ? count : prev + count);
            }
        }
        if (counts.isEmpty()) return out;

        StringBuilder sb = new StringBuilder("⚠ ");
        if (marked.size() > 1) sb.append("被 ").append(marked.size()).append(" 家标记 · ");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append('、');
            first = false;
            sb.append(e.getKey());
            if (e.getValue() > 0) sb.append(" ×").append(e.getValue());
        }
        out.add(sb.toString());
        return out;
    }

    private static void collectTmini(JSONObject o, List<String> verified, List<String> marked) {
        JSONArray results = o.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String platform = item.optString("platform", "?");
            String status = item.optString("status", "");
            if ("认证".equals(status)) {
                verified.add(platform + " · " + item.optString("name", ""));
            } else if ("被标记".equals(status)) {
                JSONArray tags = item.optJSONArray("tags");
                StringBuilder t = new StringBuilder();
                if (tags != null) {
                    for (int j = 0; j < tags.length(); j++) {
                        JSONObject tag = tags.optJSONObject(j);
                        if (tag == null) continue;
                        if (t.length() > 0) t.append("、");
                        t.append(tag.optString("name"));
                        int count = tag.optInt("count", 0);
                        if (count > 0) t.append(" ×").append(count);
                    }
                }
                marked.add(platform + " · " + t);
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
