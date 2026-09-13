package com.ylcloud.webhook;

import com.ylcloud.Exception.BaseException;
import com.ylcloud.service.SiteSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class WebhookTargetPolicy {
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private final SiteSettingService settings;
    private final HostResolver resolver;

    @Autowired
    public WebhookTargetPolicy(SiteSettingService settings) {
        this(settings,InetAddress::getAllByName);
    }

    WebhookTargetPolicy(SiteSettingService settings,HostResolver resolver) {
        this.settings = settings;
        this.resolver = resolver;
    }

    public URI requireAllowed(String target) {
        return resolveAllowed(target).uri();
    }

    public ResolvedTarget resolveAllowed(String target) {
        try {
            URI uri = URI.create(target).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if(!"http".equals(scheme) && !"https".equals(scheme)) throw rejected();
            if(uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) throw rejected();
            boolean privateAllowed = settings.getBoolean("webhook.allowPrivateTargets",false);
            InetAddress[] resolved = resolver.resolve(uri.getHost());
            if(resolved == null || resolved.length == 0) throw rejected();
            for(InetAddress address : resolved) {
                if(alwaysRejected(address) || (!privateAllowed && privateAddress(address))) throw rejected();
            }
            return new ResolvedTarget(uri,List.copyOf(Arrays.asList(resolved)));
        } catch(BaseException exception) {
            throw exception;
        } catch(Exception exception) {
            throw new TargetResolutionException(exception);
        }
    }

    private boolean alwaysRejected(InetAddress value) {
        return value.isAnyLocalAddress() || value.isMulticastAddress();
    }

    private boolean privateAddress(InetAddress value) {
        if(value.isLoopbackAddress() || value.isLinkLocalAddress() || value.isSiteLocalAddress()) return true;
        byte[] bytes = value.getAddress();
        if(bytes.length == 4) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            return first == 0 || first == 10 || first == 127 || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168)
                    || (first == 100 && second >= 64 && second <= 127);
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private BaseException rejected() {
        return new BaseException(400,"Webhook 目标被 SSRF 安全策略拒绝");
    }

    public record ResolvedTarget(URI uri,List<InetAddress> addresses) { }

    public static final class TargetResolutionException extends BaseException {
        TargetResolutionException(Throwable cause) {
            super(400,"Webhook 目标无法安全解析",cause);
        }
    }
}
