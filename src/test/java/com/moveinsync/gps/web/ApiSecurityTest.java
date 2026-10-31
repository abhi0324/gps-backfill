package com.moveinsync.gps.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    static final String BODY = "{\"pings\":[{\"lat\":12.9,\"lon\":77.6,\"ts\":1700000000000,\"accuracyM\":5}],\"stops\":[],\"endTs\":0}";

    @Test void noTokenIs401() throws Exception { mvc.perform(get("/api/v1/trips/t1")).andExpect(status().isUnauthorized()); }

    @Test void employeeCannotWrite() throws Exception {
        mvc.perform(put("/api/v1/trips/t1").header("Authorization", "Bearer dev-employee-acme")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    }
    @Test void otherTenantSeesNotFoundNotData() throws Exception {
        mvc.perform(put("/api/v1/trips/iso1").header("Authorization", "Bearer dev-operator-acme")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trips/iso1").header("Authorization", "Bearer dev-admin-globex")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/trips/iso1").header("Authorization", "Bearer dev-employee-acme")).andExpect(status().isOk());
    }
    @Test void malformedBodyIsGenericAndDoesNotEchoInput() throws Exception {
        mvc.perform(put("/api/v1/trips/t2").header("Authorization", "Bearer dev-operator-acme")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pings\":\"SECRET-PHONE-9999\"}")).andExpect(status().isBadRequest());
    }
}
