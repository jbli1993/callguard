package com.callguard.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 本机拦截日志（只写本地文件，最多 300 条）。 */
final class BlockLog {

    private static final int MAX_LINES = 300;
    private static final String NAME = "blocklog.txt";

    private BlockLog() {}

    static synchronized void add(Context ctx, String number, String desc) {
        File f = new File(ctx.getFilesDir(), NAME);
        String shown = (number == null || number.isEmpty())
                ? ctx.getString(R.string.log_hidden_number) : number;
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  " + shown + "  " + desc + "\n";
        try {
            String old = read(ctx);
            String all = line + old;
            String[] lines = all.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, MAX_LINES); i++) {
                if (!lines[i].isEmpty()) sb.append(lines[i]).append('\n');
            }
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

    static synchronized void clear(Context ctx) {
        File f = new File(ctx.getFilesDir(), NAME);
        if (f.isFile()) f.delete();
    }

    /**
     * 这个号码最近一次被拦的**简短原因**（例如「命中地区 新疆 乌鲁木齐」「未知号段」）。
     *
     * <p>日志行格式：`MM-dd HH:mm:ss  号码  「归属地 · 原因」`，取「·」后面的部分。
     * 找不到返回 null。给拨号器的通话记录用——`call_log` 只有 `block_reason`（谁拦的），
     * 说不出「按地区还是按号段」。
     */
    static synchronized String reasonOf(Context ctx, String digits) {
        if (digits == null || digits.isEmpty()) return null;
        for (String line : read(ctx).split("\n")) {
            String[] parts = line.split("\\s{2,}");
            if (parts.length < 3) continue;
            if (!digits.equals(parts[1].trim())) continue;
            String desc = parts[2].trim();
            int dot = desc.indexOf(" · ");
            return shorten(ctx, dot > 0 ? desc.substring(dot + 3).trim() : desc);
        }
        return null;
    }

    /**
     * 旧日志里存的是当时的长措辞，读的时候换成本地化后的短文案。
     *
     * <p>拦截日志最多 300 条、会自然滚动；这里只是让升级后立刻看到新措辞。
     */
    private static String shorten(Context ctx, String reason) {
        String oldRegion = "region hit ";
        if (reason.startsWith(oldRegion)) {
            return ctx.getString(R.string.reason_region_hit,
                    Geo.place(reason.substring(oldRegion.length()).trim()));
        }
        // 老日志里混着中文城市名（当时直接用了库里的原文），英文界面下补一次拼音
        String newRegion = "region ";
        if (reason.startsWith(newRegion)) {
            return ctx.getString(R.string.reason_region_hit,
                    Geo.place(reason.substring(newRegion.length()).trim()));
        }
        if (reason.startsWith("privacy relay number (") || reason.startsWith("号码保护中间号")) {
            return ctx.getString(R.string.reason_relay);
        }
        if (reason.startsWith("short code / service number")) {
            return ctx.getString(R.string.reason_short);
        }
        return reason;
    }
}
