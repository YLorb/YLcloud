package com.ylcloud.webhook;

import com.ylcloud.Exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebhookSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public WebhookSecretCipher(@Value("${ylcloud.webhook.secret-key:${ylcloud.jwt.secret}}") String masterSecret) {
        try {
            if(masterSecret == null || masterSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("webhook secret key must contain at least 32 bytes");
            }
            byte[] material = MessageDigest.getInstance("SHA-256")
                    .digest(("ylcloud:webhook:" + masterSecret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(material,"AES");
        } catch(IllegalArgumentException exception) {
            throw exception;
        } catch(Exception exception) {
            throw new IllegalStateException("Unable to initialize webhook secret cipher",exception);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce,0,combined,0,nonce.length);
            System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch(Exception exception) {
            throw new BaseException("Webhook Secret 加密失败");
        }
    }

    public String decrypt(String ciphertext) {
        try {
            if(ciphertext == null || !ciphertext.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] combined = Base64.getUrlDecoder().decode(ciphertext.substring(3));
            if(combined.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOfRange(combined,0,12);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined,12,combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8);
        } catch(Exception exception) {
            throw new BaseException("Webhook Secret 无法解密");
        }
    }
}
