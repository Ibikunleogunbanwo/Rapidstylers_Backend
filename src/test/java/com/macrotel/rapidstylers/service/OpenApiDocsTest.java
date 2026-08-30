package com.macrotel.rapidstylers.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the OpenAPI/Swagger contract: the design requires generated docs for
 * the public API surface. springdoc auto-scans controllers, so this asserts
 * /v3/api-docs actually renders with the app title and a real endpoint, gated
 * by the shared x-api-key like every other call.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${app.api.key}")
    private String apiKey;

    @Test
    void openApiContractGeneratesWithRealEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("x-api-key", apiKey))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.info.title").value("RapidStylers API"))
                .andExpect(jsonPath("$.paths['/rapid_stylers/search_nearby']").exists())
                .andExpect(jsonPath("$.paths['/rapid_stylers/book_appointment']").exists())
                .andExpect(jsonPath("$.paths['/rapid_stylers/admin/recovery_campaigns']").exists());
    }
}
