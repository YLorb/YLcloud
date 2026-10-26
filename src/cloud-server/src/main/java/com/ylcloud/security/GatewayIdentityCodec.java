package com.ylcloud.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Version 1 internal request-bound identity envelope; not a client credential or JWT. */
public final class GatewayIdentityCodec {
    public static final String HEADER = "X-YLCloud-Identity";
    public record Identity(long userId, String username, String nickname, String role, boolean owner) {}
    private final byte[] key;
    public GatewayIdentityCodec(String secret) {
        key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("Invalid identity secret");
    }
    public String sign(Identity identity, String method, String target, String credential) {
        Identity value = identity == null ? new Identity(0, "", "", "", false) : identity;
        String payload = String.join("|", "v1", Long.toString(Instant.now().getEpochSecond()),
                hash(method + "\n" + target), hash(credential == null ? "" : credential), Long.toString(value.userId()),
                encode(value.username()), encode(value.nickname()), encode(value.role()), value.owner() ? "1" : "0");
        return payload + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }
    public Identity verify(String envelope, String method, String target, String credential) {
        if (envelope == null || envelope.length() > 4096) throw new IllegalArgumentException("Missing identity");
        String[] p = envelope.split("\\|", -1);
        if (p.length != 10 || !"v1".equals(p[0])) throw new IllegalArgumentException("Invalid identity");
        String payload = envelope.substring(0, envelope.lastIndexOf('|'));
        if (!MessageDigest.isEqual(mac(payload), Base64.getUrlDecoder().decode(p[9]))) throw new IllegalArgumentException("Invalid signature");
        long age = Instant.now().getEpochSecond() - Long.parseLong(p[1]);
        if (age < -5 || age > 15 || !hash(method + "\n" + target).equals(p[2])
                || !hash(credential == null ? "" : credential).equals(p[3])) throw new IllegalArgumentException("Identity expired or request mismatch");
        long id = Long.parseLong(p[4]);
        if (id < 0 || !(p[8].equals("0") || p[8].equals("1"))) throw new IllegalArgumentException("Invalid identity fields");
        return new Identity(id, decode(p[5]), decode(p[6]), decode(p[7]), p[8].equals("1"));
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private byte[] mac(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
