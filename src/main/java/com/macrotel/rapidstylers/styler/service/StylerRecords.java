package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The styler domain's front door for other domains (payment today): stylist
 * records and the services each stylist offers. Only styler code reaches
 * {@link StylerRepo} and {@link SubServiceRepo} directly.
 */
@Service
public class StylerRecords {

    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;

    public StylerRecords() {
    }

    public StylerRecords(StylerRepo stylerRepo, SubServiceRepo subServiceRepo) {
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
    }

    public Optional<StylerEntity> findByStylerId(String stylerId) {
        return stylerRepo.findByStylerId(stylerId);
    }

    public Optional<StylerEntity> findByStripeConnectAccountId(String stripeConnectAccountId) {
        return stylerRepo.findByStripeConnectAccountId(stripeConnectAccountId);
    }

    public List<StylerEntity> findAll() {
        return stylerRepo.findAll();
    }

    public StylerEntity save(StylerEntity styler) {
        return stylerRepo.save(styler);
    }

    /** One of this stylist's services, if it exists. */
    public Optional<SubServiceEntity> findSubService(String stylerId, Long subServiceId) {
        return subServiceRepo.isServiceExistById(stylerId, subServiceId);
    }

    public Optional<SubServiceEntity> findSubServiceById(Long subServiceId) {
        return subServiceRepo.findById(subServiceId);
    }

    public List<SubServiceEntity> subServicesOf(String stylerId) {
        return subServiceRepo.findByStylerId(stylerId);
    }
}
