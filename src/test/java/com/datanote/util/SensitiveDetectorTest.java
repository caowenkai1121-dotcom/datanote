package com.datanote.util;

import com.datanote.model.DnSensitiveRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 敏感识别引擎纯函数单测 — 重点覆盖 Luhn / 身份证校验码 / 手机 / 邮箱 / 统一社会信用代码 / 列名命中。
 */
class SensitiveDetectorTest {

    // ========== 校验位 / 正则 ==========

    @Test
    void luhnAcceptsValidCardAndRejectsInvalid() {
        assertTrue(SensitiveDetector.luhn("622260000000000014"), "Luhn 正例应通过");
        assertFalse(SensitiveDetector.luhn("622260000000000015"), "Luhn 反例应拒绝");
        assertFalse(SensitiveDetector.luhn(""), "空串非银行卡");
        assertFalse(SensitiveDetector.luhn("12a4"), "含非数字非银行卡");
    }

    @Test
    void idCard18ValidatesCheckDigit() {
        assertTrue(SensitiveDetector.isIdCard18("11010119900307371 4".replace(" ", "")), "正确校验码应通过");
        assertFalse(SensitiveDetector.isIdCard18("110101199003073715"), "错误校验码应拒绝");
        assertFalse(SensitiveDetector.isIdCard18("1101011990030737"), "17 位不是身份证");
        assertTrue(SensitiveDetector.isIdCard18("11010119900307379x"), "末位 X 小写也可");
        assertTrue(SensitiveDetector.isIdCard18("11010119900307379X"), "末位 X 大写也可");
    }

    @Test
    void phoneRegexMatchesChineseMobile() {
        assertTrue(SensitiveDetector.isPhone("13800138000"));
        assertTrue(SensitiveDetector.isPhone("19912345678"));
        assertFalse(SensitiveDetector.isPhone("12345678901"), "第二位非 3-9 不匹配");
        assertFalse(SensitiveDetector.isPhone("1380013800"), "10 位不匹配");
        assertFalse(SensitiveDetector.isPhone("138001380000"), "12 位不匹配");
    }

    @Test
    void emailRegexBasic() {
        assertTrue(SensitiveDetector.isEmail("a.b_c@example.com"));
        assertTrue(SensitiveDetector.isEmail("user@sub.domain.cn"));
        assertFalse(SensitiveDetector.isEmail("no-at-sign.com"));
        assertFalse(SensitiveDetector.isEmail("a@b"), "缺顶级域名不匹配");
    }

    @Test
    void usccValidatesCheckDigit() {
        assertTrue(SensitiveDetector.isUscc("91350100M000100Y43"), "正确统一社会信用代码应通过");
        assertFalse(SensitiveDetector.isUscc("91350100M000100Y44"), "错误校验位应拒绝");
        assertFalse(SensitiveDetector.isUscc("91350100M000100Y4"), "非 18 位应拒绝");
    }

    // ========== 列名字典命中 ==========

    @Test
    void detectByColumnNameHitsKeyword() {
        List<DnSensitiveRule> rules = Arrays.asList(
                rule("手机列名", "COLUMN_NAME", "phone,mobile,手机", "PHONE", "重要"),
                rule("邮箱列名", "COLUMN_NAME", "email,mail,邮箱", "EMAIL", "一般"));
        SensitiveDetector.Candidate c = SensitiveDetector.detectByColumnName("user_mobile_no", rules);
        assertNotNull(c, "列名含 mobile 应命中");
        assertEquals("PHONE", c.sensitiveType);
        assertEquals("重要", c.suggestLevel);
        assertTrue(c.hitColumnName);
        assertNull(SensitiveDetector.detectByColumnName("amount", rules), "无关列名不命中");
    }

    // ========== 取值命中（校验位 / 正则） ==========

    @Test
    void detectByValueUsesValidatorHitRate() {
        List<DnSensitiveRule> rules = Arrays.asList(
                rule("银行卡", "VALIDATOR", "BANKCARD", "BANK_CARD", "核心"));
        List<String> samples = Arrays.asList(
                "622260000000000014", "622260000000000014", "abc", "");
        SensitiveDetector.Candidate c = SensitiveDetector.detectByValue(samples, rules);
        assertNotNull(c, "样本中含合法银行卡应命中");
        assertEquals("BANK_CARD", c.sensitiveType);
        // 2/2 非空样本通过校验（空串与非数字应被视为不通过，命中率 2/3 有效值或类似口径）
        assertTrue(c.confidence > 0);
    }

    @Test
    void detectCombinesColumnNameAndValue() {
        List<DnSensitiveRule> rules = Arrays.asList(
                rule("手机列名", "COLUMN_NAME", "phone,mobile", "PHONE", "重要"),
                rule("手机正则", "REGEX", "^1[3-9]\\d{9}$", "PHONE", "重要"));
        SensitiveDetector.Candidate c = SensitiveDetector.detect("contact_phone",
                Arrays.asList("13800138000", "19912345678"), rules);
        assertNotNull(c);
        assertEquals("PHONE", c.sensitiveType);
        // 列名 + 取值双命中，置信度应高于单一信号
        assertTrue(c.confidence >= 90, "双命中置信度应高，实际=" + c.confidence);
    }

    @Test
    void detectReturnsNullWhenNoRuleHits() {
        List<DnSensitiveRule> rules = new ArrayList<>();
        rules.add(rule("手机正则", "REGEX", "^1[3-9]\\d{9}$", "PHONE", "重要"));
        assertNull(SensitiveDetector.detect("amount", Arrays.asList("100.5", "200"), rules));
    }

    private DnSensitiveRule rule(String name, String matchType, String pattern, String type, String level) {
        DnSensitiveRule r = new DnSensitiveRule();
        r.setRuleName(name);
        r.setMatchType(matchType);
        r.setPattern(pattern);
        r.setSensitiveType(type);
        r.setSuggestLevel(level);
        r.setEnabled(1);
        return r;
    }
}
