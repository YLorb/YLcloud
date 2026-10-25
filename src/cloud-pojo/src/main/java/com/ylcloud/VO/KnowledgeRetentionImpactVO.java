package com.ylcloud.VO;

import lombok.Data;

@Data
public class KnowledgeRetentionImpactVO {
    private long affectedRevisions;
    private long currentlyAccessible;
    private long configRevision;
}
