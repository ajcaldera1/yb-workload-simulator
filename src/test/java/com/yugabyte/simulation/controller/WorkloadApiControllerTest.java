package com.yugabyte.simulation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class WorkloadApiControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsWorkloadsIncludingUserLoginWorkload() throws Exception {
        mockMvc.perform(get("/api/v1/workloads"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.beanName == 'UserLoginWorkload')]").exists());
    }

    @Test
    void returnsUserLoginOperations() throws Exception {
        mockMvc.perform(get("/api/v1/workloads/UserLoginWorkload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operations[?(@.operationId == 'CREATE_TABLES')]").exists())
                .andExpect(jsonPath("$.operations[?(@.operationId == 'SEED_DATA')]").exists())
                .andExpect(jsonPath("$.operations[?(@.operationId == 'RUN_SIMULATION')]").exists());
    }

    @Test
    void rejectsInvalidParameters() throws Exception {
        mockMvc.perform(post("/api/v1/workloads/UserLoginWorkload/operations/SEED_DATA/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{\"Threads\":9999}}"))
                .andExpect(status().isBadRequest());
    }
}
