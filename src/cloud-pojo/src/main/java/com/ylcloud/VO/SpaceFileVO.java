package com.ylcloud.VO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 空间文件树节点展示对象。
 */
@Data
public class SpaceFileVO {
    private Long id;
    private Long spaceId;
    private String fileUuid;
    private String name;
    private Boolean dir;
    private Long parentId;
    private String path;
    private Long nodeVersion;
    private Integer depth;
    private String lifecycleState;
    private Long createdBy;
    private String type;
    private Long size;
    private Integer versionEnabled;
    private Boolean effectiveVersionEnabled;
    private String knowledgeState;
    private Long knowledgeVersion;
    private Boolean searchable;
    private String lastKnowledgeError;
    private LocalDateTime removedAt;
    private LocalDateTime createtime;
    private LocalDateTime updatetime;
    private List<SpaceFileVO> children;
    private SpaceFileCapabilityVO capability;
}
