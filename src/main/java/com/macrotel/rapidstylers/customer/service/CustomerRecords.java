package com.macrotel.rapidstylers.customer.service;

import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Locale;
import javax.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * The customer domain's front door for other domains. Only
 * customer code reaches {@link UserRepo} directly.
 */
@Service
public class CustomerRecords {

    @Autowired
    UserRepo userRepo;
    @Autowired
    SavedStylistRepo savedStylistRepo;

    public CustomerRecords() {
    }

    public CustomerRecords(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public CustomerRecords(UserRepo userRepo, SavedStylistRepo savedStylistRepo) {
        this.userRepo = userRepo;
        this.savedStylistRepo = savedStylistRepo;
    }

    public Optional<UserEntity> findByUserId(String userId) {
        return userRepo.findByUserId(userId);
    }

    public Optional<UserEntity> findByEmailAddress(String emailAddress) {
        return userRepo.findByEmailAddress(emailAddress);
    }

    public Optional<UserEntity> findByPhoneNumber(String phoneNumber) {
        return userRepo.findByPhoneNumber(phoneNumber);
    }

    public UserEntity save(UserEntity user) {
        return userRepo.save(user);
    }

    /** Ids of active customer accounts, for admin announcements. */
    public List<String> findActiveUserIds() {
        return userRepo.findActiveUserIds();
    }

    /** Every customer's saved stylists, for "a stylist you saved changed" notices. */
    public List<SavedStylistEntity> allSavedStylists() {
        return savedStylistRepo.findAll();
    }

    public long count() {
        return userRepo.count();
    }

    /**
     * One page of customer accounts for the admin. {@code text} matches part of
     * the email, first or last name, phone number or customer id (any case);
     * {@code status} is the stored status ("0" active, "SUSPENDED").
     */
    public Page<UserEntity> search(String text, String status, Pageable pageable) {
        Specification<UserEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (text != null && !text.isBlank()) {
                String like = "%" + text.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("emailAddress")), like),
                        cb.like(cb.lower(root.get("firstname")), like),
                        cb.like(cb.lower(root.get("lastname")), like),
                        cb.like(cb.lower(root.get("phoneNumber")), like),
                        cb.like(cb.lower(root.get("userId")), like)));
            }
            if (status != null && !status.isBlank()) where.add(cb.equal(root.get("status"), status));
            return cb.and(where.toArray(new Predicate[0]));
        };
        return userRepo.findAll(spec, pageable);
    }
}
