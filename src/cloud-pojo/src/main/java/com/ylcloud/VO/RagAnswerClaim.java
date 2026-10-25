package com.ylcloud.VO;

import lombok.Data;
import java.util.List;

/** A conclusion accepted by the server's structured answer verifier. */
@Data
public class RagAnswerClaim {
    private String claimId;
    private String type;
    private String scope;
    private String text;
    private List<String> evidenceIds = List.of();
    private String reasoning;
    private List<String> conditions = List.of();
}
