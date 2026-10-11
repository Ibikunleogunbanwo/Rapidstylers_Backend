package com.macrotel.rapidstylers.customer.service;

import com.macrotel.rapidstylers.customer.entity.SavedStylistEntity;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.SavedStylistRepo;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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
}
