package com.ylcloud.service.rag.trust;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.VO.RagAnswerClaim;
import com.ylcloud.VO.RagAnswerEvidence;

import java.util.*;
import java.util.regex.Pattern;

/** Strict model-output protocol. Parsing failures never degrade to free-form text. */
public final class TrustedAnswerProtocol {
    public static final int MAX_CLAIMS = 12;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> QUESTION_TYPES = Set.of("POLICY", "KNOWLEDGE", "MIXED", "UNKNOWN");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z\\d])[-+]?\\d+(?:[.,]\\d+)*(?:%)?");

    private TrustedAnswerProtocol() { }

    public record Draft(boolean refused, List<RagAnswerClaim> claims, List<String> missingConditions) { }
    public record Verdict(List<RagAnswerClaim> accepted, String reasonCode) { }

    public static String classify(String raw) {
        JsonNode node = object(raw);
        fields(node, Set.of("questionType"));
        String type = text(node, "questionType", 16);
        if (!QUESTION_TYPES.contains(type)) throw invalid("unknown question type");
        return type;
    }

    public static Draft draft(String raw, List<RagAnswerEvidence> evidence, String questionType) {
        return draft(raw,evidence,questionType,Map.of());
    }

    public static Draft draft(String raw, List<RagAnswerEvidence> evidence, String questionType,
                              Map<String,EvidenceMetadata> metadata) {
        JsonNode node = object(raw);
        fields(node, Set.of("status", "claims", "missingConditions"));
        String status = text(node, "status", 16);
        if (!Set.of("ANSWERED", "REFUSED").contains(status)) throw invalid("invalid draft status");
        List<String> missing = strings(node, "missingConditions", 12, 300, true);
        JsonNode claimsNode = node.get("claims");
        if (claimsNode == null || !claimsNode.isArray() || claimsNode.size() > MAX_CLAIMS) throw invalid("invalid claims");
        if ("REFUSED".equals(status)) {
            if (!claimsNode.isEmpty()) throw invalid("refusal contains claims");
            return new Draft(true, List.of(), missing);
        }
        if (claimsNode.isEmpty()) throw invalid("answer contains no claims");
        Map<String, RagAnswerEvidence> available = new LinkedHashMap<>();
        evidence.forEach(item -> available.put(item.getEvidenceId(), item));
        List<RagAnswerClaim> claims = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : claimsNode) {
            fields(item, Set.of("claimId", "type", "scope", "text", "evidenceIds", "reasoning", "conditions"));
            RagAnswerClaim claim = new RagAnswerClaim();
            claim.setClaimId(text(item, "claimId", 64));
            if (!claim.getClaimId().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(claim.getClaimId())) throw invalid("duplicate or invalid claim ID");
            claim.setType(text(item, "type", 16));
            if (!Set.of("FACT", "INFERENCE").contains(claim.getType())) throw invalid("invalid claim type");
            String scope = optionalText(item, "scope", 16);
            if (scope.isBlank()) {
                if ("MIXED".equals(questionType)) throw invalid("mixed claim requires explicit scope");
                scope = "KNOWLEDGE".equals(questionType) ? "KNOWLEDGE" : "POLICY";
            }
            if (!Set.of("POLICY", "KNOWLEDGE").contains(scope)) throw invalid("invalid claim scope");
            if (Set.of("POLICY", "UNKNOWN").contains(questionType)) scope = "POLICY";
            claim.setScope(scope);
            if ("POLICY".equals(scope) && "INFERENCE".equals(claim.getType())) throw invalid("policy inference forbidden");
            claim.setText(text(item, "text", 1600));
            claim.setEvidenceIds(strings(item, "evidenceIds", 20, 128, false));
            if (claim.getEvidenceIds().isEmpty() || !available.keySet().containsAll(claim.getEvidenceIds())) throw invalid("unknown or missing evidence");
            claim.setReasoning(optionalText(item, "reasoning", 1600));
            if ("INFERENCE".equals(claim.getType()) && claim.getReasoning().isBlank()) throw invalid("inference has no reasoning");
            claim.setConditions(strings(item, "conditions", 12, 300, true));
            // This catches unsupported literal numbers; entailment still requires the semantic verifier.
            if ("FACT".equals(claim.getType())) {
                String source = claim.getEvidenceIds().stream().map(id -> available.get(id).getContent())
                        .reduce("", (left, right) -> left + "\n" + right);
                source += claim.getEvidenceIds().stream().filter(metadata::containsKey)
                        .map(id -> json(metadata.get(id).fields())).reduce("",(left,right)->left+"\n"+right);
                Set<String> sourceNumbers = numbers(source);
                Set<String> claimedNumbers = numbers(claim.getText() + "\n" + String.join("\n", claim.getConditions()));
                if (!sourceNumbers.containsAll(claimedNumbers)) throw invalid("unsupported literal number");
            }
            claims.add(claim);
        }
        return new Draft(false, List.copyOf(claims), missing);
    }

    public static Verdict judge(String raw, Draft draft) {
        JsonNode node = object(raw);
        fields(node, Set.of("evaluations", "safePartial"));
        JsonNode partial = node.get("safePartial");
        if (partial == null || !partial.isBoolean()) throw invalid("missing partial verdict");
        JsonNode evaluations = node.get("evaluations");
        if (evaluations == null || !evaluations.isArray() || evaluations.size() != draft.claims().size()) throw invalid("incomplete verifier output");
        Map<String, RagAnswerClaim> expected = new LinkedHashMap<>();
        draft.claims().forEach(claim -> expected.put(claim.getClaimId(), claim));
        Map<String, RagAnswerClaim> accepted = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        boolean contradicted = false;
        boolean missingCondition = false;
        for (JsonNode item : evaluations) {
            fields(item, Set.of("claimId", "verdict", "scope", "applicability", "evidenceIds", "reason"));
            String id = text(item, "claimId", 64);
            RagAnswerClaim claim = expected.get(id);
            if (claim == null || !seen.add(id)) throw invalid("unexpected verifier claim ID");
            String verdict = text(item, "verdict", 24);
            if (!Set.of("SUPPORTED", "PLAUSIBLE", "CONTRADICTED", "INSUFFICIENT").contains(verdict)) throw invalid("invalid verdict");
            text(item, "reason", 1600);
            List<String> refs = strings(item, "evidenceIds", 20, 128, false);
            if (!claim.getEvidenceIds().containsAll(refs)) throw invalid("verifier referenced new evidence");
            boolean pass = "FACT".equals(claim.getType()) ? "SUPPORTED".equals(verdict) : "PLAUSIBLE".equals(verdict);
            String verifiedScope = optionalText(item, "scope", 16);
            if (!verifiedScope.isEmpty() && !Set.of("POLICY", "KNOWLEDGE").contains(verifiedScope)) throw invalid("invalid verified scope");
            // A second independent check must agree that an inference is explanatory, not a policy decision.
            if ("INFERENCE".equals(claim.getType()) && !"KNOWLEDGE".equals(verifiedScope)) pass = false;
            String applicability=optionalText(item,"applicability",24);
            if(!applicability.isEmpty() && !Set.of("CONFIRMED","NOT_REQUIRED","UNKNOWN").contains(applicability))
                throw invalid("invalid applicability verdict");
            if("POLICY".equals(claim.getScope()) || "POLICY".equals(verifiedScope)) {
                if(!Set.of("CONFIRMED","NOT_REQUIRED").contains(applicability)) {
                    pass=false;
                    missingCondition=true;
                }
            }
            if (pass) {
                if (refs.isEmpty()) throw invalid("accepted claim lacks supporting evidence");
                // Only expose evidence that the verifier actually accepted for this conclusion.
                claim.setEvidenceIds(refs);
                accepted.put(id, claim);
            }
            contradicted |= "CONTRADICTED".equals(verdict);
        }
        if (accepted.size() != expected.size() && !partial.booleanValue()) accepted.clear();
        List<RagAnswerClaim> ordered = draft.claims().stream().filter(c -> accepted.containsKey(c.getClaimId())).toList();
        return new Verdict(ordered, contradicted ? "CONFLICTING_EVIDENCE" : missingCondition ? "MISSING_CONDITION" : "UNSUPPORTED_CLAIM");
    }

    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw invalid("cannot serialize evidence"); }
    }

    public static JsonNode object(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 65_536) throw invalid("empty or oversized JSON response");
        try (JsonParser parser = JSON.createParser(raw)) {
            JsonNode result = JSON.readTree(parser);
            if (result == null || !result.isObject() || parser.nextToken() != null) throw invalid("expected one JSON object");
            return result;
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw invalid("invalid JSON response"); }
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid("expected JSON object");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid("unexpected field: " + name); });
    }

    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit) throw invalid("invalid " + field);
        return value.textValue().trim();
    }

    private static String optionalText(JsonNode node, String field, int limit) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > limit) throw invalid("invalid " + field);
        return value.textValue().trim();
    }

    private static List<String> strings(JsonNode node, String field, int size, int length, boolean allowMissing) {
        JsonNode value = node.get(field);
        if ((value == null || value.isNull()) && allowMissing) return List.of();
        if (value == null || !value.isArray() || value.size() > size) throw invalid("invalid " + field);
        List<String> result = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.textValue().isBlank() || entry.textValue().length() > length) throw invalid("invalid " + field);
            String item = entry.textValue().trim();
            if (result.contains(item)) throw invalid("duplicate " + field);
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static Set<String> numbers(String text) {
        Set<String> result = new HashSet<>();
        NUMBER.matcher(text).results().forEach(match -> result.add(match.group()));
        return result;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
