package com.callguard.app;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 号段库：直接读取 phone.dat（ls0f/phone、xluohome/phonedata 同格式）。
 *
 * <pre>
 * 头部： 4s  version     | int32 first_record_offset   (小端)
 * 记录： int32 prefix(7位)| int32 content_offset | uint8 carrier   (9 字节，按 prefix 升序)
 * 内容： "省|市|邮编|区号\0"
 * carrier: 1移动 2联通 3电信 4电信虚拟 5联通虚拟 6移动虚拟 7广电 8广电虚拟
 * </pre>
 */
public final class Db {

    private static final String TAG = "CallGuardDb";
    private static final String ASSET = "phone.dat";
    private static final int REC_LEN = 9;

    public static final class Rec {
        public final int prefix;
        public final String province;
        public final String city;
        public final String zip;
        public final String area;
        public final int carrier;

        Rec(int prefix, String province, String city, String zip, String area, int carrier) {
            this.prefix = prefix;
            this.province = province;
            this.city = city;
            this.zip = zip;
            this.area = area;
            this.carrier = carrier;
        }

        public String carrierName(Context c) {
            int id;
            switch (carrier) {
                case 1: id = R.string.carrier_1; break;
                case 2: id = R.string.carrier_2; break;
                case 3: id = R.string.carrier_3; break;
                case 4: id = R.string.carrier_4; break;
                case 5: id = R.string.carrier_5; break;
                case 6: id = R.string.carrier_6; break;
                case 7: id = R.string.carrier_7; break;
                case 8: id = R.string.carrier_8; break;
                default: return c.getString(R.string.carrier_unknown);
            }
            return c.getString(id);
        }

        public boolean isVirtual() {
            return carrier == 4 || carrier == 5 || carrier == 6 || carrier == 8;
        }

        public String label(Context c) {
            String p = Geo.provinceName(province);
            if (province.equals(city)) return p + " " + carrierName(c);
            return p + " " + Geo.cityName(city) + " " + carrierName(c);
        }
    }

    private static volatile Db sInstance;

    private final byte[] buf;
    private final int first;
    private final int count;
    private final String version;

    private Db(byte[] buf) {
        this.buf = buf;
        this.version = buf.length >= 4
                ? new String(buf, 0, 4, StandardCharsets.US_ASCII) : "?";
        int f = buf.length >= 8 ? readInt(buf, 4) : -1;
        if (f < 8 || f >= buf.length || (buf.length - f) % REC_LEN != 0) {
            this.first = buf.length;
            this.count = 0;
        } else {
            this.first = f;
            this.count = (buf.length - f) / REC_LEN;
        }
    }

    public static Db get(Context ctx) {
        Db d = sInstance;
        if (d != null) return d;
        synchronized (Db.class) {
            if (sInstance == null) {
                try {
                    sInstance = new Db(readAll(ctx, ASSET));
                    Log.i(TAG, "loaded phone.dat ver=" + sInstance.version + " records=" + sInstance.count);
                } catch (IOException e) {
                    Log.e(TAG, "load phone.dat failed", e);
                }
            }
            return sInstance;
        }
    }

    /** 后台预热，避免第一通电话时才读 4MB。 */
    public static void preload(Context ctx) {
        new Thread(() -> get(ctx.getApplicationContext()), "db-preload").start();
    }

