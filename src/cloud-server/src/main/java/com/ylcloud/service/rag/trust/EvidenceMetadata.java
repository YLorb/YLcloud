package com.ylcloud.service.rag.trust;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Bounded source condition data. It is never an authorization or policy-classification override. */
public record EvidenceMetadata(String state, String trust, Map<String,Object> fields) {
    private static final Set<String> ALLOWED = Set.of(
            "effectiveFrom", "effectiveTo", "effectiveDate", "expirationDate", "expiryDate", "expiresAt", "validFrom", "validTo", "validUntil",
            "effective_from", "effective_to", "valid_from", "valid_to", "effective_date", "expiration_date",
            "policyVersion", "documentVersion", "version", "policy_version", "document_version",
            "product", "products", "productId", "productName", "applicableProduct", "applicableProducts",
            "product_id", "product_name", "applicable_product", "applicable_products",
            "region", "regions", "country", "countries", "applicableRegion", "applicableRegions", "jurisdiction",
            "applicable_region", "applicable_regions", "conditions", "condition", "eligibility", "exclusions", "exceptions",
            "applicability", "businessConditions", "business_conditions", "validity", "dateRange", "date_range",
            "start", "end", "from", "to", "required", "value", "values",
            "生效日期", "失效日期", "有效期", "适用产品", "适用地区", "适用条件", "例外条件", "政策版本");
    private static final int MAX_FIELDS = 40;
    private static final int MAX_CHARACTERS = 4000;

    public static EvidenceMetadata from(String raw) {
        return from(raw,MAX_CHARACTERS);
    }

    public static EvidenceMetadata from(String raw,int remainingCharacters) {
        if(raw == null || raw.isBlank()) return new EvidenceMetadata("ABSENT", "UNTRUSTED_SOURCE", Map.of());
        if(remainingCharacters<=0) return new EvidenceMetadata("TRUNCATED", "UNTRUSTED_SOURCE", Map.of());
        if(raw.length() > 65_536) return new EvidenceMetadata("INVALID", "UNTRUSTED_SOURCE", Map.of());
        try {
            JsonNode metadata = TrustedAnswerProtocol.object(raw);
            Budget budget = new Budget(Math.min(MAX_CHARACTERS,remainingCharacters));
            Map<String,Object> filtered = filter(metadata, 0, budget);
            return new EvidenceMetadata(budget.truncated ? "TRUNCATED" : filtered.isEmpty() ? "ABSENT" : "PRESENT",
                    "UNTRUSTED_SOURCE", Collections.unmodifiableMap(filtered));
        } catch(IllegalArgumentException malformed) {
            return new EvidenceMetadata("INVALID", "UNTRUSTED_SOURCE", Map.of());
        }
    }

    private static Map<String,Object> filter(JsonNode object, int depth, Budget budget) {
        Map<String,Object> result = new LinkedHashMap<>();
        if(depth > 3) { budget.truncated = true; return result; }
        object.fields().forEachRemaining(entry -> {
            if(!ALLOWED.contains(entry.getKey())) { if(depth>0) budget.truncated=true; return; }
            if(budget.count >= MAX_FIELDS || budget.remaining <= 0) { budget.truncated = true; return; }
            budget.count++;
            Object value = value(entry.getValue(),depth,budget);
            if(value != null) result.put(entry.getKey(),value);
        });
        return result;
    }

    private static Object value(JsonNode node,int depth,Budget budget) {
        if(depth>3) { budget.truncated=true; return null; }
        if(node.isNull()) return null;
        if(node.isObject()) return filter(node,depth+1,budget);
        if(node.isArray()) {
            List<Object> values=new ArrayList<>();
            for(JsonNode item:node) {
                if(values.size()>=12 || budget.remaining<=0) { budget.truncated=true; break; }
                Object value=value(item,depth+1,budget);
                if(value!=null) values.add(value);
            }
            return values;
        }
        if(node.isTextual() || node.isNumber() || node.isBoolean()) {
            String text=node.asText();
            int limit=Math.min(500,budget.remaining);
            if(text.length()>limit) { text=text.substring(0,limit); budget.truncated=true; }
            budget.remaining-=text.length();
            return text;
        }
        return null;
    }

    private static final class Budget {
        int remaining;
        int count;
        boolean truncated;
        Budget(int remaining) { this.remaining=remaining; }
    }
}
