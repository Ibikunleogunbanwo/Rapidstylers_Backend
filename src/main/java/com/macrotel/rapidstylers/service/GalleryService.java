package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Serves the public gallery exclusively from work that APPROVED professionals
 * have uploaded to their portfolios — newest first, no stock imagery. Results
 * are read fresh on every call so new uploads appear immediately.
 */
@Service
public class GalleryService {

    private static final Logger LOG = Logger.getLogger(GalleryService.class.getName());

    @Autowired
    private StylerPortfolioRepo stylerPortfolioRepo;

    @Autowired
    private StylerRepo stylerRepo;

    public BaseResponse searchGallery(String category, int perPage, int page, String query) {
        // Fresh response per call — the shared field previously let one
        // request's state leak into the next.
        BaseResponse response = new BaseResponse(true);

        int safePage = Math.max(page, 1);
        String normalized = category.trim().toLowerCase();
        String normalizedQuery = (query == null ? "" : query.trim().toLowerCase());

        try {
            // Offset is counted over *eligible* (approved) uploads so pages stay
            // stable. A keyword narrows uploads to those whose studio name matches it.
            List<Map<String, Object>> uploads = stylerUploads(normalized, normalizedQuery, (safePage - 1) * perPage, perPage);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(uploads);
        } catch (Exception ex) {
            LOG.warning("Gallery error: " + ex.getMessage());
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Failed to fetch images from gallery service");
            response.setData(EMPTY_DATA);
        }
        return response;
    }

    /**
     * Portfolio images from APPROVED stylists for a category, newest first,
     * shaped with a `source` marker so the frontend can attribute the work.
     * The offset skips eligible (approved) uploads so the pagination stays
     * stable as uploads come in between pages.
     */
    private List<Map<String, Object>> stylerUploads(String category, String query, int offset, int limit){
        List<Map<String, Object>> result = new ArrayList<>();
        List<StylerPortfolioEntity> items = stylerPortfolioRepo.findByCategory(category);
        // Newest first
        Collections.reverse(items);
        int eligible = 0;
        for(StylerPortfolioEntity item : items){
            if(result.size() >= limit) break;
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(item.getStylerId());
            if(stylerOpt.isEmpty()) continue;
            StylerEntity styler = stylerOpt.get();
            // Only approved professionals' work is shown publicly.
            if(!VERIFICATION_APPROVED.equals(styler.getVerificationStatus())) continue;
            // Keyword narrows uploads to matching studio names (within this category).
            if(!query.isEmpty()){
                String businessName = styler.getBusinessName() == null ? "" : styler.getBusinessName();
                String fullName = (styler.getFirstname() + " " + styler.getLastname()).trim();
                if(!businessName.toLowerCase().contains(query) && !fullName.toLowerCase().contains(query)) continue;
            }
            if(eligible++ < offset) continue;
            String businessName = styler.getBusinessName() == null || styler.getBusinessName().isBlank()
                    ? (styler.getFirstname() + " " + styler.getLastname()).trim() : styler.getBusinessName();
            Map<String, Object> photo = new LinkedHashMap<>();
            Map<String, String> src = new LinkedHashMap<>();
            src.put("medium", item.getImageUrl());
            src.put("large", item.getImageUrl());
            src.put("original", item.getImageUrl());
            photo.put("src", src);
            photo.put("alt", businessName + " — " + item.getCategory());
            photo.put("photographer", businessName);
            photo.put("stylerId", item.getStylerId());
            photo.put("source", "stylist");
            result.add(photo);
        }
        return result;
    }

}
