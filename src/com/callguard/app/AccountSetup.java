package com.callguard.app;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * 把内置号码表写进一个**独立账户**下的联系人，让系统来电界面能原生显示名称。
 *
 * 为什么要独立账户：通讯录的「要显示的联系人」按账户过滤，用户可以取消勾选 CallGuard
 * 让这些条目从列表消失，而 PhoneLookup（来电取名字的通道）不看这个过滤，来电照旧显示。
 */
final class AccountSetup {

    static final String TYPE = "com.callguard.app.account";
    static final String NAME = "CallGuard";

    private AccountSetup() {}

    static Account get(Context ctx) {
        Account[] list = AccountManager.get(ctx).getAccountsByType(TYPE);
        return list.length > 0 ? list[0] : null;
    }

    /** 没有就静默创建（无需登录、无服务器、不联网）。 */
    static Account ensure(Context ctx) {
        Account existing = get(ctx);
        if (existing != null) return existing;
        AccountManager am = AccountManager.get(ctx);
        Account acc = new Account(NAME, TYPE);
        try {
            if (am.addAccountExplicitly(acc, null, null)) {
                ContentResolver.setIsSyncable(acc, ContactsContract.AUTHORITY, 1);
                ContentResolver.setSyncAutomatically(acc, ContactsContract.AUTHORITY, false);
            } else {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
        return acc;
    }

    private static String[] selection() {
        return new String[]{TYPE, NAME};
    }

    private static String where() {
        return ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND "
                + ContactsContract.RawContacts.ACCOUNT_NAME + "=?";
    }

    static int count(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI,
                new String[]{"_id"}, where(), selection(), null)) {
            return c == null ? 0 : c.getCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 只删自己账户下的条目，不碰用户原有联系人。 */
    static int clear(Context ctx) {
        Account acc = get(ctx);
        if (acc == null) return 0;
        int before = count(ctx);
        try {
            android.net.Uri uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, acc.name)
                    .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, acc.type)
                    .build();
            ctx.getContentResolver().delete(uri, null, null);
        } catch (Throwable t) {
            return 0;
        }
        // 清空后把可见性还原，免得以后重新写入时状态莫名其妙
        setHidden(ctx, false);
        return before - count(ctx);
    }

