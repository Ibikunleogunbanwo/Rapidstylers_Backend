package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.common.service.DTOService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.config.EmailConfig;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.entity.StylerPortfolioEntity;
import com.macrotel.rapidstylers.styler.dto.StylerPortfolioDTO;
import com.macrotel.rapidstylers.styler.pojo.StylerPortfolioData;
import com.macrotel.rapidstylers.styler.repo.StylerPortfolioRepo;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Stylist portfolio photos: upload, list and delete.
 */
@Service
public class PortfolioService {
    @Autowired
    DTOService dtoService;
    @Autowired
    ImageReclaimService imageReclaimService;
    private static final Logger LOG = Logger.getLogger(PortfolioService.class.getName());
    @Autowired
    EmailConfig emailConfig;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    StylerPortfolioRepo stylerPortfolioRepo;
    @Autowired
    ReadCacheService readCacheService;

    /**
     * Stores one portfolio photo, and hands the image back if it is refused.
     *
     * The browser uploaded it before asking, so a refusal here (an unknown
     * category, the size cap, a duplicate, a database failure) is the moment to
     * discard it. Only an image no row references can be destroyed, so a URL that
     * already belongs to a stored photo is never taken from it.
     */
    public BaseResponse createStylerPortfolio(StylerPortfolioData stylerPortfolioData){
        BaseResponse response = createStylerPortfolioRow(stylerPortfolioData);
        if(!SUCCESS_STATUS_CODE.equals(response.getStatusCode())){
            imageReclaimService.reclaimIfUnreferenced(stylerPortfolioData.getImageUrl());
        }
        return response;
    }

