package com.ylcloud.webhook;

import com.ylcloud.Exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecretAndSignatureTest {
    @Test
    void encryptsAtRestAndDetectsTampering() {
        WebhookSecretCipher cipher = new WebhookSecretCipher("0123456789abcdef0123456789abcdef");
        String encrypted = cipher.encrypt("whsec_test-secret");

        assertTrue(encrypted.startsWith("v1:"));
        assertFalse(encrypted.contains("test-secret"));
        assertEquals("whsec_test-secret",cipher.decrypt(encrypted));
        String replacement = encrypted.endsWith("A") ? "B" : "A";
        assertThrows(BaseException.class,() -> cipher.decrypt(encrypted.substring(0,encrypted.length()-1) + replacement));
    }

    @Test
    void signatureBindsTimestampAndExactBody() {
        String first = WebhookSigner.sign("whsec_secret",1700000000L,"{\"eventId\":\"one\"}");
        String replay = WebhookSigner.sign("whsec_secret",1700000000L,"{\"eventId\":\"one\"}");

        assertEquals(first,replay);
        assertTrue(first.matches("v1=[0-9a-f]{64}"));
        assertNotEquals(first,WebhookSigner.sign("whsec_secret",1700000001L,"{\"eventId\":\"one\"}"));
        assertNotEquals(first,WebhookSigner.sign("whsec_secret",1700000000L,"{\"eventId\":\"two\"}"));
    }
}
