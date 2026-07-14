package com.ylcloud.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ylcloud.VO.FileVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileVoJsonContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesDirectoryFlagAsIsDirOnly() throws Exception {
        FileVO file = new FileVO();
        file.setDir(true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(file));

        assertTrue(json.get("isDir").asBoolean());
        assertFalse(json.has("dir"));
    }
}
