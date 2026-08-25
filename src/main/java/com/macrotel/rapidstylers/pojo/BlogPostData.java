package com.macrotel.rapidstylers.pojo;

import lombok.Data;

import javax.validation.constraints.NotEmpty;

@Data
public class BlogPostData {
    private String id;
    @NotEmpty(message = "Blog title cannot be empty")
    private String title;
    private String category;
    private String content;
    private String imageUrl;
    private String author;
}
