package com.yuceyang.uitest.testcase;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用例编号统一为 GN-00001 这种五位格式。
 * 库里已有的 GN-0002 这类老编号读取时补齐位数，避免新旧混在一起。
 */
public final class CaseNumbers {
    private static final Pattern NUMBER = Pattern.compile("^\\s*GN[-_ ]?(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    private CaseNumbers() {}

    public static String format(int sequence) {
        return String.format("GN-%05d", Math.max(0, sequence));
    }

    /** 老格式（GN-0001 / GN-1）补齐成五位；不是编号原样返回。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        Matcher matcher = NUMBER.matcher(raw);
        if (!matcher.matches()) return raw;
        try {
            return format(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** 从编号里取序号，解析不了返回 0。 */
    public static int sequence(String raw) {
        if (raw == null) return 0;
        Matcher matcher = NUMBER.matcher(raw);
        if (!matcher.matches()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
