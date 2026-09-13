package com.ylcloud.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class SiteSettingUpdateDTO {
    @Valid
    @NotEmpty
    private List<Item> settings;

    @Data
    public static class Item {
        @NotBlank
        private String key;

        private String value;
    }
}
