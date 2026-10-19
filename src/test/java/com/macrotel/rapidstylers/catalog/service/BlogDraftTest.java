package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.entity.BlogPostEntity;
import com.macrotel.rapidstylers.catalog.pojo.BlogPostData;
import com.macrotel.rapidstylers.catalog.repo.BlogPostRepo;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.support.TestServices;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Drafts stay in the admin console: the public list, article page and their caches never show one. */
class BlogDraftTest {

    private CatalogService catalogService;
    private BlogPostRepo blogPostRepo;
    private ReadCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TestServices services = new TestServices();
        blogPostRepo = mock(BlogPostRepo.class);
        services.blogPostRepo = blogPostRepo;
        cache = mock(ReadCacheService.class);
        // A cache that always misses, so every read runs the loader.
        when(cache.getOrLoad(anyString(), any(), any(), any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(3)).get());
        services.readCacheService = cache;
        services.imageReclaimService = mock(ImageReclaimService.class);
        catalogService = services.catalogService();
        when(blogPostRepo.save(any(BlogPostEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "0", "-1", "abc", "1.5", "1e3", "9223372036854775808", "111111111111111111111111"})
    void invalidPublicBlogIdsNeverReachTheCacheOrDatabase(String id) {
        assertEquals("400", catalogService.singleBlogPost(id).getStatusCode());
        verifyNoInteractions(cache, blogPostRepo);
    }

    @Test void equivalentBlogIdsUseOneCanonicalCacheKey() {
        catalogService.singleBlogPost("0001");
        catalogService.singleBlogPost(" 1 ");
        verify(cache, times(2)).getOrLoad(org.mockito.ArgumentMatchers.eq("catalog:blog:1"), any(), any(), any());
        verify(blogPostRepo, times(2)).findById(1L);
    }

    @Test void createEvictsAPreviouslyMissingBlogId() {
        assertEquals("400", catalogService.singleBlogPost("42").getStatusCode());
        when(blogPostRepo.save(any(BlogPostEntity.class))).thenAnswer(call -> {
            BlogPostEntity post = call.getArgument(0); post.setId(42L); return post;
        });
        assertEquals("200", catalogService.createBlogPost(article("PUBLISHED")).getStatusCode());
        verify(cache).evict(ReadCacheService.KEY_CATALOG_BLOG + "42");
    }

    private static BlogPostData article(String status) {
        BlogPostData data = new BlogPostData();
        data.setTitle("Summer looks");
        data.setContent("Body");
        data.setStatus(status);
        return data;
    }

    private static BlogPostEntity post(long id, String status) {
        BlogPostEntity post = new BlogPostEntity();
        post.setId(id);
        post.setTitle("Post " + id);
        post.setContent("Body " + id);
        post.setStatus(status);
        return post;
    }

    private String savedStatus(String requested) {
        assertEquals("200", catalogService.createBlogPost(article(requested)).getStatusCode());
        ArgumentCaptor<BlogPostEntity> saved = ArgumentCaptor.forClass(BlogPostEntity.class);
        verify(blogPostRepo).save(saved.capture());
        return saved.getValue().getStatus();
    }

    @Test
    void anArticleWithNoStatusIsPublishedAsBefore() {
        assertEquals("PUBLISHED", savedStatus(null));
    }

    @Test
    void anArticleCanBeSavedAsADraft() {
        assertEquals("DRAFT", savedStatus("draft"));
    }

    @Test
    void anUnknownStatusIsRefused() {
        assertEquals("Status must be DRAFT or PUBLISHED", catalogService.createBlogPost(article("LIVE")).getMessage());
        verify(blogPostRepo, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePublicListLeavesDraftsOut() {
        when(blogPostRepo.findAll()).thenReturn(new ArrayList<>(List.of(post(1, "PUBLISHED"), post(2, "DRAFT"), post(3, "PUBLISHED"))));

        List<Map<String, String>> list = (List<Map<String, String>>) catalogService.listBlogPosts().getData();

        assertEquals(2, list.size());
        assertEquals("3", list.get(0).get("id"));
        assertEquals("1", list.get(1).get("id"));
    }

    @Test
    void aDraftHasNoPublicPage() {
        when(blogPostRepo.findById(2L)).thenReturn(Optional.of(post(2, "DRAFT")));
        when(blogPostRepo.findById(1L)).thenReturn(Optional.of(post(1, "PUBLISHED")));

        assertEquals("400", catalogService.singleBlogPost("2").getStatusCode());
        assertEquals("200", catalogService.singleBlogPost("1").getStatusCode());
    }

    @Test void deletingAnArticleByAnAliasClearsItsCanonicalCacheKey() {
        when(blogPostRepo.findById(1L)).thenReturn(Optional.of(post(1, "PUBLISHED")));
        assertEquals("200", catalogService.deleteBlogPost("0001").getStatusCode());
        verify(cache).evict(ReadCacheService.KEY_CATALOG_BLOGS,
                ReadCacheService.KEY_CATALOG_BLOG + "0001", ReadCacheService.KEY_CATALOG_BLOG + "1");
    }

    @Test
    void publishingADraftDropsBothPublicCacheEntries() {
        BlogPostEntity draft = post(4, "DRAFT");
        when(blogPostRepo.findById(4L)).thenReturn(Optional.of(draft));
        BlogPostData data = article("PUBLISHED");
        data.setId("4");

        assertEquals("200", catalogService.updateBlogPost(data).getStatusCode());

        assertEquals("PUBLISHED", draft.getStatus());
        verify(cache).evict(ReadCacheService.KEY_CATALOG_BLOGS, ReadCacheService.KEY_CATALOG_BLOG + "4",
                ReadCacheService.KEY_CATALOG_BLOG + 4L);
    }

    @Test
    void unpublishingAnArticleDropsBothPublicCacheEntries() {
        BlogPostEntity live = post(5, "PUBLISHED");
        when(blogPostRepo.findById(5L)).thenReturn(Optional.of(live));
        BlogPostData data = article("DRAFT");
        data.setId("5");

        catalogService.updateBlogPost(data);

        assertEquals("DRAFT", live.getStatus());
        verify(cache).evict(ReadCacheService.KEY_CATALOG_BLOGS, ReadCacheService.KEY_CATALOG_BLOG + "5",
                ReadCacheService.KEY_CATALOG_BLOG + 5L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdminListShowsEveryArticleWithItsStatus() {
        when(blogPostRepo.findAll()).thenReturn(new ArrayList<>(List.of(post(1, "PUBLISHED"), post(2, "DRAFT"))));

        List<Map<String, String>> list = (List<Map<String, String>>) catalogService.listAdminBlogPosts().getData();

        assertEquals(2, list.size());
        assertEquals("2", list.get(0).get("id"));
        assertEquals("DRAFT", list.get(0).get("status"));
        assertEquals("Body 2", list.get(0).get("content"));
        assertEquals("PUBLISHED", list.get(1).get("status"));
        verify(cache, never()).getOrLoad(anyString(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdminCanOpenADraft() {
        when(blogPostRepo.findById(2L)).thenReturn(Optional.of(post(2, "DRAFT")));

        Map<String, String> row = (Map<String, String>) catalogService.adminBlogPost("2").getData();

        assertEquals("DRAFT", row.get("status"));
        assertEquals("400", catalogService.adminBlogPost("x").getStatusCode());
    }
}
