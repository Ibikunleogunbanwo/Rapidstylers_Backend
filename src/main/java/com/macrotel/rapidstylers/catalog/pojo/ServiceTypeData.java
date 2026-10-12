package com.macrotel.rapidstylers.catalog.pojo;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ServiceTypeData {
    // Length, characters and duplicates are checked on the trimmed name in
    // CatalogService (see CatalogRules), so the message can say what is wrong.
    @NotBlank(message = "Service name cannot be empty")
    private String serviceName;
    private String id;
    private String imageUrl;
    private String description;
}
