package com.macrotel.rapidstylers.media.service;

import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import com.macrotel.rapidstylers.security.access.Roles;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Gives back the Cloudinary assets that nothing points at any more.
 *
 * Five columns in this schema hold an image: a stylist's profile photo and the
 * ID document they were verified with, a portfolio photo, a blog article's
 * cover, and a service's picture. Rows are deleted and images are replaced all
 * the time, and until this class existed each of those left the asset on
 * Cloudinary forever, because a row deletion says nothing to a CDN.
 *
 * The rule is intentionally one-directional and deliberately boring: an asset is
 * destroyed only when **no row in any of those five columns still points at
 * it** (matched by public id, so no spelling of the URL slips past). That check is what makes this safe to call from anywhere, including
 * from paths a stranger can trigger, because the only thing it can ever destroy
 * is an image that no record refers to, which is the definition of an orphan.
 * The same URL can legitimately be shared, and a shared image is never taken
 * away from the rows that still use it.
 *
 * It refuses anything that is not a Cloudinary delivery URL, so the static
 * images served from this application's own folders and images hosted elsewhere
 * are never touched.
 *
 * Callers are expected to have removed or overwritten the row **before** calling
 * it, so that the row being discarded is not counted as a reference to itself.
 */
@Service
public class ImageReclaimService {

    private static final Logger LOG = Logger.getLogger(ImageReclaimService.class.getName());

    // Package-private like the domain services' collaborators, so a unit test can hand
    // in stand-ins without a Spring context.
    @Autowired
    CloudinaryService cloudinaryService;

    @Autowired
    StylerRepo stylerRepo;

    @Autowired
    StylerPortfolioRepo stylerPortfolioRepo;

    @Autowired
    BlogPostRepo blogPostRepo;

    @Autowired
    ServiceRepo serviceRepo;

    @Autowired
    UploadOwnership uploadOwnership = new UploadOwnership();

    /** A sign-up photo nothing uses after this long was abandoned (the sign-up pass lasts 30 minutes). */
    @Value("${app.uploads.abandoned-after-hours:24}")
    long abandonedAfterHours = 24;

    /** At most this many listing pages (500 each) per sweep, so one run stays short. */
    private static final int MAX_SWEEP_PAGES = 10;

    /**
     * The shape of a public id this app hands out: under the rapid_stylers folder,
     * letters, digits, dashes and underscores only. Anything else is refused before
     * a database or Cloudinary is asked about it.
     */
    private static final Pattern OUR_PUBLIC_ID = Pattern.compile("^rapid_stylers/[A-Za-z0-9_\\-/]+$");

    /**
     * How many rows still point at this asset, across every column that holds an
     * image. Matched by public id, not by the exact URL: the same asset has many
     * spellings (with or without the v123/ version, another extension, a ?query),
     * and counting only one spelling let a caller pass a variant and have a
     * still-used image destroyed. A loose match can only over-count, which keeps
     * an image rather than losing one.
     */
    public long referenceCount(String publicId) {
        String fragment = "/" + publicId;
        return stylerRepo.countByProfileImageUrlContaining(fragment)
                + stylerRepo.countByIdentificationImageUrlContaining(fragment)
                + stylerPortfolioRepo.countByImageUrlContaining(fragment)
                + blogPostRepo.countByImageUrlContaining(fragment)
                + serviceRepo.countByServiceImageUrlContaining(fragment);
    }

    /**
     * Destroys the asset behind a URL that nothing references any more.
     *
     * @return true when an asset was actually destroyed
     */
    public boolean reclaimIfUnreferenced(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        // Not a Cloudinary delivery URL (a static path served by this app, or an
        // image hosted somewhere else): nothing here to delete.
        return reclaimPublicIdIfUnreferenced(cloudinaryService.extractPublicId(url.trim()));
    }

    /**
     * Destroys an asset by its public id, but only when nothing references it.
     * This is the one way callers outside the server (the delete endpoint) may
     * remove an image, so the most anyone can delete is an orphan.
     *
     * @return true when an asset was actually destroyed
     */
    public boolean reclaimPublicIdIfUnreferenced(String publicId) {
        if (publicId == null || !OUR_PUBLIC_ID.matcher(publicId).matches() || publicId.contains("..")) {
            return false;
        }
        try {
            if (referenceCount(publicId) > 0) {
                // Still in use somewhere, so it is not an orphan. Silent by design:
                // sharing an image is normal and not worth a log line per delete.
                return false;
            }
            boolean destroyed = cloudinaryService.destroy(publicId);
            if (destroyed) {
                LOG.info("Reclaimed orphaned image " + publicId);
            }
            return destroyed;
        } catch (Exception ex) {
            // Cleanup is a courtesy, never a reason to fail the operation that
            // triggered it, and never a reason to lose an exception report either.
            LOG.warning("Image reclaim failed for " + publicId + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Whether the photo at this URL was uploaded by someone other than this
     * email's sign-up, so the sign-up may not use it. Older uploads with no
     * owner folder, and non-Cloudinary URLs, are accepted as before.
     */
    public boolean uploadedByAnotherSignup(String email, String url) {
        String publicId = url == null || url.isBlank() ? null : cloudinaryService.extractPublicId(url.trim());
        if (publicId == null || !UploadOwnership.hasOwnerFolder(publicId)) {
            return false;
        }
        return !uploadOwnership.owns(Roles.SIGNUP_UPLOAD, email, publicId);
    }

    /**
     * Gives back a failed sign-up's photo, but only one this sign-up uploaded
     * (or an older one with no owner folder, as before), and only if unused.
     */
    public boolean reclaimSignupUpload(String email, String url) {
        if (uploadedByAnotherSignup(email, url)) {
            return false;
        }
        return reclaimIfUnreferenced(url);
    }

    /**
     * Deletes sign-up photos nothing ever used: uploaded with a sign-up pass,
     * older than a day, and not on any profile. They come from sign-ups that
     * were abandoned or failed after the upload. Account uploads and anything
     * from before owner folders are never touched here.
     */
    @Scheduled(cron = "${app.uploads.sweep-cron:0 30 3 * * *}")
    public int sweepAbandonedSignupUploads() {
        if (!cloudinaryService.isConfigured()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(abandonedAfterHours));
        int deleted = 0;
        String cursor = null;
        for (int page = 0; page < MAX_SWEEP_PAGES; page++) {
            CloudinaryService.UploadPage uploads = cloudinaryService.listUploads("rapid_stylers/", cursor);
            for (CloudinaryService.Upload upload : uploads.uploads) {
                if (UploadOwnership.isSignupUpload(upload.publicId) && upload.createdAt.isBefore(cutoff)
                        && reclaimPublicIdIfUnreferenced(upload.publicId)) {
                    deleted++;
                }
            }
            cursor = uploads.nextCursor;
            if (cursor == null) {
                break;
            }
        }
        if (deleted > 0) {
            LOG.info("Deleted " + deleted + " abandoned sign-up photo(s)");
        }
        return deleted;
    }
}
