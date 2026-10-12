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

    /**
     * The email of the admin account an audit row's actor id names, or null.
     * Admin tokens carry the account's email as their subject, so that is the
     * usual actor id; a numeric id (the account's row id) is accepted as well.
     */
    public String emailForActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return null;
        }
        String id = actorId.trim();
        if (adminAccountRepo == null) {
            return id.contains("@") ? id : null;
        }
        Optional<AdminAccountEntity> account = id.matches("\\d{1,18}")
                ? adminAccountRepo.findById(Long.parseLong(id))
                : adminAccountRepo.findByEmailIgnoreCase(id);
        return account.map(AdminAccountEntity::getEmail).orElse(id.contains("@") ? id : null);
    }
}
