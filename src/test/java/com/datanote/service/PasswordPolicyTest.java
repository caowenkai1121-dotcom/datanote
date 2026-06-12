package com.datanote.platform.iam;

import com.datanote.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PasswordPolicy 密码强度策略单测。
 */
class PasswordPolicyTest {

    @Test
    void rejectsShort() {
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("Ab1"));
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("Abc123"));   // 7位
    }

    @Test
    void rejectsWeakCommon() {
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("admin123"));
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("12345678"));
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("Password".toLowerCase()));
    }

    @Test
    void rejectsSingleCharClass() {
        // 8位但全字母(只一类)
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("abcdefgh"));
        // 8位全数字(只一类, 且非黑名单)
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("90817263"));
    }

    @Test
    void acceptsStrong() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Datanote2026"));   // 字母+数字
        assertDoesNotThrow(() -> PasswordPolicy.validate("my-pass-99"));     // 字母+数字+符号
        assertDoesNotThrow(() -> PasswordPolicy.validate("Abcd!234"));
    }

    @Test
    void rejectsNullAndTooLong() {
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) sb.append("a1");
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate(sb.toString()));
    }
}
