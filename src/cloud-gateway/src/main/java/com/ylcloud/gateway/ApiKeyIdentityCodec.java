package com.ylcloud.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Internal request assertion; never exposed as a client credential. Domain-separated from Session. */
public final class ApiKeyIdentityCodec {
    public static final String HEADER = "X-YLCloud-Api-Key-Identity";
    public record Identity(long keyId, long userId, String requestId) {}
    private final byte[] key;
    public ApiKeyIdentityCodec(String secret) {
        key=secret.getBytes(StandardCharsets.UTF_8);
        if(key.length<32) throw new IllegalArgumentException("Invalid identity secret");
    }
    public String sign(long keyId,long userId,String requestId,String method,String target) {
        String payload=String.join("|","ak1",Long.toString(Instant.now().getEpochSecond()),
                Long.toString(keyId),Long.toString(userId),requestId,GatewayIdentityCodec.hash(method+"\n"+target));
        return payload+"|"+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }
    public Identity verify(String assertion,String requestId,String method,String target) {
        if(assertion==null || assertion.length()>1024) throw new IllegalArgumentException("Invalid API identity");
        String[] values=assertion.split("\\|",-1);
        if(values.length!=7 || !values[0].equals("ak1")) throw new IllegalArgumentException("Invalid API identity");
        String payload=assertion.substring(0,assertion.lastIndexOf('|'));
        if(!MessageDigest.isEqual(mac(payload),Base64.getUrlDecoder().decode(values[6]))) throw new IllegalArgumentException("Invalid signature");
        long age=Instant.now().getEpochSecond()-Long.parseLong(values[1]);
        long keyId=Long.parseLong(values[2]),userId=Long.parseLong(values[3]);
        if(age < -5 || age > 15 || keyId<=0 || userId<=0 || !values[4].equals(requestId)
                || !values[4].matches("[a-f0-9-]{36}") || !values[5].equals(GatewayIdentityCodec.hash(method+"\n"+target)))
            throw new IllegalArgumentException("API identity expired or request mismatch");
        return new Identity(keyId,userId,values[4]);
    }
    private byte[] mac(String value) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
