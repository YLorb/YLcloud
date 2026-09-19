package com.ylcloud.share;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShareCryptoTest {
    final ShareCrypto crypto=new ShareCrypto("test-only-secret-with-at-least-32-bytes");
    @Test void shortCodeUsesSpecifiedAlphabetPaddingAndTruncation() {
        assertEquals("11aaaaaa",ShareCrypto.code(1,1));
        assertEquals("Z10aaaaa",ShareCrypto.code(61,62));
        assertEquals((ShareCrypto.base62(Long.MAX_VALUE)+ShareCrypto.base62(4)).substring(0,8),ShareCrypto.code(Long.MAX_VALUE,4));
        assertEquals(ShareCrypto.code(1,63),ShareCrypto.code(63,1)); // Accepted ambiguity, DB must reject.
    }
    @Test void saltedHashesSupportLongUnicodePasswordsWithoutTruncation() {
        String password="密码".repeat(100);
        String a=crypto.hash(password),b=crypto.hash(password);
        assertNotEquals(a,b);assertTrue(crypto.matches(password,a));
        assertFalse(crypto.matches(password+"x",a));assertFalse(crypto.matches(null,a));
    }
    @Test void credentialsBindPurposeCodeVersionAndSignature() {
        String token=crypto.token("11aaaaaa",5,1,"download");
        assertEquals(5,((Number)crypto.verify(token,"11aaaaaa",1,"download").get("visit")).intValue());
        assertThrows(ShareModels.Expired.class,()->crypto.verify(token,"11aaaaaa",2,"download"));
        assertThrows(ShareModels.Expired.class,()->crypto.verify(token,"11aaaaaa",1,"visit"));
        assertThrows(ShareModels.Expired.class,()->crypto.verify(token,"22aaaaaa",1,"download"));
        assertThrows(ShareModels.Expired.class,()->crypto.verify(token+"broken","11aaaaaa",1,"download"));
    }
    @Test void zipNamesCannotCreateTraversalEntries() {
        assertEquals("file",ShareLinkService.safeName(".."));
        assertEquals(".._secret",ShareLinkService.safeName("../secret"));
        assertFalse(ShareLinkService.safeName("C:\\secret").contains("\\"));
        assertFalse(ShareLinkService.safeName("C:\\secret").contains(":"));
    }
}
