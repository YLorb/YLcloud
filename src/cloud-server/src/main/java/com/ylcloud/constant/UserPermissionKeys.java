package com.ylcloud.constant;

import com.ylcloud.VO.PermissionDefinitionVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UserPermissionKeys {
    public static final String CLOUD_DRIVE = "CLOUD_DRIVE";
    public static final String FILE_UPLOAD = "FILE_UPLOAD";
    public static final String FILE_DOWNLOAD = "FILE_DOWNLOAD";
    public static final String KNOWLEDGE_USE = "KNOWLEDGE_USE";
    public static final String KNOWLEDGE_FILE_ADD = "KNOWLEDGE_FILE_ADD";

    public static final List<PermissionDefinitionVO> DEFINITIONS = List.of(
            new PermissionDefinitionVO(CLOUD_DRIVE, "云盘总权限", "关闭后覆盖全部单项权限，但保留单项配置"),
            new PermissionDefinitionVO(FILE_UPLOAD, "上传与添加文件", "上传、分片上传、创建文件以及上传新版本"),
            new PermissionDefinitionVO(FILE_DOWNLOAD, "下载文件", "下载个人文件、空间文件及历史版本"),
            new PermissionDefinitionVO(KNOWLEDGE_USE, "使用知识库", "浏览、检索、问答和运行知识库处理任务"),
            new PermissionDefinitionVO(KNOWLEDGE_FILE_ADD, "向知识库添加文件", "上传或导入空间文件以及添加网页知识源")
    );
    public static final Set<String> ALL = Set.of(CLOUD_DRIVE, FILE_UPLOAD, FILE_DOWNLOAD, KNOWLEDGE_USE, KNOWLEDGE_FILE_ADD);

    private UserPermissionKeys() { }

    public static Map<String, Boolean> defaults(boolean allowed) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        DEFINITIONS.forEach(definition -> values.put(definition.getKey(), allowed));
        return values;
    }
}
