package com.macrotel.rapidstylers.booking.pojo;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

/** A message on a quote. Who sent it comes from the token. */
@Data
public class QuoteMessageData {
    @NotBlank(message = "Quote id is required")
    private String quoteRef;
    @NotBlank(message = "Write a message")
    @Size(max = 500, message = "Keep the message under 500 characters")
    private String body;
}
