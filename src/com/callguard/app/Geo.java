package com.callguard.app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 号段库里的省名和城市名都是中文（数据源如此），运营商名走 `R.string.carrier_*`
 * 会跟随系统语言。
 *
 * <p>为了让界面不出现中英混搭（"Yunnan 曲靖 Unicom"），非中文界面下：
 * <ul>
 *   <li>省份走人工映射（"Inner Mongolia"、"Tibet" 这种拼音反而难认）</li>
 *   <li>城市走 ICU 的 Han-Latin 转换器（近 400 个城市，不做人工翻译）</li>
 * </ul>
 */
final class Geo {

    private static final Map<String, String> EN = new HashMap<>();
    private static android.icu.text.Transliterator sTranslit;

    static {
        EN.put("北京", "Beijing");
        EN.put("天津", "Tianjin");
        EN.put("上海", "Shanghai");
        EN.put("重庆", "Chongqing");
        EN.put("河北", "Hebei");
        EN.put("山西", "Shanxi");
        EN.put("内蒙古", "Inner Mongolia");
        EN.put("辽宁", "Liaoning");
        EN.put("吉林", "Jilin");
        EN.put("黑龙江", "Heilongjiang");
        EN.put("江苏", "Jiangsu");
        EN.put("浙江", "Zhejiang");
        EN.put("安徽", "Anhui");
        EN.put("福建", "Fujian");
        EN.put("江西", "Jiangxi");
        EN.put("山东", "Shandong");
        EN.put("河南", "Henan");
        EN.put("湖北", "Hubei");
        EN.put("湖南", "Hunan");
        EN.put("广东", "Guangdong");
        EN.put("广西", "Guangxi");
        EN.put("海南", "Hainan");
        EN.put("四川", "Sichuan");
        EN.put("贵州", "Guizhou");
        EN.put("云南", "Yunnan");
        EN.put("西藏", "Tibet");
        EN.put("陕西", "Shaanxi");
        EN.put("甘肃", "Gansu");
        EN.put("青海", "Qinghai");
        EN.put("宁夏", "Ningxia");
        EN.put("新疆", "Xinjiang");
        EN.put("香港", "Hong Kong");
        EN.put("澳门", "Macau");
        EN.put("台湾", "Taiwan");
    }

    private Geo() {}

    static boolean isChinese() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }

    static String provinceName(String cn) {
        if (cn == null) return "";
        if (isChinese()) return cn;
        String en = EN.get(cn);
        return en != null ? en : pinyin(cn);
    }

    /**
     * 城市名。中文界面原样返回；其他语言用拼音（"曲靖" → "Qujing"）。
     */
    static String cityName(String cn) {
        if (cn == null) return "";
        if (isChinese()) return cn;
        return pinyin(cn);
    }

    /**
     * 把 "Yunnan 曲靖" 这种中英混排的地名转成 "Yunnan Qujing"。
     *
     * <p>用于老数据迁移：中文片段走拼音，已经是英文的片段原样保留。
     * 中文界面下原样返回。
     */
    static String place(String text) {
        if (text == null || text.isEmpty()) return "";
        if (isChinese()) return text;
        StringBuilder sb = new StringBuilder();
        for (String part : text.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(hasHan(part) ? pinyin(part) : part);
        }
        return sb.toString();
    }

    private static boolean hasHan(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /**
     * 中文 → 拼音：去掉声调符号和空格，首字母大写。
     *
     * <p>用 Android 自带的 ICU 转换器（API 24+），不需要额外依赖。
     * "曲靖" → "Qūjìng" → "Qujing"；"乌鲁木齐" → "Wulumuqi"。
     */
    static String pinyin(String cn) {
        if (cn == null || cn.isEmpty()) return "";
        try {
            android.icu.text.Transliterator t = translit();
            if (t == null) return cn;
            String raw = t.transliterate(cn);
            String flat = java.text.Normalizer
                    .normalize(raw, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .replaceAll("\\s+", "");
            if (flat.isEmpty()) return cn;
            return Character.toUpperCase(flat.charAt(0)) + flat.substring(1);
        } catch (Throwable e) {
            return cn;
        }
    }

    private static android.icu.text.Transliterator translit() {
        if (sTranslit == null) {
            try {
                sTranslit = android.icu.text.Transliterator.getInstance("Han-Latin/Names");
            } catch (Throwable ignored) {
            }
        }
        return sTranslit;
    }
}
