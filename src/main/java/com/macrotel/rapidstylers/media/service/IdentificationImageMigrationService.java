package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Moves ID photos uploaded before ID uploads became private onto private
 * (authenticated) Cloudinary assets.
 *
 * Such a photo is a public `upload` asset: anyone holding its URL can open it.
 * For each one this copies the image to an authenticated asset with the same
 * public id, points the stylist's row at the copy, and only then deletes the
 * public original. That order makes every step safe to repeat: a run that
 * stops after the copy leaves the row on the public URL, so the next run copies
 * again (overwriting the private copy) and carries on; a row already on an
 * authenticated URL is counted and left alone.
 *
 * Work is bounded: one call examines at most {@link #MAX_LIMIT} stylists, read
 * {@link #PAGE_SIZE} at a time in row-id order, and answers with nextAfterId
 * when more remain. One stylist's failure is reported and the run goes on.
 * A dry run reads and counts but changes nothing anywhere.
 */
@Service
public class IdentificationImageMigrationService {

    private static final Logger LOG = Logger.getLogger(IdentificationImageMigrationService.class.getName());

    static final int PAGE_SIZE = 50;
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    // Package-private so a unit test can hand in stand-ins without a Spring context.
    @Autowired
    CloudinaryService cloudinaryService;

    @Autowired
    StylerRecords stylerRecords;

    @Autowired
    ImageReclaimService imageReclaimService;

    @Autowired
    AuditService auditService = new AuditService();

    /**
     * One pass over up to limit stylists with an ID photo, after afterId (row id;
     * null or 0 from the start). The answer: {dryRun, examined, found, migrated,
     * alreadyPrivate, failed, notCloudinary, ids, failedIds, failures,
     * publicCopyKeptIds, nextAfterId}.
     */
    public BaseResponse migrate(String adminId, boolean dryRun, Long afterId, Integer limit) {
        BaseResponse response = new BaseResponse(true);
        try {
            if (!dryRun && !cloudinaryService.isConfigured()) {
                return Responses.errorResponse(response, "Image storage is not configured");
            }
            int budget = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            long cursor = afterId == null || afterId < 0 ? 0 : afterId;
            Report report = new Report(dryRun);
            boolean more = true;
            while (more && report.examined < budget) {
                int want = Math.min(PAGE_SIZE, budget - report.examined);
                List<StylerEntity> page = stylerRecords.withIdentificationImageAfter(cursor, want);
                for (StylerEntity styler : page) {
                    report.examined++;
                    cursor = styler.getId() == null ? cursor : Math.max(cursor, styler.getId());
                    handle(styler, report);
                }
                more = page.size() == want;
            }
            report.nextAfterId = more ? cursor : null;

            auditService.audit(adminId, "ADMIN", dryRun ? "ADMIN_ID_IMAGE_MIGRATION_DRY_RUN" : "ADMIN_ID_IMAGE_MIGRATION",
                    "STYLER", "ALL", "found=" + report.found + " migrated=" + report.migrated
                            + " alreadyPrivate=" + report.alreadyPrivate + " failed=" + report.failedIds.size()
                            + " examined=" + report.examined + (report.nextAfterId == null ? "" : " nextAfterId=" + report.nextAfterId));
            LOG.info("ID photo migration" + (dryRun ? " (dry run)" : "") + ": examined " + report.examined
                    + ", found " + report.found + ", migrated " + report.migrated + ", failed " + report.failedIds.size());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(report.toMap());
        } catch (Exception ex) {
            LOG.warning("ID photo migration failed: " + ex.getMessage());
        }
        return response;
    }

    /** Sorts one stylist into a count and, on a real run, migrates a public photo. */
    private void handle(StylerEntity styler, Report report) {
        String stored = styler.getIdentificationImageUrl();
        String publicId = cloudinaryService.extractPublicId(stored);
        String type = publicId == null ? null : cloudinaryService.deliveryType(stored);
        if (publicId == null || type == null) {
            // A static path or another host: not a Cloudinary asset of this account.
            report.notCloudinary++;
            return;
        }
        if (CloudinaryService.TYPE_AUTHENTICATED.equals(type)) {
            report.alreadyPrivate++;
            return;
        }
        report.found++;
        if (report.dryRun) {
            report.ids.add(styler.getStylerId());
            return;
        }
        try {
            String copy = cloudinaryService.copyToAuthenticated(stored, publicId);
            if (copy == null || !CloudinaryService.TYPE_AUTHENTICATED.equals(cloudinaryService.deliveryType(copy))) {
                report.fail(styler.getStylerId(), "Could not copy the image to private storage");
                return;
            }
            styler.setIdentificationImageUrl(copy);
            stylerRecords.save(styler);
            report.migrated++;
            report.ids.add(styler.getStylerId());
            // The row now names the private copy (same public id), which is its one
            // reference. More than one means another row still uses the public
            // original, so it is kept for that row.
            boolean deleted = imageReclaimService.referenceCount(publicId) <= 1
                    && cloudinaryService.destroy(publicId, CloudinaryService.TYPE_UPLOAD);
            if (!deleted) {
                report.publicCopyKeptIds.add(styler.getStylerId());
            }
        } catch (Exception ex) {
            report.fail(styler.getStylerId(), "Unexpected error: " + ex.getClass().getSimpleName());
        }
    }

    /** What one call found and did. */
    private static final class Report {
        final boolean dryRun;
        int examined;
        int found;
        int migrated;
        int alreadyPrivate;
        int notCloudinary;
        Long nextAfterId;
        final List<String> ids = new ArrayList<>();
        final List<String> failedIds = new ArrayList<>();
        final List<Map<String, String>> failures = new ArrayList<>();
        final List<String> publicCopyKeptIds = new ArrayList<>();

        Report(boolean dryRun) {
            this.dryRun = dryRun;
        }

        void fail(String stylerId, String reason) {
            failedIds.add(stylerId);
            Map<String, String> failure = new LinkedHashMap<>();
            failure.put("stylerId", stylerId);
            failure.put("reason", reason);
            failures.add(failure);
        }

        Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dryRun", dryRun);
            data.put("examined", examined);
            data.put("found", found);
            data.put("migrated", migrated);
            data.put("alreadyPrivate", alreadyPrivate);
            data.put("failed", failedIds.size());
            data.put("notCloudinary", notCloudinary);
            data.put("ids", ids);
            data.put("failedIds", failedIds);
            data.put("failures", failures);
            data.put("publicCopyKeptIds", publicCopyKeptIds);
            data.put("nextAfterId", nextAfterId);
            return data;
        }
    }
}
