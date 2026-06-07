package com.datanote.domain.governance.util;

import com.datanote.domain.governance.model.DnSensitiveRule;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感识别引擎 — 纯静态函数：正则 + 字典 + 校验位，不引 NLP/ML。
 * 列名命中为强信号，取值校验位/正则按命中率给置信度，综合取最高。
 */
public final class SensitiveDetector {

    private SensitiveDetector() {}

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 识别候选结果 */
    public static class Candidate {
        public String sensitiveType;
        public String suggestLevel;
        public int confidence;       // 0-100
        public boolean hitColumnName; // 是否列名命中
        public double hitRate;        // 取值命中率 0-1

        public Candidate(String sensitiveType, String suggestLevel, int confidence,
                         boolean hitColumnName, double hitRate) {
            this.sensitiveType = sensitiveType;
            this.suggestLevel = suggestLevel;
            this.confidence = confidence;
            this.hitColumnName = hitColumnName;
            this.hitRate = hitRate;
        }
    }

    // ========== 校验位 / 正则纯函数 ==========

    /** 中国大陆手机号 */
    public static boolean isPhone(String s) {
        return s != null && PHONE.matcher(s).matches();
    }

    /** 邮箱 */
    public static boolean isEmail(String s) {
        return s != null && EMAIL.matcher(s).matches();
    }

    /** 18 位身份证（含末位 ISO 7064 MOD 11-2 校验码，末位 X 不分大小写） */
    public static boolean isIdCard18(String s) {
        if (s == null || s.length() != 18) return false;
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] code = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
            sum += (ch - '0') * w[i];
        }
        char last = Character.toUpperCase(s.charAt(17));
        return last == code[sum % 11];
    }

    /** 银行卡号 Luhn 校验（13-19 位数字） */
    public static boolean luhn(String s) {
        if (s == null || s.length() < 13 || s.length() > 19) return false;
        int sum = 0;
        boolean alt = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
            int d = ch - '0';
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** 18 位统一社会信用代码（GB32100 末位校验，基于 GS1 字符集） */
    public static boolean isUscc(String s) {
        if (s == null || s.length() != 18) return false;
        final String chars = "0123456789ABCDEFGHJKLMNPQRTUWXY";
        int[] w = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int idx = chars.indexOf(Character.toUpperCase(s.charAt(i)));
            if (idx < 0) return false;
            sum += idx * w[i];
        }
        int c = 31 - (sum % 31);
        if (c == 31) c = 0;
        int lastIdx = chars.indexOf(Character.toUpperCase(s.charAt(17)));
        return lastIdx == c;
    }

    // ========== 识别入口 ==========

    /** 列名字典命中（强信号），返回首个命中的候选，无则 null。 */
    public static Candidate detectByColumnName(String columnName, List<DnSensitiveRule> rules) {
        if (columnName == null || rules == null) return null;
        String name = columnName.toLowerCase();
        for (DnSensitiveRule r : rules) {
            if (!enabled(r) || !"COLUMN_NAME".equals(r.getMatchType())) continue;
            for (String kw : splitKeywords(r.getPattern())) {
                if (!kw.isEmpty() && name.contains(kw.toLowerCase())) {
                    return new Candidate(r.getSensitiveType(), r.getSuggestLevel(), 80, true, 0d);
                }
            }
        }
        return null;
    }

    /** 取值命中：按 REGEX/VALIDATOR 规则统计命中率，取命中率最高的候选，无则 null。 */
    public static Candidate detectByValue(List<String> sampleValues, List<DnSensitiveRule> rules) {
        if (sampleValues == null || rules == null) return null;
        Candidate best = null;
        for (DnSensitiveRule r : rules) {
            if (!enabled(r)) continue;
            if (!"REGEX".equals(r.getMatchType()) && !"VALIDATOR".equals(r.getMatchType())) continue;
            int total = 0, hit = 0;
            Pattern regex = "REGEX".equals(r.getMatchType()) ? safeCompile(r.getPattern()) : null;
            if ("REGEX".equals(r.getMatchType()) && regex == null) continue;
            for (String v : sampleValues) {
                if (v == null || v.trim().isEmpty()) continue; // 空值不计入分母
                total++;
                String val = v.trim();
                boolean ok = regex != null ? regex.matcher(val).matches() : matchValidator(r.getPattern(), val);
                if (ok) hit++;
            }
            if (total == 0) continue;
            double rate = (double) hit / total;
            if (hit == 0) continue;
            int conf = (int) Math.round(rate * 90); // 取值满命中最高 90
            if (best == null || conf > best.confidence) {
                best = new Candidate(r.getSensitiveType(), r.getSuggestLevel(), conf, false, rate);
            }
        }
        return best;
    }

    /**
     * 综合识别：列名命中 + 取值命中，同类型双命中加成（封顶 100），否则取置信度更高者。
     */
    public static Candidate detect(String columnName, List<String> sampleValues, List<DnSensitiveRule> rules) {
        Candidate byName = detectByColumnName(columnName, rules);
        Candidate byValue = detectByValue(sampleValues, rules);
        if (byName == null) return byValue;
        if (byValue == null) return byName;
        // 同一敏感类型双命中：合成高置信度
        if (byName.sensitiveType != null && byName.sensitiveType.equals(byValue.sensitiveType)) {
            int conf = Math.min(100, byName.confidence + Math.round((float) (byValue.confidence * 0.3)) + 10);
            return new Candidate(byName.sensitiveType, byName.suggestLevel, conf, true, byValue.hitRate);
        }
        return byName.confidence >= byValue.confidence ? byName : byValue;
    }

    // ========== 内部 ==========

    /** 校验器名 → 校验函数 */
    private static boolean matchValidator(String validator, String val) {
        if (validator == null) return false;
        switch (validator.trim().toUpperCase()) {
            case "PHONE":    return isPhone(val);
            case "EMAIL":    return isEmail(val);
            case "ID_CARD":  return isIdCard18(val);
            case "BANKCARD":
            case "BANK_CARD": return luhn(val);
            case "USCC":     return isUscc(val);
            default:         return false;
        }
    }

    private static boolean enabled(DnSensitiveRule r) {
        return r != null && r.getEnabled() != null && r.getEnabled() == 1;
    }

    private static String[] splitKeywords(String pattern) {
        return pattern == null ? new String[0] : pattern.split(",");
    }

    private static Pattern safeCompile(String regex) {
        if (regex == null) return null;
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
    }
}
