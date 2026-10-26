package com.ylcloud.gateway;

import java.util.Set;
import org.springframework.stereotype.Component;

/** Explicit operation allowlist. New Open API operations require an explicit policy. */
@Component
public class ApiKeyRoutePolicy {
    public record Rule(Set<String> anyScope, boolean write) {}
    public static boolean openApi(String path) {
        return path.equals("/api/v1") || path.startsWith("/api/v1/") || path.equals("/api/open") || path.startsWith("/api/open/");
    }
    public Rule resolve(String method,String path) {
        String suffix=path.startsWith("/api/v1") ? path.substring(7) : path.substring(9);
        String verb=method.equals("HEAD") ? "GET" : method;
        if(verb.equals("GET") && Set.of("/capabilities","/usage","/usage/records").contains(suffix)) return rule(false);
        if(verb.equals("GET") && suffix.equals("/files")) return rule(false,"DRIVE_READ");
        if(verb.equals("POST") && Set.of("/files/upload","/files/folders").contains(suffix)) return rule(true,"DRIVE_WRITE");
        if(verb.equals("PUT") && suffix.matches("/files/[1-9][0-9]*/move")) return rule(true,"DRIVE_WRITE");
        if(verb.equals("DELETE") && suffix.matches("/files/[1-9][0-9]*")) return rule(true,"DRIVE_WRITE");
        if(verb.equals("GET") && suffix.equals("/spaces")) return rule(false,"KNOWLEDGE_RETRIEVE","KNOWLEDGE_AGENT");
        if(verb.equals("POST") && (suffix.matches("/spaces/[1-9][0-9]*/knowledge/query") || suffix.equals("/assistant/sessions")
                || suffix.matches("/assistant/sessions/[1-9][0-9]*/queries"))) return rule(true,"KNOWLEDGE_RETRIEVE");
        if(verb.equals("POST") && suffix.matches("/agent/sessions/[1-9][0-9]*/queries")) return rule(true,"KNOWLEDGE_AGENT");
        if(verb.equals("GET") && suffix.matches("/assistant/sessions/[1-9][0-9]*/queries/[1-9][0-9]*")) return rule(false,"KNOWLEDGE_RETRIEVE","KNOWLEDGE_AGENT");
        if(verb.equals("GET") && suffix.matches("/agent/sessions/[1-9][0-9]*/queries/[1-9][0-9]*")) return rule(false,"KNOWLEDGE_AGENT");
        return null;
    }
    private Rule rule(boolean write,String... scopes) { return new Rule(Set.of(scopes),write); }
}
