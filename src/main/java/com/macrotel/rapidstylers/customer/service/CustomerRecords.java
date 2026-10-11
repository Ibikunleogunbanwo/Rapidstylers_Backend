package com.macrotel.rapidstylers.customer.service;

import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The customer domain's front door for other domains (payment today). Only
 * customer code reaches {@link UserRepo} directly.
 */
@Service
public class CustomerRecords {

    @Autowired
    UserRepo userRepo;

    public CustomerRecords() {
    }

    public CustomerRecords(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public Optional<UserEntity> findByUserId(String userId) {
        return userRepo.findByUserId(userId);
    }
}
