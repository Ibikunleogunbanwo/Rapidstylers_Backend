package com.macrotel.rapidstylers.catalog.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "blog_posts")
public class BlogPostEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String title;
    private String category;
    @Column(columnDefinition = "TEXT")
    private String content;
    @Column(length = 2048)
    private String imageUrl;
    private String author;
    private String insertedDt;
    private String updatedDt;
    /** {@link #DRAFT} (admin only) or {@link #PUBLISHED} (on the public site). */
    private String status;

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    public BlogPostEntity() {
        this.status = PUBLISHED;
        this.insertedDt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        this.updatedDt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
    }

    /** Whether the public site may show it. Only a draft is hidden. */
    public boolean isPublished() {
        return !DRAFT.equals(status);
    }
}
