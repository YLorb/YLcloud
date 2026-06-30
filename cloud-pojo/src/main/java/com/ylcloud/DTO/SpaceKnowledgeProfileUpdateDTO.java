package com.ylcloud.DTO;

import lombok.Data;

import java.util.List;

@Data
public class SpaceKnowledgeProfileUpdateDTO {
    private String title;
    private String summary;
    private String category;
    private List<String> tags;
    private List<String> keywords;
    private List<String> questions;
    private String profileStatus;
}
