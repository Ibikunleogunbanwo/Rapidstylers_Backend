package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.styler.entity.SubServiceEntity;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.styler.repo.SubServiceRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The styler domain's front door for other domains: stylist records, the
 * services each stylist offers and their portfolio photos. Only styler code reaches
 * {@link StylerRepo} and {@link SubServiceRepo} directly.
 */
@Service
public class StylerRecords {

    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    SubServiceRepo subServiceRepo;
    @Autowired
    StylerPortfolioRepo stylerPortfolioRepo;

    public StylerRecords() {
    }

    public StylerRecords(StylerRepo stylerRepo, SubServiceRepo subServiceRepo) {
        this.stylerRepo = stylerRepo;
        this.subServiceRepo = subServiceRepo;
    }

    public Optional<StylerEntity> findByStylerId(String stylerId) {
        return stylerRepo.findByStylerId(stylerId);
    }

    /** Locks the stylist row until the caller's transaction ends. */
    public Optional<StylerEntity> findByStylerIdForUpdate(String stylerId) {
        return stylerRepo.findByStylerIdForUpdate(stylerId);
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

    /** Public portfolio photos in one gallery category. */
    public List<StylerPortfolioEntity> portfolioByCategory(String category) {
        return stylerPortfolioRepo.findByCategory(category);
    }

    /**
     * How many stylist images point at this Cloudinary asset: profile, ID,
     * cover and portfolio photos. Used before an asset is destroyed.
     */
    public long imageReferences(String fragment) {
        return stylerRepo.countByProfileImageUrlContaining(fragment)
                + stylerRepo.countByIdentificationImageUrlContaining(fragment)
                + stylerRepo.countByCoverImageUrlContaining(fragment)
                + stylerPortfolioRepo.countByImageUrlContaining(fragment);
    }

    public Optional<StylerEntity> findByEmailAddress(String emailAddress) {
        return stylerRepo.findByEmailAddress(emailAddress);
    }

    public Optional<StylerEntity> findByPhoneNumber(String phoneNumber) {
        return stylerRepo.findByPhoneNumber(phoneNumber);
    }

    public long count() {
        return stylerRepo.count();
    }
}
