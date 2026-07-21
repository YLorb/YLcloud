package com.ylcloud.workflow.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ylcloud.workflow.contract.WorkflowContracts.JavaMessageStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowDeliveryAck;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowResult;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunCreateRequest;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowRunStatus;
import com.ylcloud.workflow.contract.WorkflowContracts.WorkflowTerminalCallback;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowContractsTest {

    private static ObjectMapper objectMapper;
    private static Validator validator;
    private static Path repositoryRoot;

    @BeforeAll
    static void setUp() {
        objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        repositoryRoot = findRepositoryRoot();
    }

    @Test
    void sharedExamplesRoundTripThroughJavaDtos() throws IOException {
        assertRoundTrip("workflow-run-create.valid.json", WorkflowRunCreateRequest.class);
        assertRoundTrip("workflow-result.valid.json", WorkflowResult.class);
        assertRoundTrip("workflow-callback.valid.json", WorkflowTerminalCallback.class);
        assertRoundTrip("workflow-delivery-ack.valid.json", WorkflowDeliveryAck.class);
    }

    @Test
    void dtoFieldsMatchAuthoritativeSchemaProperties() throws IOException {
        JsonNode definitions = objectMapper.readTree(
                repositoryRoot.resolve("schemas/workflow-run-contracts.schema.json").toFile()
        ).path("$defs");

        assertRecordMatchesSchema(WorkflowRunCreateRequest.class, definitions.path("WorkflowRunCreateRequest"));
        assertRecordMatchesSchema(WorkflowResult.class, definitions.path("WorkflowResult"));
        assertRecordMatchesSchema(WorkflowTerminalCallback.class, definitions.path("WorkflowTerminalCallback"));
        assertRecordMatchesSchema(WorkflowDeliveryAck.class, definitions.path("WorkflowDeliveryAck"));
        assertRecordMatchesSchema(WorkflowContracts.IntentPlan.class, definitions.path("IntentPlan"));
        assertRecordMatchesSchema(WorkflowContracts.ToolInvokeRequest.class, definitions.path("ToolInvokeRequest"));
        assertRecordMatchesSchema(WorkflowContracts.ToolInvokeResponse.class, definitions.path("ToolInvokeResponse"));
        assertRecordMatchesSchema(WorkflowContracts.SessionDeletionOutboxEvent.class,
                definitions.path("SessionDeletionOutboxEvent"));
        assertRecordMatchesSchema(WorkflowContracts.JavaGenerationRetry.class,
                definitions.path("JavaGenerationRetry"));
        assertRecordMatchesSchema(WorkflowContracts.StatusMapping.class, definitions.path("StatusMapping"));
        assertRecordMatchesSchema(WorkflowContracts.ContractError.class, definitions.path("ContractError"));
    }

    @Test
    void missingRequiredUnknownEnumAndExcessiveBudgetAreRejected() throws IOException {
        JsonNode valid = readExample("workflow-run-create.valid.json");

        JsonNode missing = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("assistantMessageId");
        assertThrows(IOException.class, () -> objectMapper.treeToValue(missing, WorkflowRunCreateRequest.class));

        JsonNode unknown = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("workflowType", "UNKNOWN");
        assertThrows(IOException.class, () -> objectMapper.treeToValue(unknown, WorkflowRunCreateRequest.class));

        JsonNode excessive = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) excessive.path("budget"))
                .put("maxBusinessNodes", 7);
        WorkflowRunCreateRequest excessiveRequest = objectMapper.treeToValue(
                excessive, WorkflowRunCreateRequest.class
        );
        assertFalse(validator.validate(excessiveRequest).isEmpty());
    }

    @Test
    void highRiskToolRequiresConfirmationBoundToUserAndTool() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowContracts.ToolInvokeRequest(
                "1.0",
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "send_email",
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                101,
                201,
                "email.send",
                WorkflowContracts.RiskLevel.HIGH,
                Map.of("to", "sandbox@example.com"),
                null
        ));

        assertThrows(IllegalArgumentException.class, () -> new WorkflowContracts.ToolInvokeResponse(
                "1.0",
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                WorkflowContracts.ToolInvokeResponse.ToolInvokeStatus.FAILED,
                null,
                null
        ));
    }

    @Test
    void workflowResultRejectsFinalAnswerAndMismatchedSnapshotHash() throws IOException {
        JsonNode withFinalAnswer = readExample("workflow-result.valid.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) withFinalAnswer)
                .put("finalAnswer", "Workflow 不得生成最终回答");
        assertThrows(IOException.class, () -> objectMapper.treeToValue(withFinalAnswer, WorkflowResult.class));

        JsonNode mismatch = readExample("workflow-result.valid.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatch.path("snapshotDraft"))
                .put("snapshotHash", "f".repeat(64));
        assertThrows(IOException.class, () -> objectMapper.treeToValue(mismatch, WorkflowResult.class));
    }

    @Test
    void twoLevelStatusMappingIncludesCancelledAsFailed() {
        Map<WorkflowRunStatus, WorkflowContracts.StatusMapping> expected = Map.ofEntries(
                Map.entry(WorkflowRunStatus.QUEUED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.QUEUED, WorkflowRunStatus.QUEUED, false)),
                Map.entry(WorkflowRunStatus.PLANNING,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.RUNNING, WorkflowRunStatus.PLANNING, false)),
                Map.entry(WorkflowRunStatus.VALIDATING,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.RUNNING, WorkflowRunStatus.VALIDATING, false)),
                Map.entry(WorkflowRunStatus.RUNNING,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.RUNNING, WorkflowRunStatus.RUNNING, false)),
                Map.entry(WorkflowRunStatus.SUCCEEDED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.SUCCESS, WorkflowRunStatus.SUCCEEDED, false)),
                Map.entry(WorkflowRunStatus.DEGRADED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.SUCCESS, WorkflowRunStatus.DEGRADED, true)),
                Map.entry(WorkflowRunStatus.FAILED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.FAILED, WorkflowRunStatus.FAILED, false)),
                Map.entry(WorkflowRunStatus.TIMED_OUT,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.FAILED, WorkflowRunStatus.TIMED_OUT, false)),
                Map.entry(WorkflowRunStatus.CANCELLED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.FAILED, WorkflowRunStatus.CANCELLED, false)),
                Map.entry(WorkflowRunStatus.ABANDONED,
                        new WorkflowContracts.StatusMapping(JavaMessageStatus.FAILED, WorkflowRunStatus.ABANDONED, false))
        );

        expected.forEach((status, mapping) -> assertEquals(mapping, WorkflowContracts.mapJavaStatus(status)));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowContracts.StatusMapping(
                JavaMessageStatus.SUCCESS,
                WorkflowRunStatus.CANCELLED,
                false
        ));
    }

    private static <T> void assertRoundTrip(String name, Class<T> type) throws IOException {
        JsonNode input = readExample(name);
        T value = objectMapper.treeToValue(input, type);
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        assertTrue(violations.isEmpty(), violations.toString());
        JsonNode output = objectMapper.valueToTree(value);
        // JSON 对象字段顺序无语义，数字也不区分 int/long；只对数字使用值比较，其余节点保持严格比较。
        Comparator<JsonNode> jsonComparator = (left, right) -> {
            if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue());
            }
            return left.equals(right) ? 0 : 1;
        };
        assertTrue(input.equals(jsonComparator, output), () -> output.toString());
    }

    private static JsonNode readExample(String name) throws IOException {
        return objectMapper.readTree(repositoryRoot.resolve("schemas/examples").resolve(name).toFile());
    }

    private static void assertRecordMatchesSchema(Class<?> recordType, JsonNode schema) {
        Set<String> recordFields = Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        Set<String> schemaFields = new java.util.HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaFields::add);
        assertEquals(schemaFields, recordFields);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("schemas/workflow-run-contracts.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate ylcloud repository root");
    }
}
