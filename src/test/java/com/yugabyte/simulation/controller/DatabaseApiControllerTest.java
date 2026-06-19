package com.yugabyte.simulation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DatabaseApiControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsInvalidCertificatePem() throws Exception {
        mockMvc.perform(post("/api/v1/database/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certId\":\"bad\",\"certificatePem\":\"not-a-cert\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSslConnectionWithoutCertificate() throws Exception {
        mockMvc.perform(post("/api/v1/database/connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"127.0.0.1\",\"port\":5433,\"databaseName\":\"yugabyte\","
                                + "\"username\":\"yugabyte\",\"password\":\"yugabyte\",\"ssl\":true,"
                                + "\"sslMode\":\"verify-full\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
