package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Data
@Entity
@Table(name = "user_feedbacks")
public class FeedbackEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String emailAddress;
    private String feedBackType;
    private String message;
    private String userId;
    private String insertedDt;

    public FeedbackEntity(){
        this.insertedDt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM dd, yyyy"));
    }
}
