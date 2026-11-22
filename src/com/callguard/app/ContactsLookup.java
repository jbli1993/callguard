package com.callguard.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.util.HashMap;
import java.util.Map;

/**
 * 通讯录查询（仅在授予 READ_CONTACTS 时使用），带小容量缓存。
 *
 * <p><b>必须排除 CallGuard 自己写入的账户</b>：那些号码是我们写进去给来电界面显示名称用的，
 * 如果算作"用户联系人"，拦截规则就会被自己写的数据反向绕过（比如拦了 95338，
 * 却因为 95338 在 CallGuard 账户里而被放行）。
 */
final class ContactsLookup {

    private static final Map<String, Boolean> CACHE = new HashMap<>();
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    private ContactsLookup() {
    }

    /** 这个号码是不是"用户自己存的"联系人（不含 CallGuard 账户里的条目）。 */
    static boolean isKnown(Context ctx, String digits) {
        if (digits == null || digits.length() < 5) return false;
        Boolean cached = CACHE.get(digits);
        if (cached != null) return cached;
        boolean found = ownName(ctx, digits) != null;
        if (CACHE.size() > 300) CACHE.clear();
        CACHE.put(digits, found);
        return found;
    }

    /** 用户自己存的名字；没有（或只在 CallGuard 账户里）返回 null。 */
    static String ownName(Context ctx, String digits) {
        if (digits == null || digits.length() < 5) return null;
        if (NAME_CACHE.containsKey(digits)) return NAME_CACHE.get(digits);
        String name = null;
        long contactId = 0;
        Cursor cur = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(digits));
            cur = ctx.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.CONTACT_ID,
                            ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                contactId = cur.getLong(0);
                name = cur.getString(1);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (contactId > 0 && !hasForeignRaw(ctx, contactId)) name = null;
        if (NAME_CACHE.size() > 300) NAME_CACHE.clear();
        NAME_CACHE.put(digits, name);
        return name;
    }

    /** 这个聚合联系人里有没有"不是 CallGuard 账户"的原始条目。 */
    private static boolean hasForeignRaw(Context ctx, long contactId) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI,
                    new String[]{ContactsContract.RawContacts.ACCOUNT_TYPE},
                    ContactsContract.RawContacts.CONTACT_ID + "=? AND "
                            + ContactsContract.RawContacts.DELETED + "=0",
                    new String[]{String.valueOf(contactId)}, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                String type = c.isNull(0) ? "" : c.getString(0);
                if (!AccountSetup.TYPE.equals(type)) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    static void invalidate() {
        CACHE.clear();
        NAME_CACHE.clear();
    }
}
