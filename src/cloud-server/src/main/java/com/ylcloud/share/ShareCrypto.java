package com.ylcloud.share;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class ShareCrypto {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int ITERATIONS = 210000;
    private final SecretKey key;
    public ShareCrypto(@Value("${ylcloud.jwt.secret}") String secret) {
        // Domain separation: these credentials cannot be used as login tokens.
        try { key = Keys.hmacShaKeyFor(MessageDigest.getInstance("SHA-256")
                .digest(("share-link-v1:" + secret).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String base62(long n) {
        if (n < 1) throw new IllegalArgumentException("ID 必须为正数");
        StringBuilder b = new StringBuilder();
        while (n > 0) { b.append(ALPHABET.charAt((int)(n % 62))); n /= 62; }
        return b.reverse().toString();
    }
    public static String code(long id, long userId) {
        return (base62(id) + base62(userId) + "aaaaaaaa").substring(0, 8);
    }
    public String hash(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    public boolean matches(String password, String hash) {
        if (password == null || hash == null) return false;
        String[] p = hash.split(":");
        return MessageDigest.isEqual(Base64.getDecoder().decode(p[2]),
                derive(password, Base64.getDecoder().decode(p[1]), Integer.parseInt(p[0])));
    }
    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
    public String token(String code, long visit, long version, String purpose) {
        return Jwts.builder().setAudience("share-link").setSubject(code)
                .claim("visit",visit).claim("version",version).claim("purpose",purpose)
                .setExpiration(new Date(System.currentTimeMillis()+900_000))
                .signWith(key).compact();
    }
    public Claims verify(String token, String code, long version, String purpose) {
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(key).requireAudience("share-link").build()
                    .parseClaimsJws(token).getBody();
            if (!code.equals(c.getSubject()) || ((Number)c.get("version")).longValue()!=version ||
                    !purpose.equals(c.get("purpose"))) throw new ShareModels.Expired();
            return c;
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new ShareModels.Expired();
        }
    }
}
