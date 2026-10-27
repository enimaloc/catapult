package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiSocialLinksController;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.service.social.SocialLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static fr.enimaloc.catapult.common.dto.admin.SocialPlatform.DISCORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {ApiAdminSocialLinksController.class, ApiSocialLinksController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false) // the admin guard is ApiSecurityConfig's, tested with it
class ApiAdminSocialLinksControllerTest {
    @MockitoBean fr.enimaloc.catapult.service.account.UserApiTokenService userApiTokenService;
    @Autowired MockMvc mvc;
    @MockitoBean SocialLinkService service;

    static final String BODY = "{\"platform\":\"DISCORD\",\"label\":\"Chat\",\"url\":\"https://discord.gg/x\",\"visible\":true}";
    static final SocialLinkRequest REQUEST = new SocialLinkRequest(DISCORD, "Chat", "https://discord.gg/x", true);
    static final SocialLinkDto LINK = new SocialLinkDto(3, DISCORD, "Chat", "https://discord.gg/x", true, 1);

    @Test
    void createAnswers201WithTheLink() throws Exception {
        when(service.create(any())).thenReturn(LINK);
        mvc.perform(post("/api/internal/admin/social-links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.platform").value("DISCORD"));
        verify(service).create(REQUEST);
    }

    @Test
    void listsPubliclyAndForTheAdmin() throws Exception {
        when(service.listVisible()).thenReturn(List.of(LINK));
        when(service.listAll()).thenReturn(List.of(LINK, LINK));
        mvc.perform(get("/api/internal/social-links")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://discord.gg/x"));
        mvc.perform(get("/api/internal/admin/social-links")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void updateDeleteAndReorder() throws Exception {
        when(service.update(eq(3L), any())).thenReturn(LINK);
        when(service.reorder(any())).thenReturn(List.of(LINK));
        mvc.perform(put("/api/internal/admin/social-links/3").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(service).update(3L, REQUEST);
        mvc.perform(delete("/api/internal/admin/social-links/3")).andExpect(status().isNoContent());
        verify(service).delete(3L);
        mvc.perform(put("/api/internal/admin/social-links/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[3,1]}"))
                .andExpect(status().isOk());
        verify(service).reorder(new SocialLinkOrderRequest(List.of(3L, 1L)));
    }

    @Test
    void aRefusalKeepsItsStatus() throws Exception {
        when(service.create(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        mvc.perform(post("/api/internal/admin/social-links").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        when(service.update(eq(9L), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(put("/api/internal/admin/social-links/9").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        when(service.reorder(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        mvc.perform(put("/api/internal/admin/social-links/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
