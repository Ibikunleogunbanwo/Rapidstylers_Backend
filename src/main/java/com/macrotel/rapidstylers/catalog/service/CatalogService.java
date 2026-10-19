package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.common.service.CacheLoadUnavailableException;
import com.macrotel.rapidstylers.catalog.entity.BlogPostEntity;
import com.macrotel.rapidstylers.catalog.entity.IdentificationEntity;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.pojo.BlogPostData;
import com.macrotel.rapidstylers.catalog.pojo.IdentificationData;
import com.macrotel.rapidstylers.catalog.pojo.ServiceTypeData;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.IdentificationRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.config.AppUtils;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import java.util.*;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * The catalog: identification types, service types and blog posts that admins manage and everyone reads.
 */
@Service
public class CatalogService {
    @Autowired
    ImageReclaimService imageReclaimService;
    private static final Logger LOG = Logger.getLogger(CatalogService.class.getName());
    @Autowired
    IdentificationRepo identificationRepo;
    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    BlogPostRepo blogPostRepo;
    @Autowired
    ReadCacheService readCacheService;

    public BaseResponse createIdentificationType (IdentificationData identificationData){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<IdentificationEntity> isIdNameExist = identificationRepo.findByIdentificationName(identificationData.getIdentificationName());
            if(isIdNameExist.isPresent()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Identification already exist");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = new IdentificationEntity();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);
            evict(ReadCacheService.KEY_CATALOG_IDENTIFICATIONS);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(identificationData.getIdentificationName()+ " successfully added to list of identification");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listIdentification(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Object> result = readCacheService.getOrLoad(
                    ReadCacheService.KEY_CATALOG_IDENTIFICATIONS, ReadCacheService.CATALOG_TTL, ReadCacheService.CATALOG_JITTER,
                    this::loadCatalogIdentifications);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (CacheLoadUnavailableException ex) {
            throw ex;
        } catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateIdentification(IdentificationData identificationData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Get Identifications
            if(identificationData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Identification Id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<IdentificationEntity> isIdentificationExist = identificationRepo.findById(Long.parseLong(identificationData.getId()));
            if(isIdentificationExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Identification available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = isIdentificationExist.get();
            identificationEntity.setIdentificationName(identificationData.getIdentificationName());
            identificationRepo.save(identificationEntity);
            evict(ReadCacheService.KEY_CATALOG_IDENTIFICATIONS);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Identification Updated Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse deleteIdentification(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<IdentificationEntity> getIdentification = identificationRepo.findById(Long.parseLong(id));
            if(getIdentification.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Identification available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            IdentificationEntity identificationEntity = getIdentification.get();
            identificationRepo.delete(identificationEntity);
            evict(ReadCacheService.KEY_CATALOG_IDENTIFICATIONS);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Identification deleted successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse createServiceType (ServiceTypeData serviceTypeData){
        BaseResponse response = new BaseResponse(true);
        try{
            String name = CatalogRules.clean(serviceTypeData.getServiceName());
            String invalid = categoryNameProblem(name, null);
            if(invalid != null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(invalid);
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = new ServiceEntity();
            serviceEntity.setServiceName(name);
            serviceEntity.setServiceImageUrl(serviceTypeData.getImageUrl());
            serviceEntity.setDescription(AppUtils.sanitizeText(serviceTypeData.getDescription()));
            serviceRepo.save(serviceEntity);
            evict(ReadCacheService.KEY_CATALOG_SERVICES);

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(name + " successfully added to list of service");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listService(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Object> result = readCacheService.getOrLoad(
                    ReadCacheService.KEY_CATALOG_SERVICES, ReadCacheService.CATALOG_TTL, ReadCacheService.CATALOG_JITTER,
                    this::loadCatalogServices);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (CacheLoadUnavailableException ex) {
            throw ex;
        } catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateService(ServiceTypeData serviceTypeData){
        BaseResponse response = new BaseResponse(true);
        try{
            //Get Identifications
            if(serviceTypeData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Service Id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<ServiceEntity> isServiceExist = serviceRepo.findById(Long.parseLong(serviceTypeData.getId()));
            if(isServiceExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Service exist for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = isServiceExist.get();
            String name = CatalogRules.clean(serviceTypeData.getServiceName());
            String invalid = categoryNameProblem(name, serviceEntity.getId());
            if(invalid != null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(invalid);
                response.setData(EMPTY_DATA);
                return response;
            }
            String previousImageUrl = serviceEntity.getServiceImageUrl();
            serviceEntity.setServiceName(name);
            serviceEntity.setServiceImageUrl(serviceTypeData.getImageUrl());
            serviceEntity.setDescription(AppUtils.sanitizeText(serviceTypeData.getDescription()));
            serviceRepo.save(serviceEntity);
            evict(ReadCacheService.KEY_CATALOG_SERVICES);
            // Replacing a picture leaves the old one behind unless it is handed back.
            if(previousImageUrl != null && !previousImageUrl.equals(serviceEntity.getServiceImageUrl())){
                imageReclaimService.reclaimIfUnreferenced(previousImageUrl);
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service Updated Successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse deleteService(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<ServiceEntity> getService = serviceRepo.findById(Long.parseLong(id));
            if(getService.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No Service available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            ServiceEntity serviceEntity = getService.get();
            String previousImageUrl = serviceEntity.getServiceImageUrl();
            serviceRepo.delete(serviceEntity);
            evict(ReadCacheService.KEY_CATALOG_SERVICES);
            // The row that held this picture is gone, so unless another row still
            // refers to it, the asset has nothing left to belong to.
            imageReclaimService.reclaimIfUnreferenced(previousImageUrl);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Service deleted successful");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse listBlogPosts(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<Object> result = readCacheService.getOrLoad(
                    ReadCacheService.KEY_CATALOG_BLOGS, ReadCacheService.BLOG_TTL, ReadCacheService.BLOG_JITTER,
                    this::loadCatalogBlogs);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (CacheLoadUnavailableException ex) {
            throw ex;
        } catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse singleBlogPost(String id){
        BaseResponse response = new BaseResponse(true);
        // Reject malformed IDs before allocating a cache key or querying MySQL.
        // Normalize leading zeros/whitespace so equivalent IDs share one entry.
        final long blogId;
        try {
            String normalized = id == null ? "" : id.trim();
            if (!normalized.matches("[0-9]{1,19}")) throw new NumberFormatException();
            blogId = Long.parseLong(normalized);
            if (blogId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            return Responses.errorResponse(response, "A valid positive blog ID is required");
        }
        try{
            Map<String, String> postMap = readCacheService.getOrLoad(
                    ReadCacheService.KEY_CATALOG_BLOG + blogId, ReadCacheService.BLOG_TTL, ReadCacheService.BLOG_JITTER,
                    () -> {
                        Optional<BlogPostEntity> post = blogPostRepo.findById(blogId);
                        // A draft is not on the site: the same answer as no article at all.
                        if(post.isEmpty() || !post.get().isPublished()) return null;
                        BlogPostEntity blogPost = post.get();
                        HashMap<String, String> map = new HashMap<>();
                        map.put("id", String.valueOf(blogPost.getId()));
                        map.put("title", blogPost.getTitle());
                        map.put("category", blogPost.getCategory());
                        map.put("content", blogPost.getContent());
                        map.put("imageUrl", blogPost.getImageUrl());
                        map.put("author", blogPost.getAuthor());
                        map.put("dateCreated", blogPost.getInsertedDt());
                        return map;
                    });
            if(postMap == null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article found for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(postMap);
        }
        catch (CacheLoadUnavailableException ex) {
            throw ex;
        } catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse createBlogPost(BlogPostData blogPostData){
        BaseResponse response = new BaseResponse(true);
        try{
            String invalid = blogProblem(blogPostData);
            if(invalid != null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(invalid);
                response.setData(EMPTY_DATA);
                return response;
            }
            String publishStatus = blogStatus(blogPostData.getStatus());
            if(publishStatus == null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(BLOG_STATUS_ERROR);
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity post = new BlogPostEntity();
            applyBlogFields(post, blogPostData);
            post.setStatus(publishStatus);
            post.setAuthor(blogPostData.getAuthor() == null || blogPostData.getAuthor().isEmpty()
                    ? "RapidStylers Team" : AppUtils.sanitizeText(blogPostData.getAuthor()));
            BlogPostEntity saved = blogPostRepo.save(post);
            evict(ReadCacheService.KEY_CATALOG_BLOGS);
            if(saved != null && saved.getId() != null) evict(ReadCacheService.KEY_CATALOG_BLOG + saved.getId());

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article created successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse updateBlogPost(BlogPostData blogPostData){
        BaseResponse response = new BaseResponse(true);
        try{
            if(blogPostData.getId() == null || blogPostData.getId().isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Blog article id cannot be empty");
                response.setData(EMPTY_DATA);
                return response;
            }
            Optional<BlogPostEntity> isPostExist = blogPostRepo.findById(Long.parseLong(blogPostData.getId()));
            if(isPostExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article exist for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            String invalid = blogProblem(blogPostData);
            if(invalid != null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(invalid);
                response.setData(EMPTY_DATA);
                return response;
            }
            String publishStatus = blogStatus(blogPostData.getStatus());
            if(publishStatus == null){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage(BLOG_STATUS_ERROR);
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity post = isPostExist.get();
            String previousImageUrl = post.getImageUrl();
            applyBlogFields(post, blogPostData);
            post.setStatus(publishStatus);
            if(blogPostData.getAuthor() != null && !blogPostData.getAuthor().isEmpty()){
                post.setAuthor(AppUtils.sanitizeText(blogPostData.getAuthor()));
            }
            blogPostRepo.save(post);
            // Both keys go, whatever changed: publishing or unpublishing moves the
            // article onto or off the public list as well as its own page.
            evict(ReadCacheService.KEY_CATALOG_BLOGS, ReadCacheService.KEY_CATALOG_BLOG + blogPostData.getId(),
                    ReadCacheService.KEY_CATALOG_BLOG + post.getId());
            // A new cover leaves the previous one unused, so it goes back.
            if(previousImageUrl != null && !previousImageUrl.equals(post.getImageUrl())){
                imageReclaimService.reclaimIfUnreferenced(previousImageUrl);
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article updated successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** Why this category name cannot be saved (excluding the category being edited), or null. */
    private String categoryNameProblem(String name, Long editingId){
        String invalid = CatalogRules.categoryNameError(name);
        if(invalid != null) return invalid;
        for(ServiceEntity same : serviceRepo.findBySameName(name)){
            if(editingId == null || !editingId.equals(same.getId())) return CatalogRules.DUPLICATE_CATEGORY;
        }
        return null;
    }

    /** Why this article cannot be saved, judged on what would actually be stored, or null. */
    private static String blogProblem(BlogPostData data){
        return CatalogRules.blogError(AppUtils.sanitizeText(data.getTitle()), AppUtils.sanitizeText(data.getContent()),
                data.getImageUrl());
    }

    static final String BLOG_STATUS_ERROR = "Status must be DRAFT or PUBLISHED";

    /**
     * The status an admin asked for, as stored: missing or blank is PUBLISHED
     * (what every save did before drafts existed), DRAFT or PUBLISHED in any
     * case as given, and null for anything else.
     */
    static String blogStatus(String requested){
        if(requested == null || requested.isBlank()) return BlogPostEntity.PUBLISHED;
        String upper = requested.trim().toUpperCase(Locale.ROOT);
        return BlogPostEntity.DRAFT.equals(upper) || BlogPostEntity.PUBLISHED.equals(upper) ? upper : null;
    }

    /**
     * Admin view of every article, drafts included, newest first, with the full
     * content so an article can be edited from the list. Never cached: the
     * public caches hold published articles only.
     */
    public BaseResponse listAdminBlogPosts(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<BlogPostEntity> posts = blogPostRepo.findAll();
            posts.sort(Comparator.comparing(BlogPostEntity::getId, Comparator.nullsLast(Comparator.reverseOrder())));
            List<Object> rows = new ArrayList<>();
            for(BlogPostEntity post : posts) rows.add(adminBlogRow(post));
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(rows);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** One article for the admin editor, draft or not. */
    public BaseResponse adminBlogPost(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BlogPostEntity> post = id == null || !id.trim().matches("\\d{1,18}") ? Optional.empty()
                    : blogPostRepo.findById(Long.parseLong(id.trim()));
            if(post.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article found for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(adminBlogRow(post.get()));
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /** The public fields of an article plus content, status and dateUpdated. */
    private static Map<String, String> adminBlogRow(BlogPostEntity post){
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", String.valueOf(post.getId()));
        row.put("title", post.getTitle());
        row.put("category", post.getCategory());
        row.put("content", post.getContent());
        row.put("imageUrl", post.getImageUrl());
        row.put("author", post.getAuthor());
        row.put("dateCreated", post.getInsertedDt());
        row.put("dateUpdated", post.getUpdatedDt());
        row.put("status", post.isPublished() ? BlogPostEntity.PUBLISHED : BlogPostEntity.DRAFT);
        return row;
    }

    /** Title, category, content and image of an article; text is sanitised, category trimmed, a blank image dropped. */
    private static void applyBlogFields(BlogPostEntity post, BlogPostData data){
        post.setTitle(AppUtils.sanitizeText(data.getTitle()));
        post.setCategory(AppUtils.sanitizeText(CatalogRules.clean(data.getCategory())));
        post.setContent(AppUtils.sanitizeText(data.getContent()));
        String imageUrl = data.getImageUrl() == null ? null : data.getImageUrl().trim();
        post.setImageUrl(imageUrl == null || imageUrl.isEmpty() ? null : imageUrl);
    }

    public BaseResponse deleteBlogPost(String id){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<BlogPostEntity> getPost = blogPostRepo.findById(Long.parseLong(id));
            if(getPost.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("No blog article available for such id");
                response.setData(EMPTY_DATA);
                return response;
            }
            BlogPostEntity deletedPost = getPost.get();
            String coverImageUrl = deletedPost.getImageUrl();
            blogPostRepo.delete(deletedPost);
            evict(ReadCacheService.KEY_CATALOG_BLOGS, ReadCacheService.KEY_CATALOG_BLOG + id,
                    ReadCacheService.KEY_CATALOG_BLOG + deletedPost.getId());
            // The cover is only kept if some other article still uses the same URL.
            imageReclaimService.reclaimIfUnreferenced(coverImageUrl);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage("Blog article deleted successfully");
            response.setData(EMPTY_DATA);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    // ---- Catalog read-cache loaders (shared by the list APIs and warm-up) ----

    public List<Object> loadCatalogServices(){
        List<ServiceEntity> getAllService = serviceRepo.findAll();
        List<Object> built = new ArrayList<>();
        for(ServiceEntity serviceEntity : getAllService){
            HashMap<String, String> serviceMap = new HashMap<>();
            serviceMap.put("id", String.valueOf(serviceEntity.getId()));
            serviceMap.put("serviceName", serviceEntity.getServiceName());
            serviceMap.put("status", serviceEntity.getStatus());
            serviceMap.put("dateCreated", serviceEntity.getInsertedDt());
            serviceMap.put("imageUrl", serviceEntity.getServiceImageUrl());
            serviceMap.put("description", serviceEntity.getDescription());
            built.add(serviceMap);
        }
        return built;
    }

    public List<Object> loadCatalogIdentifications(){
        List<IdentificationEntity> getAllIdentification = identificationRepo.findAll();
        List<Object> built = new ArrayList<>();
        for(IdentificationEntity identificationEntity : getAllIdentification){
            HashMap<String, String> idMap = new HashMap<>();
            idMap.put("id", String.valueOf(identificationEntity.getId()));
            idMap.put("identificationName", identificationEntity.getIdentificationName());
            idMap.put("status", identificationEntity.getStatus());
            idMap.put("dateCreated", identificationEntity.getInsertedDate());
            built.add(idMap);
        }
        return built;
    }

    public List<Object> loadCatalogBlogs(){
        List<BlogPostEntity> allPosts = blogPostRepo.findAll();
        allPosts.sort(Comparator.comparing(BlogPostEntity::getId).reversed());
        List<Object> built = new ArrayList<>();
        for(BlogPostEntity post : allPosts){
            // Drafts never reach the public list (nor this cache entry).
            if(!post.isPublished()) continue;
            HashMap<String, String> postMap = new HashMap<>();
            postMap.put("id", String.valueOf(post.getId()));
            postMap.put("title", post.getTitle());
            postMap.put("category", post.getCategory());
            postMap.put("imageUrl", post.getImageUrl());
            postMap.put("author", post.getAuthor());
            postMap.put("dateCreated", post.getInsertedDt());
            built.add(postMap);
        }
        return built;
    }

    /** Null-safe eviction — unit tests that build this service without a cache never break. */
    private void evict(String... keys){
        if(readCacheService != null){
            readCacheService.evict(keys);
        }
    }
}
