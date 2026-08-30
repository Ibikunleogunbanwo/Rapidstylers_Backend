package com.macrotel.rapidstylers.config;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the bootstrap admin from ADMIN_EMAIL / ADMIN_PASSWORD the first time
 * the app starts with an empty admin_accounts table, so the environment-based
 * credentials keep working after the move to database-backed admins. No-op if
 * the env vars are unset or an admin with that email already exists.
 */
@Component
public class AdminAccountInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final AdminAccountRepo adminAccountRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    public AdminAccountInitializer(AdminAccountRepo adminAccountRepo, PasswordEncoder passwordEncoder) {
        this.adminAccountRepo = adminAccountRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.trim().isEmpty()
                || adminPassword == null || adminPassword.trim().isEmpty()) {
            return;
        }
        String email = adminEmail.trim();
        if (adminAccountRepo.existsByEmailIgnoreCase(email)) {
            return;
        }
        AdminAccountEntity account = new AdminAccountEntity();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(adminPassword));
        account.setEnabled(true);
        account.setRole("ADMIN");
        adminAccountRepo.save(account);
        log.info("Seeded bootstrap admin account for {}", email);
    }
}