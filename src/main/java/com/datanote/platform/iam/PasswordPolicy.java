package com.datanote.platform.iam;

import com.datanote.common.exception.BusinessException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 密码强度策略(创建用户/改密/重置统一调用)。
 * 规则: 至少 8 位; 至少含 字母/数字/符号 三类中的两类; 拒绝常见弱口令。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    /** 常见弱口令黑名单(小写比对)。 */
    private static final Set<String> WEAK = new HashSet<>(Arrays.asList(
            "12345678", "123456789", "1234567890", "password", "admin123", "admin888",
            "qwertyui", "11111111", "00000000", "abcd1234", "passw0rd", "iloveyou", "88888888"
    ));

    /** 校验密码强度, 不达标抛 BusinessException(中文原因)。 */
    public static void validate(String pwd) {
        if (pwd == null || pwd.length() < 8) {
            throw new BusinessException("密码至少 8 位");
        }
        if (pwd.length() > 64) {
            throw new BusinessException("密码过长(最多 64 位)");
        }
        if (WEAK.contains(pwd.toLowerCase())) {
            throw new BusinessException("密码过于常见, 请更换更复杂的密码");
        }
        int kinds = 0;
        if (pwd.matches(".*[a-zA-Z].*")) kinds++;
        if (pwd.matches(".*[0-9].*")) kinds++;
        if (pwd.matches(".*[^a-zA-Z0-9].*")) kinds++;
        if (kinds < 2) {
            throw new BusinessException("密码须包含字母/数字/符号中的至少两类");
        }
    }
}
