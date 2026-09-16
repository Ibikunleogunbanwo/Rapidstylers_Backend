package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.repo.BlogPostRepo;
import com.macrotel.rapidstylers.repo.ServiceRepo;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.logging.Logger;

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
 * destroyed only when **no row in any of those five columns still holds its
 * URL**. That check is what makes this safe to call from anywhere, including
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

    // Package-private like AppService's own collaborators, so a unit test can hand
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

    /** How many rows still point at this exact URL, across every column that holds an image. */
    public long referenceCount(String url) {
        if (url == null || url.isBlank()) {
            return 0;
        }
        String trimmed = url.trim();
        return stylerRepo.countByProfileImageUrl(trimmed)
                + stylerRepo.countByIdentificationImageUrl(trimmed)
                + stylerPortfolioRepo.countByImageUrl(trimmed)
                + blogPostRepo.countByImageUrl(trimmed)
                + serviceRepo.countByImageUrl(trimmed);
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
        String trimmed = url.trim();
        try {
            long references = referenceCount(trimmed);
            if (references > 0) {
                // Still in use somewhere, so it is not an orphan. Silent by design:
                // sharing an image is normal and not worth a log line per delete.
                return false;
            }
            String publicId = cloudinaryService.extractPublicId(trimmed);
            if (publicId == null) {
                // Not a Cloudinary delivery URL: a static path served by this app,
                // or an image hosted somewhere else. Nothing here to delete.
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
            LOG.warning("Image reclaim failed for " + trimmed + ": " + ex.getMessage());
            return false;
        }
    }
}