    private BaseResponse createStylerPortfolioRow(StylerPortfolioData stylerPortfolioData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerPortfolioData.getStylerId()) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid STyler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Category must be a gallery category (normalized to lowercase).
            String category = stylerPortfolioData.getCategory() == null ? "" : stylerPortfolioData.getCategory().trim().toLowerCase();
            if(!GALLERY_CATEGORIES.contains(category)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid category. Choose one of: " + String.join(", ", GALLERY_CATEGORIES));
                response.setData(EMPTY_DATA);
                return response;
            }
            // Skip exact duplicate uploads (same image URL).
            List<StylerPortfolioEntity> existing = stylerPortfolioRepo.findByStylerId(stylerPortfolioData.getStylerId());
            for(StylerPortfolioEntity item : existing){
                if(item.getImageUrl() != null && item.getImageUrl().equals(stylerPortfolioData.getImageUrl())){
                    response.setStatusCode(ERROR_STATUS_CODE);
                    response.setMessage("This image is already in your portfolio");
                    response.setData(EMPTY_DATA);
                    return response;
                }
            }
            // Cap portfolio size so the gallery can grow organically without flooding.
            if(existing.size() >= MAX_STYLER_PORTFOLIO_IMAGES){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Portfolio limit reached (max " + MAX_STYLER_PORTFOLIO_IMAGES + " images)");
                response.setData(EMPTY_DATA);
                return response;
            }
            // Name is optional — default to the category name.
            String name = stylerPortfolioData.getName() == null || stylerPortfolioData.getName().isBlank()
                    ? category : stylerPortfolioData.getName().trim();
            stylerPortfolioData.setName(name);
            stylerPortfolioData.setCategory(category);
            StylerPortfolioEntity stylerPortfolioEntity = new StylerPortfolioEntity(stylerPortfolioData);
            stylerPortfolioRepo.save(stylerPortfolioEntity);
            evict(ReadCacheService.KEY_STYLER_PORTFOLIO + stylerPortfolioData.getStylerId());
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Work added to your portfolio");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Portfolio of the authenticated stylist (identity from the token). */
    public BaseResponse listOwnStylerPortfolio(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<StylerPortfolioEntity> portfolio = stylerPortfolioRepo.findByStylerId(stylerId);
            Collections.reverse(portfolio);
            List<Object> result = new ArrayList<>();
            for(StylerPortfolioEntity entity : portfolio){
                // The stylist sees every photo, with the moderation decision and note.
                StylerPortfolioDTO dto = dtoService.stylerPortfolioDTO(entity);
                dto.setModerationNote(entity.getModerationNote());
                result.add(dto);
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listStylerPortfolio(String stylerId){
        BaseResponse response = new BaseResponse(true);
        try{
            //Check if styler account exist
            Optional<StylerEntity> isStylerExist= stylerRepo.findByStylerId(stylerId) ;
            if(isStylerExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid Styler Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cachedPortfolio(stylerId));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Every portfolio image across all stylists, for the admin moderation view,
     * decorated with the owning stylist's identity.
     */
    public BaseResponse listAllPortfolios(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerPortfolioEntity> portfolios = stylerPortfolioRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerPortfolioEntity item : portfolios){
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", item.getId());
                entry.put("stylerId", item.getStylerId());
                entry.put("imageUrl", item.getImageUrl());
                entry.put("name", item.getName());
                entry.put("category", item.getCategory());
                entry.put("createdAt", item.getCreatedAt());
                entry.put("moderationStatus", item.getModerationStatus());
                Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(item.getStylerId());
                if(stylerOpt.isPresent()){
                    StylerEntity styler = stylerOpt.get();
                    entry.put("businessName", styler.getBusinessName());
                    entry.put("firstname", styler.getFirstname());
                    entry.put("lastname", styler.getLastname());
                    entry.put("emailAddress", styler.getEmailAddress());
                    entry.put("verificationStatus", styler.getVerificationStatus());
                }
                result.add(entry);
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Admin deletes a stylist's gallery image and emails the stylist so they
     * know their work was removed and why.
     */
    public BaseResponse adminDeletePortfolioImage(Long portfolioId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerPortfolioEntity> itemOpt = stylerPortfolioRepo.findById(portfolioId);
            if(itemOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid portfolio image");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerPortfolioEntity item = itemOpt.get();
            Optional<StylerEntity> stylerOpt = stylerRepo.findByStylerId(item.getStylerId());
            String stylerEmail = null;
            String stylerName = "Stylist";
            if(stylerOpt.isPresent()){
                stylerEmail = stylerOpt.get().getEmailAddress();
                stylerName = (stylerOpt.get().getFirstname() + " " + stylerOpt.get().getLastname()).trim();
            }
            String category = item.getCategory() == null ? "" : item.getCategory();
            String imageUrl = item.getImageUrl();
            stylerPortfolioRepo.delete(item);
            evict(ReadCacheService.KEY_STYLER_PORTFOLIO + item.getStylerId());
            // Moderation takes the row away, so the asset goes with it unless the
            // same URL is still somebody else's image.
            imageReclaimService.reclaimIfUnreferenced(imageUrl);
            // Notify the stylist their work was removed (best effort — never fail the delete on mail).
            if(stylerEmail != null && !stylerEmail.isBlank()){
                try{
                    String subject = "Your " + category + " photo was removed from the gallery";
                    String body = "Dear " + stylerName + ",<br><br>"
                            + "One of your portfolio photos" + (category.isBlank() ? "" : " (category: " + category + ")")
                            + " has been removed from the RapidStylers gallery by our moderation team."
                            + "<br><br>If you believe this was done in error, please reach out to support."
                            + "<br><br>Thank you,<br>The RapidStylers Team";
                    emailConfig.sendSimpleMail(stylerEmail, subject, body);
                } catch (Exception mailEx){
                    LOG.warning("Portfolio delete mail failed: " + mailEx.getMessage());
                }
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Image removed from the gallery and the stylist has been notified");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * Stylist removes one of their own gallery images. Ownership is enforced
     * from the authenticated token — a stylist can never delete another
     * stylist's work.
     */
    public BaseResponse deleteOwnPortfolioImage(String stylerId, Long portfolioId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<StylerPortfolioEntity> itemOpt = stylerPortfolioRepo.findById(portfolioId);
            if(itemOpt.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid portfolio image");
                response.setData(EMPTY_DATA);
                return response;
            }
            StylerPortfolioEntity item = itemOpt.get();
            // Only the owner can remove their own work.
            if(!item.getStylerId().equals(stylerId)){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("You can only remove your own work");
                response.setData(EMPTY_DATA);
                return response;
            }
            String imageUrl = item.getImageUrl();
            stylerPortfolioRepo.delete(item);
            evict(ReadCacheService.KEY_STYLER_PORTFOLIO + stylerId);
            // Removing a photo from a portfolio is the row going away, so the asset
            // is only kept when another row still points at the same URL.
            imageReclaimService.reclaimIfUnreferenced(imageUrl);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Photo removed from your portfolio");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Portfolio list through the read cache; evicted on portfolio writes. */
    public List<Object> cachedPortfolio(String stylerId){
        return readCacheService.getOrLoad(
                ReadCacheService.KEY_STYLER_PORTFOLIO + stylerId,
                ReadCacheService.STYLER_PART_TTL, ReadCacheService.STYLER_PART_JITTER,
                () -> {
                    List<StylerPortfolioEntity> rows = stylerPortfolioRepo.findByStylerId(stylerId);
                    List<Object> built = new ArrayList<>();
                    for(StylerPortfolioEntity entity : rows){
                        // Rejected photos stay off the stylist's public profile.
                        if(StylerPortfolioEntity.REJECTED.equals(entity.getModerationStatus())) continue;
                        built.add(dtoService.stylerPortfolioDTO(entity));
                    }
                    Collections.reverse(built);
                    return built;
                });
    }

    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }
}