    private static byte[] slurp(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4 * 1024 * 1024);
            byte[] tmp = new byte[64 * 1024];
            int n;
            while ((n = s.read(tmp)) > 0) bos.write(tmp, 0, n);
            return bos.toByteArray();
        }
    }

    private static byte[] readAll(Context ctx, String asset) throws IOException {
        File external = new File(ctx.getFilesDir(), ASSET);
        if (external.isFile()) {
            // 用户导入过新库，优先用它
            try {
                return slurp(new java.io.FileInputStream(external));
            } catch (IOException ignored) {
            }
        }
        return slurp(ctx.getAssets().open(asset));
    }

    /** 从外部文件（如新版 phone.dat）替换号段库。成功返回 true。 */
    public static boolean importFrom(Context ctx, File src) {
        try {
            long size = src.length();
            if (size < 16 || size > 64L * 1024 * 1024) return false;
            byte[] data = slurp(new java.io.FileInputStream(src));
            if (!valid(data)) return false;
            File dst = new File(ctx.getFilesDir(), ASSET);
            File tmp = new File(ctx.getFilesDir(), ASSET + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(dst)) {
                try (FileOutputStream fos = new FileOutputStream(dst)) { fos.write(data); }
                tmp.delete();
            }
            // 版本号相同但内容不同的库也要让城市缓存失效。
            File[] cached = ctx.getFilesDir().listFiles((d, n) -> n.startsWith("cities-") && n.endsWith(".txt"));
            if (cached != null) for (File f : cached) f.delete();
            synchronized (Db.class) { sInstance = null; }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "import failed", e);
            return false;
        }
    }

    /** 校验头部、排序和内容偏移，避免导入损坏文件后在通话路径崩溃。 */
    private static boolean valid(byte[] data) {
        if (data.length < 16) return false;
        int first = readInt(data, 4);
        if (first < 8 || first >= data.length || (data.length - first) % REC_LEN != 0) return false;
        int count = (data.length - first) / REC_LEN;
        if (count == 0) return false;
        int prev = -1;
        for (int i = 0; i < count; i++) {
            int off = first + i * REC_LEN;
            int prefix = readInt(data, off);
            int content = readInt(data, off + 4);
            if (prefix < 1000000 || prefix > 9999999 || prefix < prev) return false;
            if (content < 0 || content >= data.length) return false;
            prev = prefix;
        }
        return true;
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private int readInt(int off) {
        return readInt(buf, off);
    }

    public String version() { return version; }

    public int recordCount() { return count; }

    /** 用 7 位号段查归属地，未命中返回 null。 */
    public Rec lookup(int prefix7) {
        int left = 0, right = count - 1;
        while (left <= right) {
            int mid = (left + right) >>> 1;
            int off = first + mid * REC_LEN;
            if (off + REC_LEN > buf.length) return null;
            int ph = readInt(off);
            if (ph == prefix7) {
                int ro = readInt(off + 4);
                if (ro < 0 || ro >= buf.length) return null;
                int type = buf[off + 8] & 0xff;
                int end = ro;
                while (end < buf.length && buf[end] != 0) end++;
                String[] p = new String(buf, ro, end - ro, StandardCharsets.UTF_8).split("\\|", -1);
                String province = p.length > 0 ? p[0] : "";
                String city = p.length > 1 ? p[1] : "";
                String zip = p.length > 2 ? p[2] : "";
                String area = p.length > 3 ? p[3] : "";
                return new Rec(ph, province, city, zip, area, type);
            }
            if (ph > prefix7) right = mid - 1; else left = mid + 1;
        }
        return null;
    }

    /** 省份 -> 城市集合，用于界面选择；首次生成后缓存到 files/cities.txt。 */
    public Map<String, List<String>> provinceCities(Context ctx) {
        File cache = new File(ctx.getFilesDir(), "cities-" + version + ".txt");
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (cache.isFile()) {
            try {
                for (String line : readText(cache).split("\n")) {
                    int i = line.indexOf('|');
                    if (i <= 0) continue;
                    map.computeIfAbsent(line.substring(0, i), k -> new ArrayList<>()).add(line.substring(i + 1));
                }
                if (!map.isEmpty()) return map;
            } catch (IOException ignored) {
            }
        }
        Map<String, Set<String>> tmp = new LinkedHashMap<>();
        String[] fixed = {"北京", "天津", "上海", "重庆", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
                "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东",
                "广西", "海南", "四川", "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏",
                "新疆", "香港", "澳门", "台湾"};
        for (String p : fixed) tmp.put(p, new LinkedHashSet<>());
        for (int i = 0; i < count; i++) {
            int off = first + i * REC_LEN;
            if (off + REC_LEN > buf.length) break;
            int ro = readInt(off + 4);
            if (ro < 0 || ro >= buf.length) continue;
            int end = ro;
            while (end < buf.length && buf[end] != 0) end++;
            String rec = new String(buf, ro, end - ro, StandardCharsets.UTF_8);
            int a = rec.indexOf('|');
            if (a <= 0) continue;
            int b = rec.indexOf('|', a + 1);
            String province = rec.substring(0, a);
            String city = b > a ? rec.substring(a + 1, b) : province;
            tmp.computeIfAbsent(province, k -> new LinkedHashSet<>()).add(city);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : tmp.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            map.put(e.getKey(), new ArrayList<>(e.getValue()));
            for (String c : e.getValue()) sb.append(e.getKey()).append('|').append(c).append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(cache)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        return map;
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) bos.write(tmp, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
