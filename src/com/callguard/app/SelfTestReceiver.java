package com.callguard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 自测入口（调试/验收用）：
 *   adb shell am broadcast -n com.callguard.app/.SelfTestReceiver \
 *       -a com.callguard.app.SELFTEST --es numbers "13800138000,17012345678"
 * 结果写入 logcat，tag = CallGuardSelfTest。不做任何网络或文件外发。
 */
public class SelfTestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!"com.callguard.app.SELFTEST".equals(intent.getAction())) return;
        // Defense in depth: even a debug build with an exported receiver only accepts
        // shell/root/self, never another app.
        int uid = android.os.Binder.getCallingUid();
        if (uid != 0 && uid != 2000 && uid != android.os.Process.myUid()) {
            Log.w("CallGuardSelfTest", "ignored broadcast from uid " + uid);
            return;
        }
        String raw = intent.getStringExtra("numbers");
        if (raw == null || raw.isEmpty()) raw = "13800138000,17012345678,10086";
        Rules rules = new Rules(ctx);
        String regions = intent.getStringExtra("regions");
        if (regions != null) {
            java.util.Set<String> set = new java.util.HashSet<>();
            for (String r : regions.split(",")) {
                if (!r.trim().isEmpty()) set.add(r.trim());
            }
            rules.setRegions(set);
            Log.i("CallGuardSelfTest", "regions set to " + set);
        }
        if (intent.hasExtra("action")) rules.setAction(intent.getIntExtra("action", 0));
        if (intent.hasExtra("block_virtual")) rules.setBlockVirtual(intent.getBooleanExtra("block_virtual", false));
        if (intent.hasExtra("block_unknown")) rules.setBlockUnknown(intent.getBooleanExtra("block_unknown", false));
        if (intent.hasExtra("block_intl")) rules.setBlockIntl(intent.getBooleanExtra("block_intl", false));
        if (intent.hasExtra("silence_relay")) rules.setSilenceRelay(intent.getBooleanExtra("silence_relay", false));
        if (intent.hasExtra("notify_on_call")) rules.setNotifyOnCall(intent.getBooleanExtra("notify_on_call", false));
        if (intent.hasExtra("online_enabled")) rules.setOnlineEnabled(intent.getBooleanExtra("online_enabled", false));
        if (intent.hasExtra("online_free")) rules.setOnlineFree(intent.getBooleanExtra("online_free", true));
        if (intent.hasExtra("online_paid")) rules.setOnlinePaid(intent.getBooleanExtra("online_paid", false));
        if (intent.hasExtra("query_mode")) rules.setQueryMode(intent.getIntExtra("query_mode", 0));
        if (intent.hasExtra("overlay_on")) rules.setOverlayOn(intent.getBooleanExtra("overlay_on", false));
        if (intent.hasExtra("online_key")) rules.setOnlineKey(intent.getStringExtra("online_key"));
        if (intent.hasExtra("label_provider")) {
            rules.setLabelProvider(intent.getBooleanExtra("label_provider", true));
            Log.i("CallGuardSelfTest", "label_provider=" + rules.labelProvider());
        }
        if (intent.hasExtra("seed_contacts")) {
            int n = AccountSetup.write(ctx);
            Log.i("CallGuardSelfTest", "contacts written: " + n + " (total " + AccountSetup.count(ctx) + ")");
        }
        if (intent.hasExtra("clear_contacts")) {
            int n = AccountSetup.clear(ctx);
            Log.i("CallGuardSelfTest", "contacts cleared: " + n);
        }
        if (intent.hasExtra("prune_nonpublic")) {
            int n = AccountSetup.pruneNonPublic(ctx);
            Log.i("CallGuardSelfTest", "pruned non-public contacts: " + n
                    + " (left " + AccountSetup.count(ctx) + ")");
        }
        // 调试用：直接往在线缓存里塞一条标签，用来验证"来电提示"（不联网、不花钱）
        String seed = intent.getStringExtra("seed_cache");
        if (seed != null && seed.contains("|")) {
            String[] parts = seed.split("\\|", 2);
            String d = parts[0].replaceAll("[^0-9]", "");
            android.content.SharedPreferences cache =
                    ctx.getSharedPreferences("online_cache", Context.MODE_PRIVATE);
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ts", System.currentTimeMillis());
                o.put("text", parts[1]);
                o.put("short", parts[1]);
                // 结构化摘要（模拟付费接口的结果），多行用 ";" 分隔
                String linesRaw = intent.getStringExtra("seed_lines");
                if (linesRaw != null && !linesRaw.isEmpty()) {
                    org.json.JSONArray lines = new org.json.JSONArray();
                    for (String line : linesRaw.split(";")) {
                        if (!line.trim().isEmpty()) lines.put(line.trim());
                    }
                    o.put("lines", lines);
                }
                String src = intent.getStringExtra("seed_source");
                if (src != null && !src.isEmpty()) o.put("source", src);
                if (intent.getBooleanExtra("seed_failed", false)) {
                    // 模拟「查询失败」缓存：没有标签行，只有 failed 标记
                    o.put("failed", true);
                    o.remove("lines");
                    o.put("short", "");
                }
                cache.edit().putString(d, o.toString()).apply();
                // 和真实查询完成时一样，通知正在通话界面上的拨号器刷新
                ctx.getContentResolver().notifyChange(
                        android.net.Uri.parse(LabelProvider.URI_LABEL), null);
                Log.i("CallGuardSelfTest", "seeded cache " + d + " -> " + parts[1]);
            } catch (Exception ignored) {
            }
        }
        Log.i("CallGuardSelfTest", "db=" + (Db.get(ctx) == null ? "null" : Db.get(ctx).version()));
        for (String n : raw.split(",")) {
            String num = n.trim();
            if (num.isEmpty()) continue;
            Rules.Verdict v = rules.evaluate(ctx, num);
            Log.i("CallGuardSelfTest", "ST|" + num + "|" + (v.block ? "BLOCK" : "ALLOW") + "|"
                    + v.reason + "|" + (v.rec == null ? "-" : v.rec.label(ctx)));
        }
    }
}
