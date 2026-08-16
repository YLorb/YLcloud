package com.ylcloud.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinioclientUtilDigestTest {
    @Test
    void calculatesAllMultipartAcceptanceDigestsInOnePass() throws Exception {
        byte[] content = "multipart-fault-injection".getBytes(StandardCharsets.UTF_8);

        MinioclientUtil.ObjectDigests result = MinioclientUtil.calculateDigests(new ByteArrayInputStream(content));

        assertEquals("c81576d5a0da7c16d4fd5e6df3b5df2f",result.md5());
        assertEquals("1455665acd09ff379a950633763b8724d06772cd",result.sha1());
        assertEquals("18c1ea7189c7e4c4580aba0170dad84960c7338c05d9db25e1d76de8a9378762",result.sha256());
    }
}
