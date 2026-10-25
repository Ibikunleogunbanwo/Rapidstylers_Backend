package com.macrotel.rapidstylers.styler.entity;

import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.styler.pojo.SubServiceData;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "sub_services")
public class SubServiceEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String stylerId;
    private String name;
    private String price;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    private String status;
    private String createdAt;
    /** The price is only a starting point: the service is booked through a custom quote. */
    @Column(name = "price_on_quote", nullable = false)
    private boolean priceOnQuote;

    public SubServiceEntity() {
    }

    public SubServiceEntity(SubServiceData subServiceData){
        AppUtils appUtils = new AppUtils();
        this.stylerId = subServiceData.getStylerId();
        this.name = AppUtils.sanitizeText(subServiceData.getName());
        this.price =  appUtils.currencyFormat(subServiceData.getPrice());
        this.durationMinutes = subServiceData.getDurationMinutes() == null
                ? com.macrotel.rapidstylers.config.AppConstants.DEFAULT_SERVICE_DURATION_MINUTES
                : subServiceData.getDurationMinutes();
        this.priceOnQuote = Boolean.TRUE.equals(subServiceData.getPriceOnQuote());
        this.status= "0";
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy HH:mm:ss"));
    }
}
