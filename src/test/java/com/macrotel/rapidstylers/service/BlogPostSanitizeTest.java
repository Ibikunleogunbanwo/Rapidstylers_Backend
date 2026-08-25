package com.macrotel.rapidstylers.service;

import com.macrotel.rapidstylers.entity.BlogPostEntity;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.pojo.BlogPostData;
import com.macrotel.rapidstylers.repo.BlogPostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BlogPostSanitizeTest {

    private AppService appService;
    private BlogPostRepo blogPostRepo;

    @BeforeEach
    void setUp() {
        appService = new AppService();
        blogPostRepo = mock(BlogPostRepo.class);
        appService.blogPostRepo = blogPostRepo;
    }

    @Test
    void blogTitleContentAndAuthorAreSanitizedBeforeSave() {
        BlogPostData data = new BlogPostData();
        data.setTitle("<script>alert(1)</script> Summer looks");
        data.setCategory("Hair");
        data.setContent("Try our new styles <img src=x onerror=alert(2)> this season.");
        data.setImageUrl("https://example.com/img.jpg");
        data.setAuthor("<b>Admin</b> Team");

        BaseResponse response = appService.createBlogPost(data);

        assertEquals("200", response.getStatusCode());
        ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
        verify(blogPostRepo).save(captor.capture());
        assertEquals("Summer looks", captor.getValue().getTitle());
        assertEquals("Try our new styles this season.", captor.getValue().getContent());
        assertEquals("Admin Team", captor.getValue().getAuthor());
        assertEquals("https://example.com/img.jpg", captor.getValue().getImageUrl());
    }
}
