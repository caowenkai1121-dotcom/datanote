package com.datanote.util;

import com.datanote.common.util.CryptoUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilTest {

    private static final String KEY = "1234567890abcdef"; // 16 字符密钥

    @Test
    void encryptThenDecrypt_shouldReturnOriginalText() {
        String plainText = "Hello, DataNote!";
        String encrypted = CryptoUtil.encrypt(plainText, KEY);
        String decrypted = CryptoUtil.decrypt(encrypted, KEY);
        assertEquals(plainText, decrypted);
    }

    @Test
    void encryptThenDecrypt_emptyString() {
        String plainText = "";
        String encrypted = CryptoUtil.encrypt(plainText, KEY);
        String decrypted = CryptoUtil.decrypt(encrypted, KEY);
        assertEquals(plainText, decrypted);
    }

    @Test
    void encryptThenDecrypt_chineseText() {
        String plainText = "中文测试数据";
        String encrypted = CryptoUtil.encrypt(plainText, KEY);
        String decrypted = CryptoUtil.decrypt(encrypted, KEY);
        assertEquals(plainText, decrypted);
    }

    @Test
    void differentPlainTexts_shouldProduceDifferentCipherTexts() {
        String encrypted1 = CryptoUtil.encrypt("textA", KEY);
        String encrypted2 = CryptoUtil.encrypt("textB", KEY);
        assertNotEquals(encrypted1, encrypted2);
    }

    @Test
    void samePlainText_encryptedTwice_shouldDifferDueToRandomIV() {
        String plainText = "same input";
        String encrypted1 = CryptoUtil.encrypt(plainText, KEY);
        String encrypted2 = CryptoUtil.encrypt(plainText, KEY);
        assertNotEquals(encrypted1, encrypted2, "两次加密应产生不同密文（随机 IV）");

        // 但解密后都应得到原文
        assertEquals(plainText, CryptoUtil.decrypt(encrypted1, KEY));
        assertEquals(plainText, CryptoUtil.decrypt(encrypted2, KEY));
    }

    @Test
    void decryptWithWrongKey_shouldNotRecoverPlaintext() {
        String encrypted = CryptoUtil.encrypt("secret", KEY);
        String wrongKey = "fedcba0987654321";
        // AES/CBC 错误密钥绝大多数抛 BadPadding, 但约 1/256 概率 padding 偶然合法而不抛(原断言因此偶发失败/flaky)。
        // 测真正的安全不变式: 错误密钥要么抛异常, 要么也绝不能还原出明文。
        try {
            String result = CryptoUtil.decrypt(encrypted, wrongKey);
            assertNotEquals("secret", result);
        } catch (RuntimeException expected) {
            // 抛异常即满足
        }
    }

    @Test
    void decryptWithCorruptedCipherText_shouldThrow() {
        assertThrows(Exception.class, () -> CryptoUtil.decrypt("not-valid-base64!!", KEY));
    }
}
