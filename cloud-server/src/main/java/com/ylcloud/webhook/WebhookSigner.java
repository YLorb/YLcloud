package com.ylcloud.webhook;

import com.ylcloud.Exception.BaseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class WebhookSigner {
    private WebhookSigner() { }

    public static String sign(String secret,long timestamp,String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(signature);
        } catch(Exception exception) {
            throw new BaseException("Webhook 签名失败");
        }
    }
}
