package com.macrotel.rapidstylers.catalog.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import javax.persistence.*;
import lombok.Data;

/** A copy of one published version, kept so it can be brought back. */
@Data
@Entity
@Table(name = "site_content_versions")
public class SiteContentVersionEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "content_key", nullable = false, length = 120)
    private String contentKey;
    @Column(nullable = false)
    private int version;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String body;
    @Column(name = "published_by", length = 100)
    private String publishedBy;
    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}
