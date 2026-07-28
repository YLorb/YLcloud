package com.ylcloud.webhook;

import com.sun.net.httpserver.HttpServer;
import com.ylcloud.Exception.BaseException;
import com.ylcloud.service.SiteSettingService;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookTargetPolicyTest {
    @Test
    void blocksPrivateTargetsUnlessExplicitlyEnabled() throws Exception {
        SiteSettingService settings = mock(SiteSettingService.class);
        WebhookTargetPolicy policy = new WebhookTargetPolicy(settings,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        when(settings.getBoolean("webhook.allowPrivateTargets",false)).thenReturn(false,true);

        assertThrows(BaseException.class,() -> policy.requireAllowed("http://hook.example/test"));
        assertDoesNotThrow(() -> policy.requireAllowed("http://hook.example/test"));
    }

    @Test
    void detectsDnsRebindingOnEveryResolution() throws Exception {
        SiteSettingService settings = mock(SiteSettingService.class);
        when(settings.getBoolean("webhook.allowPrivateTargets",false)).thenReturn(false);
        AtomicInteger resolutions = new AtomicInteger();
        WebhookTargetPolicy policy = new WebhookTargetPolicy(settings,host -> new InetAddress[]{
                InetAddress.getByName(resolutions.getAndIncrement() == 0 ? "8.8.8.8" : "127.0.0.1")
        });

        policy.requireAllowed("https://hook.example/events");
        assertThrows(BaseException.class,() -> policy.requireAllowed("https://hook.example/events"));
    }

    @Test
    void rechecksRedirectDestinationBeforeFollowing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/start",exchange -> {
            exchange.getResponseHeaders().add("Location","http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302,-1);
            exchange.close();
        });
        server.start();
        try {
            SiteSettingService settings = mock(SiteSettingService.class);
            when(settings.getBoolean("webhook.allowPrivateTargets",false)).thenReturn(true);
            WebhookTargetPolicy policy = new WebhookTargetPolicy(settings,host -> new InetAddress[]{
                    InetAddress.getByName("127.0.0.1".equals(host) ? "127.0.0.1" : "224.0.0.1")
            });
            WebhookHttpClient client = new WebhookHttpClient(policy);

            assertThrows(BaseException.class,() -> client.post(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/start","{}",Map.of(),Duration.ofSeconds(3)));
        } finally {
            server.stop(0);
        }
    }
}
