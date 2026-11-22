package com.callguard.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机来电历史（含放行和拦截），最多 {@link #MAX_LINES} 条。
 *
 * <p>只存"判定时就知道的东西"：时间、号码、动作、原因。
 * 归属地、企业名、在线标记都在展示时现算 —— 这样缓存更新后历史里的信息也是新的，
 * 而且不用把网络返回的整段文本抄一遍。
 *
 * <p>格式：{@code 毫秒时间戳|号码|B或A|原因}
 */
final class HistoryLog {

    private static final int MAX_LINES = 500;
    private static final String NAME = "callhistory.txt";

    static final class Entry {
        long ts;
        String digits;
        boolean blocked;
        String reason;

        String time() {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(ts));
        }
    }

    private HistoryLog() {
    }

    static synchronized void add(Context ctx, String number, boolean blocked, String reason) {
        String digits = Rules.normalize(number);
        if (digits == null || digits.isEmpty()) digits = "";
        String line = System.currentTimeMillis() + "|" + digits + "|" + (blocked ? "B" : "A") + "|"
                + (reason == null ? "" : reason.replace('\n', ' ').replace('|', '/')) + "\n";
        try {
            String all = line + read(ctx);
            String[] lines = all.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, MAX_LINES); i++) {
                if (!lines[i].isEmpty()) sb.append(lines[i]).append('\n');
            }
            File f = new File(ctx.getFilesDir(), NAME);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    static synchronized String read(Context ctx) {
        File f = new File(ctx.getFilesDir(), NAME);
        if (!f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static List<Entry> recent(Context ctx, int max) {
        List<Entry> out = new ArrayList<>();
        for (String line : read(ctx).split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\\|", 4);
            if (p.length < 4) continue;
            Entry e = new Entry();
            try {
                e.ts = Long.parseLong(p[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            e.digits = p[1];
            e.blocked = "B".equals(p[2]);
            e.reason = p[3];
            out.add(e);
            if (out.size() >= max) break;
        }
        return out;
    }

    static synchronized void clear(Context ctx) {
        File f = new File(ctx.getFilesDir(), NAME);
        if (f.isFile()) f.delete();
    }
}
