package com.ylcloud.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {
    private static final int BUFFER_SIZE = 8192;
    private static final String SHA_256 = "SHA-256";

    private HashUtil() {
    }

    public static String sha256(String text) {
        return hash(text, SHA_256);
    }

    public static String sha256(byte[] bytes) {
        return hash(bytes, SHA_256);
    }

    public static String sha256(InputStream inputStream) throws IOException {
        return hash(inputStream, SHA_256);
    }

    public static String hash(String text, String algorithm) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return hash(text.getBytes(StandardCharsets.UTF_8), algorithm);
    }

    public static String hash(byte[] bytes, String algorithm) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        MessageDigest digest = newDigest(algorithm);
        return toHex(digest.digest(bytes));
    }

    public static String hash(InputStream inputStream, String algorithm) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }

        MessageDigest digest = newDigest(algorithm);
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, length);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newDigest(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm: " + algorithm, e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
