package com.macrotel.rapidstylers.customer.dto;

import com.macrotel.rapidstylers.payment.dto.CardDetailsDTO;
import lombok.Data;

@Data
public class UserDataDTO {
    private UserAccountDTO userData;
    private CardDetailsDTO userCardData;
}
