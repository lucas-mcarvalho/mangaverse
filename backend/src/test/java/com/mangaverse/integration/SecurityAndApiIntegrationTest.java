package com.mangaverse.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SecurityAndApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void apiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/library")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reader/chapters/00000000-0000-0000-0000-000000000000/pages/0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void loginWithCsrfCreatesSessionAndWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"senha-de-teste-123\"}"))
                .andExpect(status().isForbidden()); // sem token CSRF

        mvc.perform(post("/api/auth/login").with(SecurityMockMvcRequestPostProcessors.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"errada\"}"))
                .andExpect(status().isUnauthorized());

        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"senha-de-teste-123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"))
                .andReturn().getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/library").session(session)).andExpect(status().isOk());
    }

    @Test
    void storageAndImageEndpointsRejectUnsafeTargets() throws Exception {
        var user = SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN");
        mvc.perform(get("/api/library/browse").param("path", "/etc").with(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/library/browse").param("path", STORAGE + "/../../etc").with(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/library/import").with(user).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/etc\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/images").param("src", "http://169.254.169.254/latest/meta-data").with(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/images").param("src", "https://evil.example/x.png").with(user)).andExpect(status().isForbidden());
        mvc.perform(get("/api/search").param("q", "").with(user)).andExpect(status().isBadRequest());
    }
}
