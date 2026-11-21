package com.hyperdrive.backend.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    private final MockMvc mockMvc;

    @Autowired
    HealthControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsUpWithServiceNameOnly() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"status":"UP","service":"hyperdrive-backend"}
                        """, JsonCompareMode.STRICT))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsNonGetMethods() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void doesNotServeOtherPaths() throws Exception {
        mockMvc.perform(get("/api/v1/health/details"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthResponseHasExactlyTwoFields() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(jsonPath("$.length()").value(2));
    }
}
