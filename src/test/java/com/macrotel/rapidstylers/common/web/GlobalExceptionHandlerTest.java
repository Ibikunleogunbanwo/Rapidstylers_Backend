package com.macrotel.rapidstylers.common.web;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Client mistakes answer 4xx with a plain message; server faults answer 500
 * without internal detail. Runs a stand-in controller, no database.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class SampleController {
        @PostMapping("/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping("/count")
        int count(@RequestParam("n") int n) {
            return n;
        }

        @GetMapping("/parse")
        long parse(@RequestParam("id") String id) {
            return Long.parseLong(id);
        }

        @GetMapping("/refused")
        String refused() {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("jdbc:mysql://db-host:3306 password=secret");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value("400"));
    }

    @Test
    void missingParameterIs400AndNamesIt() throws Exception {
        mockMvc.perform(get("/count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter: n"));
    }

    @Test
    void wrongParameterTypeIs400() throws Exception {
        mockMvc.perform(get("/count").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter: n"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(get("/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.statusCode").value("405"));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mockMvc.perform(post("/echo").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void badNumberHidesJavasWording() throws Exception {
        mockMvc.perform(get("/parse").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("For input string"))));
    }

    @Test
    void serverFaultIs500WithoutInternalDetail() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later."))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void aChosenStatusIsKeptNotTurnedInto500() throws Exception {
        mockMvc.perform(get("/refused"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value("401"))
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"));
    }
}
