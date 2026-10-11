package com.macrotel.rapidstylers.admin.service;

import com.macrotel.rapidstylers.admin.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.admin.repo.AdminAccountRepo;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The admin domain's front door for other domains (auth's step-up check):
 * admin accounts. Only admin code reaches {@link AdminAccountRepo} directly.
 */
@Service
public class AdminRecords {

    @Autowired
    AdminAccountRepo adminAccountRepo;

    public AdminRecords() {
    }

    public AdminRecords(AdminAccountRepo adminAccountRepo) {
        this.adminAccountRepo = adminAccountRepo;
    }

    public Optional<AdminAccountEntity> findByEmailIgnoreCase(String email) {
        return adminAccountRepo.findByEmailIgnoreCase(email);
    }
}
