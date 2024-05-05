package com.macrotel.rapidstylers.entity;

import com.macrotel.rapidstylers.pojo.StylerPortfolioData;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "portfolios")
public class StylerPortfolioEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    private String imageUrl;
    private String name;
    private String status;
    private String createdAt;

    public StylerPortfolioEntity() {
    }

    public StylerPortfolioEntity(StylerPortfolioData stylerPortfolioData) {
        this.stylerId = stylerPortfolioData.getStylerId();
        this.imageUrl = stylerPortfolioData.getImageUrl();
        this.name = stylerPortfolioData.getName();
        this.status = "0";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    }

}
