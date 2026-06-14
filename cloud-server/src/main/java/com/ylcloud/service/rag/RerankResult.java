package com.ylcloud.service.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RerankResult {
    private Integer index;
    private Double score;
}
