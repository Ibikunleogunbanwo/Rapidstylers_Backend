package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.entity.AuditLogEntity;
import com.macrotel.rapidstylers.repo.AuditLogRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

/**
 * Shared audit-log and ops-alert sink. Extracted from AppService so domain
 * services (payment ops first) can record audit trails without depending on
 * the app-wide service class.
 */
@Service
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class.getName());

    @Autowired(required = false)
    AuditLogRepo auditLogRepo;

    @Autowired(required = false)
    EmailConfig emailConfig;

    /** Ops email address for payment dispute / reconciliation alerts (empty = disabled). */
    @Value("${app.admin.alert-email:}")
    private String adminAlertEmail;

    /**
     * Records an audit-trail row; never throws. Mirrors the original AppService
     * audit(...) behavior (silent no-op when the repo is unavailable).
     */
    public void audit(String actorId, String actorRole, String action,
                      String resourceType, String resourceId, String details) {
        try {
            if (auditLogRepo != null) {
                auditLogRepo.save(new AuditLogEntity(actorId, actorRole, action, resourceType, resourceId, details));
            }
        } catch (Exception ex) {
            LOG.warning("Audit log write failed: " + ex.getMessage());
        }
    }

    /** Sends an operational alert to the configured ops address (no-op when unset). */
    public void alertAdmin(String message) {
        if (adminAlertEmail == null || adminAlertEmail.isBlank() || emailConfig == null) {
            LOG.warning("Admin alert (no alert email configured): " + message);
            return;
        }
        try {
            emailConfig.sendSimpleMail(adminAlertEmail, "RapidStylers - Action required", "<p>" + message + "</p>");
        } catch (Exception ex) {
            LOG.warning("Admin alert failed: " + ex.getMessage());
        }
    }
}
