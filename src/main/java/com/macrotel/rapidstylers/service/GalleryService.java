package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.StylerEntity;
import com.macrotel.rapidstylers.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.repo.StylerRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Proxies gallery image searches to Pexels so the API key never ships to the
 * browser.  Results are cached in memory for one hour so we stay well under the
 * Pexels free-tier limit (200 requests / hour).
 */
@Service
public class GalleryService {

    private static final Logger LOG = Logger.getLogger(GalleryService.class.getName());
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private StylerPortfolioRepo stylerPortfolioRepo;

    @Autowired
    private StylerRepo stylerRepo;

    @Value("${app.pexels.api-key:}")
    private String pexelsApiKey;

    /** key = "category|per_page" → { timestamp, photos } */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Category → Pexels search query tuned toward Black beauty content, so the
     * gallery reflects the community it serves. Unknown categories fall back to
     * "black <term>".
     */
    private static final Map<String, String> CATEGORY_QUERIES = new HashMap<>();
    static {
        CATEGORY_QUERIES.put("dreadlocks", "black woman dreadlocks hairstyle");
        CATEGORY_QUERIES.put("buzz cut", "black woman buzz cut");
        CATEGORY_QUERIES.put("braids", "black woman braids hairstyle");
        CATEGORY_QUERIES.put("cornrows", "black woman cornrows");
        CATEGORY_QUERIES.put("wigs", "black woman wig hairstyle");
        CATEGORY_QUERIES.put("high-top fade", "black man high top fade haircut");
        CATEGORY_QUERIES.put("hair dye", "black woman hair color");
        CATEGORY_QUERIES.put("nail tech", "black woman nail technician manicure");
        CATEGORY_QUERIES.put("makeup", "black woman makeup beauty");
        CATEGORY_QUERIES.put("eyelash extensions", "black woman eyelash extensions");
        CATEGORY_QUERIES.put("natural hair", "black natural hair styles");
        CATEGORY_QUERIES.put("afro", "afro hairstyle black");
        CATEGORY_QUERIES.put("locs", "locs hairstyle black woman");
    }

    public BaseResponse searchGallery(String category, int perPage, int page, String query) {
        // Fresh response per call — the shared field previously let one
        // request's state leak into the next.
        BaseResponse response = new BaseResponse(true);

        int safePage = Math.max(page, 1);
        String normalized = category.trim().toLowerCase();
        String normalizedQuery = (query == null ? "" : query.trim().toLowerCase());
        // Each distinct keyword caches separately so searching never poisons other pages.
        String cacheKey = normalized + "|" + perPage + "|" + safePage + "|" + normalizedQuery;

        try {
            // 1. Approved stylists' uploaded work for this category — merged fresh
            //    every call so new uploads appear immediately (never cached).
            //    Offset is counted over *eligible* (approved) uploads so pages stay stable.
            //    A keyword narrows uploads to those whose studio name matches it.
            List<Map<String, Object>> uploads = stylerUploads(normalized, normalizedQuery, (safePage - 1) * perPage, perPage);

            // 2. Pexels results (cached for 1h to respect the free-tier rate limit).
            List<Object> pexels = pexelsPhotos(normalized, normalizedQuery, perPage, safePage, cacheKey);

            // 3. Uploaded work first — as stylists add images over time they
            //    naturally replace Pexels content.
            List<Object> merged = new ArrayList<>(uploads);
            for (Object photo : pexels) {
                if (merged.size() >= perPage) break;
                merged.add(photo);
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(merged);
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
     * shaped like Pexels photos (with a `source` marker). The offset skips
     * eligible (approved) uploads so the pagination stays stable as uploads
     * come in between pages.
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

    /** Pexels search results for a category (cached 1h, keyed by page too). */
    private List<Object> pexelsPhotos(String category, String keyword, int perPage, int page, String cacheKey){
        if (pexelsApiKey == null || pexelsApiKey.isBlank()) {
            return Collections.emptyList();
        }
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.photos;
        }
        // Search stays inside the active category; the keyword narrows it further.
        String query = CATEGORY_QUERIES.getOrDefault(category, "black " + category);
        if(!keyword.isEmpty()){
            query = query + " " + keyword;
        }
        String encodedQuery;
        try {
            encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return Collections.emptyList();
        }
        String url = "https://api.pexels.com/v1/search?query=" + encodedQuery + "&per_page=" + perPage + "&page=" + page;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", pexelsApiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> pexelsResponse = restTemplate.exchange(
                url, HttpMethod.GET, request, Map.class);

        if (pexelsResponse.getStatusCode() != HttpStatus.OK || pexelsResponse.getBody() == null) {
            return Collections.emptyList();
        }

        Object photos = pexelsResponse.getBody().get("photos");
        List<Object> list = (photos == null) ? Collections.emptyList() : (List<Object>) photos;
        cache.put(cacheKey, new CacheEntry(list));
        return list;
    }

    private static class CacheEntry {
        final long timestamp;
        final List<Object> photos;

        CacheEntry(List<Object> photos) {
            this.timestamp = System.currentTimeMillis();
            this.photos = photos;
        }
    }

}
