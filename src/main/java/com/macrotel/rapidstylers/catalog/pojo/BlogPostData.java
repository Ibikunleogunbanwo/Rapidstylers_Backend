package com.macrotel.rapidstylers.catalog.pojo;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlogPostData {
    private String id;
    @NotBlank(message = "Blog title cannot be empty")
    private String title;
    private String category;
    @NotBlank(message = "Blog content cannot be empty")
    private String content;
    private String imageUrl;
    private String author;
}
