package com.macrotel.rapidstylers.common.web;

import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.common.service.CacheLoadUnavailableException;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CacheOverloadResponseTest {
    @RestController static class Controller {
        final CatalogService catalog;
        Controller(CatalogService catalog) { this.catalog = catalog; }
        @GetMapping("/services") BaseResponse services() { return catalog.listService(); }
    }
    @Test void saturatedCacheSurvivesServiceErrorHandlingAndReturnsRetryable503() throws Exception {
        ReadCacheService cache = mock(ReadCacheService.class);
        when(cache.getOrLoad(anyString(), any(), any(), any())).thenThrow(new CacheLoadUnavailableException());
        CatalogService catalog = new CatalogService();
        ReflectionTestUtils.setField(catalog, "readCacheService", cache);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Controller(catalog))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/services"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.statusCode").value("503"))
                .andExpect(jsonPath("$.message").value("The service is busy. Please try again shortly."));
    }
}
