package com.macrotel.rapidstylers.common.service;

import com.macrotel.rapidstylers.common.entity.AuditLogEntity;
import com.macrotel.rapidstylers.common.repo.AuditLogRepo;
import com.macrotel.rapidstylers.config.EmailConfig;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Shared audit-log and ops-alert sink, so every domain service can record an
 * audit trail or alert the ops address the same way.
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
     * Records an audit-trail row; never throws (a silent no-op when the repo
     * is unavailable).
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

    /** The newest 100 audit rows (empty when the repo is unavailable). */
    public java.util.List<AuditLogEntity> recentLogs() {
        return auditLogRepo == null ? java.util.List.of() : auditLogRepo.findTop100ByOrderByCreatedAtDesc();
    }

    /** One page of audit rows, newest first, narrowed by whichever filters are given. */
    public org.springframework.data.domain.Page<AuditLogEntity> searchLogs(String action, String actorId, String resourceId,
                                                                          org.springframework.data.domain.Pageable pageable) {
        return auditLogRepo.search(action, actorId, resourceId, pageable);
    }
}
