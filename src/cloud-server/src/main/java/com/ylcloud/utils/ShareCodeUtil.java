package com.ylcloud.utils;

import java.security.SecureRandom;

public class ShareCodeUtil {
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int DEFAULT_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareCodeUtil() {
    }

    /**
     * 生成默认长度的分享码。
     *
     * @return 由大小写字母和数字组成的分享码
     */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /**
     * 生成指定长度的分享码。
     *
     * @param length 分享码长度
     * @return 由大小写字母和数字组成的分享码
     */
    public static String generate(int length) {
        StringBuilder code = new StringBuilder(length);
        for(int i = 0; i < length; i++) {
            code.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return code.toString();
    }
}
