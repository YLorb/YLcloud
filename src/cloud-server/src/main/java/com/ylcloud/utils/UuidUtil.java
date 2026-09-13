package com.ylcloud.utils;

import java.util.UUID;

public class UuidUtil {

    private UuidUtil() {
    }

    public static String randomUuid() {
        return UUID.randomUUID().toString();
    }

    public static String randomSimpleUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