    /** 写入全部内置号码；返回写入条数（-1 表示账户创建失败）。 */
    static int write(Context ctx) {
        return write(ctx, true);
    }
    /**
     * 把"查到的名字"写成一条联系人 —— 下次来电时系统界面就能直接显示，
     * 不用再弹悬浮窗（这是系统唯一认的信息来源）。
     *
     * <p>只写有实际标签的号码；用户自己存过的、或者我们已经写过的都跳过。
     *
     * @return true 表示这次真的写入了
     */
    static boolean saveOne(Context ctx, String number, String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String digits = Rules.normalize(number);
        if (digits == null || digits.length() < 5) return false;
        // 用户自己存过 → 不动；我们已写过 → 跳过
        if (ContactsLookup.ownName(ctx, digits) != null) return false;
        if (existsInAccount(ctx, digits)) return false;

        Account acc = ensure(ctx);
        if (acc == null) return false;
        long groupId = ensureGroup(ctx, acc);

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, acc.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, acc.name)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, digits)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN)
                .build());
        if (groupId > 0) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.DATA1, groupId)
                    .build());
        }
        try {
            ctx.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (Throwable t) {
            return false;
        }
        // 写进来的默认从通讯录列表隐藏，和批量写入保持一致
        setHidden(ctx, true);
        ContactsLookup.invalidate();
        return true;
    }

    /** 这个号码是否已经在 CallGuard 账户下。 */
    private static boolean existsInAccount(Context ctx, String digits) {
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.RawContacts.ACCOUNT_TYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, TYPE}, null)) {
            if (c == null) return false;
            while (c.moveToNext()) {
                String d = digitsOf(c.getString(0));
                if (digits.equals(d)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 用户后来自己把同样的号码存进通讯录了 → 删掉我们写的那条。
     *
     * <p>不删的后果：同一个号码有两个联系人条目，系统的 PhoneLookup 命中哪一个不确定，
     * 来电界面可能显示我们写的「顺丰速运 · 快递」，把用户自己起的名字顶掉。
     *
     * <p>按<b>号码</b>比对（而不是聚合结果）——实测两个同号联系人不一定被系统聚合到一起。
     *
     * @return 清掉的条数
     */
    static int pruneUserDuplicates(Context ctx) {
        Account acc = get(ctx);
        if (acc == null) return 0;
        final String ours = acc.type;

        java.util.Set<String> foreign = new java.util.HashSet<>();
        java.util.Map<Long, String> mine = new java.util.LinkedHashMap<>();   // rawId -> 号码

        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.RAW_CONTACT_ID,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE}, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                long raw = c.getLong(0);
                String type = c.isNull(1) ? "" : c.getString(1);
                String digits = digitsOf(c.getString(2));
                if (digits == null || digits.length() < 5) continue;
                if (ours.equals(type)) mine.put(raw, digits);
                else foreign.add(digits);
            }
        } catch (Throwable t) {
            android.util.Log.w("CallGuard", "prune query failed", t);
            return 0;
        }
        android.net.Uri uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, acc.name)
                .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, acc.type)
                .build();
        int removed = 0;
        for (java.util.Map.Entry<Long, String> e : mine.entrySet()) {
            if (!foreign.contains(e.getValue())) continue;
            try {
                removed += ctx.getContentResolver().delete(uri,
                        ContactsContract.RawContacts._ID + "=" + e.getKey(), null);
            } catch (Throwable ignored) {
            }
        }
        if (removed > 0) ContactsLookup.invalidate();
        return removed;
    }

    private static String digitsOf(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 这个号码是否值得留在通讯录里。
     *
     * <p>只有**公开确认**的号码才算：
     * <ul>
     *   <li>内置企业号码表命中（顺丰、10086 这类，号码本身就是公开的）</li>
     *   <li>在线缓存里有「✅ 认证」记录</li>
     * </ul>
     */
    static boolean isPublicNumber(Context ctx, String digits) {
        if (digits == null || digits.isEmpty()) return false;
        String known = Directory.label(ctx, digits);
        if (known != null && !known.isEmpty()) return true;
        for (String line : Online.screenLines(ctx, digits)) {
            if (line != null && line.trim().startsWith("✅")) return true;
        }
        return false;
    }

    /**
     * 按新规则整理已经写入的联系人：删掉"非公开"的那些。
     *
     * <p>旧版本只要查到任何标签就写联系人，于是「骚扰电话（360）」「快递送餐」这类
     * **别人对这个号码的判断**也变成了联系人条目——既污染通讯录，又和来电界面上的
     * 标签行重复。这里重新筛一遍，只保留公开号码。
     *
     * @return 删掉的条数
     */
    static int pruneNonPublic(Context ctx) {
        Account acc = get(ctx);
        if (acc == null) return 0;

        java.util.List<Long> doomed = new java.util.ArrayList<>();
        String where = ContactsContract.RawContacts.ACCOUNT_TYPE + "=? AND "
                + ContactsContract.RawContacts.ACCOUNT_NAME + "=? AND "
                + ContactsContract.Data.MIMETYPE + "=?";
        String[] args = {acc.type, acc.name, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE};

        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.DATA1},
                where, args, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long rawId = c.getLong(0);
                    String digits = digitsOf(c.getString(1));
                    if (!isPublicNumber(ctx, digits)) doomed.add(rawId);
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        if (doomed.isEmpty()) return 0;

        int removed = 0;
        for (Long rawId : doomed) {
            try {
                android.net.Uri uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(rawId))
                        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, acc.name)
                        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, acc.type)
                        .build();
                removed += ctx.getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) {
            }
        }
        if (removed > 0) ContactsLookup.invalidate();
        return removed;
    }

    /** @param hide 写完后是否从通讯录列表隐藏（用户可在设置里关掉） */
    static int write(Context ctx, boolean hide) {
        Account acc = ensure(ctx);
        if (acc == null) return -1;
        clear(ctx);
        long groupId = ensureGroup(ctx, acc);

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        int added = 0;
        for (String[] row : Directory.all()) {
            String number = row[0];
            String label = Directory.label(ctx, number);
            if (label == null) continue;
            // 用户自己已经存了这个号码 → 跳过，避免聚合改名 / 重复条目
            if (hasUserContact(ctx, number)) continue;

            int raw = ops.size();
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, acc.type)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, acc.name)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, raw)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, label)
                    .build());
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, raw)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN)
                    .build());
            if (groupId > 0) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, raw)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.Data.DATA1, groupId)
                        .build());
            }
            added++;
        }

        if (ops.isEmpty()) return 0;
        try {
            ctx.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (Throwable t) {
            return 0;
        }
        // 写进来的号码只用于来电显示名称，默认别脏了通讯录列表
        setHidden(ctx, hide);
        return added;
    }

    /**
     * 让这笔联系人不出现在通讯录列表里，但 PhoneLookup 仍然查得到（来电照常显示名字）。
     *
     * <p>等价于「设置 → 要显示的联系人 → Customize → 取消勾选 CallGuard」，只是走库：
     * {@code Groups.GROUP_VISIBLE=0} + {@code Settings.UNGROUPED_VISIBLE=0}。
     * 之所以不依赖那条 UI：LineageOS 的 Customize 页有布局 bug，列表第一行会被标题栏
     * 整个盖住，而 CallGuard 按字母排序正好排在 Device 前面，勾选框点不到。
     */
    static void setHidden(Context ctx, boolean hidden) {
        Account acc = get(ctx);
        if (acc == null) return;
        android.content.ContentResolver cr = ctx.getContentResolver();

        long gid = findGroup(ctx, acc);
        if (gid > 0) {
            android.content.ContentValues g = new android.content.ContentValues();
            g.put(ContactsContract.Groups.GROUP_VISIBLE, hidden ? 0 : 1);
            android.net.Uri uri = ContactsContract.Groups.CONTENT_URI.buildUpon()
                    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                    .build();
            try {
                cr.update(uri, g, ContactsContract.Groups._ID + "=" + gid, null);
            } catch (Throwable ignored) {
            }
        }

        // UNGROUPED_VISIBLE 管的是"没有分组的联系人"，保险起见一起设
        android.content.ContentValues s = new android.content.ContentValues();
        s.put(ContactsContract.Settings.UNGROUPED_VISIBLE, hidden ? 0 : 1);
        String sel = ContactsContract.Settings.ACCOUNT_NAME + "=? AND "
                + ContactsContract.Settings.ACCOUNT_TYPE + "=?";
        try {
            int n = cr.update(ContactsContract.Settings.CONTENT_URI, s, sel,
                    new String[]{acc.name, acc.type});
            if (n == 0 && hidden) {
                s.put(ContactsContract.Settings.ACCOUNT_NAME, acc.name);
                s.put(ContactsContract.Settings.ACCOUNT_TYPE, acc.type);
                cr.insert(ContactsContract.Settings.CONTENT_URI, s);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前这个账户在通讯录里是否处于隐藏状态（读 Groups.GROUP_VISIBLE）。 */
    static boolean isHidden(Context ctx) {
        Account acc = get(ctx);
        if (acc == null) return false;
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Groups.CONTENT_URI,
                new String[]{ContactsContract.Groups.GROUP_VISIBLE},
                ContactsContract.Groups.ACCOUNT_TYPE + "=? AND " + ContactsContract.Groups.ACCOUNT_NAME + "=?",
                new String[]{acc.type, acc.name}, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getInt(0) == 0;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 账户下已有的分组 id，没有则返回 0（不会创建）。 */
    private static long findGroup(Context ctx, Account acc) {
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.Groups.CONTENT_URI,
                new String[]{ContactsContract.Groups._ID},
                ContactsContract.Groups.ACCOUNT_TYPE + "=? AND " + ContactsContract.Groups.ACCOUNT_NAME + "=?",
                new String[]{acc.type, acc.name}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** AOSP 通讯录的「Customize view」按分组列出，所以给账户建一个 CallGuard 分组。 */
    private static long ensureGroup(Context ctx, Account acc) {
        long existing = findGroup(ctx, acc);
        if (existing > 0) return existing;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(ContactsContract.Groups.ACCOUNT_TYPE, acc.type);
            cv.put(ContactsContract.Groups.ACCOUNT_NAME, acc.name);
            cv.put(ContactsContract.Groups.TITLE, NAME);
            cv.put(ContactsContract.Groups.GROUP_VISIBLE, 1);
            android.net.Uri uri = ctx.getContentResolver()
                    .insert(ContactsContract.Groups.CONTENT_URI, cv);
            if (uri != null) {
                String id = uri.getLastPathSegment();
                return id == null ? 0 : Long.parseLong(id);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean hasUserContact(Context ctx, String number) {        android.net.Uri uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number));
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }
}
