package com.ylcloud.workflow.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ylcloud.Exception.BaseException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 所有幂等与确认均使用同一递归有序 JSON 哈希，避免调用端字段顺序影响授权边界。 */
@Component
public class ToolArgumentsCanonicalizer {
    private final ObjectMapper mapper;
    public ToolArgumentsCanonicalizer(ObjectMapper objectMapper) {
        mapper = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public String hash(Map<String, Object> arguments) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(arguments)));
        } catch (Exception exception) { throw new BaseException("Tool 参数无法规范化"); }
    }
}
