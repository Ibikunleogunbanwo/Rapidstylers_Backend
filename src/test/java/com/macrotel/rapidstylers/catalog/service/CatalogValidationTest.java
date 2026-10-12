package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.entity.BlogPostEntity;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.pojo.BlogPostData;
import com.macrotel.rapidstylers.catalog.pojo.ServiceTypeData;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Blog articles need a title and content and an https image; categories are trimmed, 2-50 characters, unique. */
class CatalogValidationTest {

    private CatalogService catalogService;
    private BlogPostRepo blogPostRepo;
    private ServiceRepo serviceRepo;

    @BeforeEach
    void setUp() {
        TestServices services = new TestServices();
        blogPostRepo = mock(BlogPostRepo.class);
        serviceRepo = mock(ServiceRepo.class);
        services.blogPostRepo = blogPostRepo;
        services.serviceRepo = serviceRepo;
        catalogService = services.catalogService();
    }

    private static BlogPostData article(String title, String content, String imageUrl) {
        BlogPostData data = new BlogPostData();
        data.setTitle(title);
        data.setContent(content);
        data.setImageUrl(imageUrl);
        data.setCategory("  Hair care ");
        return data;
    }

    @Test
    void anArticleNeedsContent() {
        BaseResponse response = catalogService.createBlogPost(article("Summer looks", "  ", null));

        assertEquals("400", response.getStatusCode());
        assertEquals("Blog content cannot be empty", response.getMessage());
        // Markup alone is no content either: it is stripped before saving.
        assertEquals("Blog content cannot be empty",
                catalogService.createBlogPost(article("Summer looks", "<p> </p>", null)).getMessage());
        assertEquals("Blog title cannot be empty",
                catalogService.createBlogPost(article("   ", "Body", null)).getMessage());
        verify(blogPostRepo, never()).save(any());
    }

    @Test
    void anArticleImageMustBeAnHttpsLink() {
        for (String bad : new String[]{"http://example.com/a.jpg", "javascript:alert(1)", "images/a.jpg", "https://"}) {
            assertEquals("Image URL must be a full https:// link",
                    catalogService.createBlogPost(article("Summer looks", "Body", bad)).getMessage(), bad);
        }
        verify(blogPostRepo, never()).save(any());
    }

    @Test
    void aValidArticleIsSavedWithItsCategoryTrimmed() {
        assertEquals("200", catalogService.createBlogPost(article("Summer looks", "Body", " https://res.cloudinary.com/demo/image/upload/a.jpg ")).getStatusCode());
        assertEquals("200", catalogService.createBlogPost(article("No image", "Body", "")).getStatusCode());

        ArgumentCaptor<BlogPostEntity> saved = ArgumentCaptor.forClass(BlogPostEntity.class);
        verify(blogPostRepo, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("Hair care", saved.getAllValues().get(0).getCategory());
        assertEquals("https://res.cloudinary.com/demo/image/upload/a.jpg", saved.getAllValues().get(0).getImageUrl());
        assertNull(saved.getAllValues().get(1).getImageUrl());
    }

    @Test
    void anUpdateIsCheckedTheSameWay() {
        BlogPostEntity existing = new BlogPostEntity();
        existing.setId(3L);
        when(blogPostRepo.findById(3L)).thenReturn(Optional.of(existing));
        BlogPostData data = article("Summer looks", "", null);
        data.setId("3");

        assertEquals("Blog content cannot be empty", catalogService.updateBlogPost(data).getMessage());
        verify(blogPostRepo, never()).save(any());
    }

    private static ServiceTypeData category(String name) {
        ServiceTypeData data = new ServiceTypeData();
        data.setServiceName(name);
        return data;
    }

    @Test
    void aCategoryNameIsTrimmedAndBetweenTwoAndFiftyCharacters() {
        assertEquals("Category name must be 2 to 50 characters", catalogService.createServiceType(category("  N ")).getMessage());
        assertEquals("Category name must be 2 to 50 characters", catalogService.createServiceType(category("x".repeat(51))).getMessage());
        assertEquals("Category name may only contain letters, spaces, hyphens, apostrophes and &",
                catalogService.createServiceType(category("Nails <b>")).getMessage());
        verify(serviceRepo, never()).save(any());

        assertEquals("200", catalogService.createServiceType(category("  Nails   & Spa ")).getStatusCode());
        ArgumentCaptor<ServiceEntity> saved = ArgumentCaptor.forClass(ServiceEntity.class);
        verify(serviceRepo).save(saved.capture());
        assertEquals("Nails & Spa", saved.getValue().getServiceName());
        assertEquals("200", catalogService.createServiceType(category("Ox")).getStatusCode());
    }

    @Test
    void aDuplicateNameIsRefusedWhateverItsCase() {
        ServiceEntity braids = new ServiceEntity();
        braids.setId(1L);
        braids.setServiceName("Braids");
        when(serviceRepo.findBySameName("braids")).thenReturn(List.of(braids));
        when(serviceRepo.findById(1L)).thenReturn(Optional.of(braids));
        ServiceEntity other = new ServiceEntity();
        other.setId(2L);
        when(serviceRepo.findById(2L)).thenReturn(Optional.of(other));

        assertEquals("A category with this name already exists", catalogService.createServiceType(category(" braids ")).getMessage());
        ServiceTypeData rename = category("braids");
        rename.setId("2");
        assertEquals("A category with this name already exists", catalogService.updateService(rename).getMessage());
        // Saving a category under its own name (a new picture, say) is fine.
        ServiceTypeData same = category("braids");
        same.setId("1");
        assertEquals("200", catalogService.updateService(same).getStatusCode());
    }
}
