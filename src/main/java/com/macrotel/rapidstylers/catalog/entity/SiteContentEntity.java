package com.macrotel.rapidstylers.catalog.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** One editable piece of the website: a draft the admin works on and the published copy visitors see. */
@Data
@Entity
@Table(name = "site_content")
public class SiteContentEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** "faq", "homepage", "seo:/some/path" or "email:template_name". */
    @Column(name = "content_key", nullable = false, length = 120, unique = true)
    private String contentKey;
    /** FAQ, HOMEPAGE, SEO or EMAIL. */
    @Column(nullable = false, length = 20)
    private String kind;
    @Column(name = "draft_body", columnDefinition = "MEDIUMTEXT")
    private String draftBody;
    @Column(name = "published_body", columnDefinition = "MEDIUMTEXT")
    private String publishedBody;
    /** How many times it has been published; 0 until the first publish. */
    @Column(nullable = false)
    private int version;
    @Column(name = "draft_updated_by", length = 100)
    private String draftUpdatedBy;
    @Column(name = "draft_updated_at")
    private LocalDateTime draftUpdatedAt;
    @Column(name = "published_by", length = 100)
    private String publishedBy;
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
