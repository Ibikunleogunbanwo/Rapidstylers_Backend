package com.macrotel.rapidstylers.payment.service;

import com.macrotel.rapidstylers.payment.entity.CardDetailsEntity;
import com.macrotel.rapidstylers.payment.repo.CardDetailsRepo;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The payment domain's front door for other domains (booking today): a
 * customer's saved card. Only payment code reaches {@link CardDetailsRepo}
 * directly.
 */
@Service
public class CardRecords {

    @Autowired
    CardDetailsRepo cardDetailsRepo;

    public CardRecords() {
    }

    public CardRecords(CardDetailsRepo cardDetailsRepo) {
        this.cardDetailsRepo = cardDetailsRepo;
    }

    public Optional<CardDetailsEntity> findByUserId(String userId) {
        return cardDetailsRepo.findByUserId(userId);
    }
}
