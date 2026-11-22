package com.callguard.app;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置的公开客服/企业号码表——免费、离线、不依赖任何 API。
 *
 * 数据来源（公开信息，均可查证）：
 * - 各省邮政管理局公布的《邮政、快递企业投诉电话号码》（如云南省邮政管理局 2025-04 公告、
 *   山西省邮政管理局 2025-04 公告）：11185/11183/95338/95311/95554/95546/95543/950616/
 *   956025/95353/956160/951966/95333/95380/95324/952709/956138
 * - 美团官网服务热线页 https://www.meituan.com/hotline
 * - 各地政务公开的常用电话表（银行 955xx、运营商 100xx）
 *
 * 号码如有变更，直接改这张表即可，不影响其它逻辑。
 */
final class Directory {

    private static final String[][] DATA = {
            // 号码, 中文名, 英文名, 分类
            {"11185", "中国邮政", "China Post", "courier"},
            {"11183", "中国邮政 EMS", "EMS", "courier"},
            {"95338", "顺丰速运", "SF Express", "courier"},
            {"95311", "中通快递", "ZTO Express", "courier"},
            {"95554", "圆通速递", "YTO Express", "courier"},
            {"95546", "韵达快递", "Yunda Express", "courier"},
            {"95543", "申通快递", "STO Express", "courier"},
            {"950616", "京东快递", "JD Logistics", "courier"},
            {"956025", "极兔速递", "J&T Express", "courier"},
            {"95353", "德邦快递", "Deppon Express", "courier"},
            {"956160", "菜鸟速递", "Cainiao Express", "courier"},
            {"951966", "菜鸟驿站", "Cainiao Post", "courier"},
            {"95333", "丰巢", "Hive Box", "courier"},
            {"95380", "DHL", "DHL", "courier"},
            {"95324", "跨越速运", "Kuayue Express", "courier"},
            {"952709", "兔喜驿站", "Tuxi Post", "courier"},
            {"956138", "驿收发", "Yishoufa", "courier"},
            {"4008861888", "FedEx", "FedEx", "courier"},

            {"10107888", "美团", "Meituan", "food"},
            {"10109777", "美团", "Meituan", "food"},
            {"10105557", "美团外卖商家", "Meituan merchant", "food"},
            {"10105757", "饿了么", "Ele.me", "food"},

            {"95588", "工商银行", "ICBC", "bank"},
            {"95599", "农业银行", "ABC", "bank"},
            {"95566", "中国银行", "Bank of China", "bank"},
            {"95533", "建设银行", "CCB", "bank"},
            {"95559", "交通银行", "Bank of Communications", "bank"},
            {"95580", "邮储银行", "Postal Savings Bank", "bank"},
            {"95555", "招商银行", "China Merchants Bank", "bank"},
            {"95595", "光大银行", "Everbright Bank", "bank"},
            {"95558", "中信银行", "CITIC Bank", "bank"},
            {"95528", "浦发银行", "SPD Bank", "bank"},
            {"95568", "民生银行", "Minsheng Bank", "bank"},
            {"95577", "华夏银行", "Hua Xia Bank", "bank"},
            {"95511", "平安", "Ping An", "bank"},

            {"10086", "中国移动", "China Mobile", "carrier"},
            {"10010", "中国联通", "China Unicom", "carrier"},
            {"10000", "中国电信", "China Telecom", "carrier"},
            {"10099", "中国广电", "China Broadnet", "carrier"},

            {"95105105", "铁路 12306", "China Railway 12306", "gov"},

            {"9510211", "淘宝 / 天猫", "Taobao / Tmall", "shop"},
            {"95188", "支付宝", "Alipay", "shop"},
            {"95017", "微信支付", "WeChat Pay", "shop"},
    };

    private static final Map<String, String[]> MAP = new HashMap<>();

    static {
        for (String[] row : DATA) {
            MAP.put(row[0], row);
        }
    }

    private Directory() {}

    /** 供 AccountSetup 写入通讯录用。 */
    static java.util.List<String[]> all() {
        return java.util.Arrays.asList(DATA);
    }

    static int size() {
        return DATA.length;
    }

    /** 命中返回 "顺丰速运 · 快递" 这样一行；未命中返回 null。 */
    static String label(Context ctx, String digits) {
        if (digits == null) return null;
        String[] row = MAP.get(digits);
        if (row == null) return null;
        String name = Geo.isChinese() ? row[1] : row[2];
        int cat = categoryRes(row[3]);
        if (cat == 0) return name;
        return name + " · " + ctx.getString(cat);
    }

    private static int categoryRes(String key) {
        switch (key) {
            case "courier": return R.string.cat_courier;
            case "food": return R.string.cat_food;
            case "bank": return R.string.cat_bank;
            case "carrier": return R.string.cat_carrier;
            case "gov": return R.string.cat_gov;
            case "shop": return R.string.cat_shop;
            default: return 0;
        }
    }
}
