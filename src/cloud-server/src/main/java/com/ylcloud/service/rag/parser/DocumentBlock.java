package com.ylcloud.service.rag.parser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentBlock {
    private Integer orderIndex;
    private String type;
    private String text;
    private Integer pageNo;
    private Integer level;
    private List<String> headingPath = new ArrayList<>();
    private String bbox;
    private Double confidence;
    private String source;
}
