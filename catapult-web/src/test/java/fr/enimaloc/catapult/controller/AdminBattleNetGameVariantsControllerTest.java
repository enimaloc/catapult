package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminBattleNetGameVariantsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminBattleNetGameVariantsControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BASE = "/admin/battlenet-game-variants";
    static final String BODY = "{\"fourcc\":\"WoW\",\"variantKey\":\"Forever\",\"matchType\":\"PREFIX\","
            + "\"patterns\":[\"Forever - \"],\"displayName\":\"World of Warcraft: Forever\",\"enabled\":true}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private static BattleNetGameVariantDto dto() {
        return new BattleNetGameVariantDto(ID, "WoW", "Forever", "WoW:Forever", "PREFIX", List.of("Forever - "),
                "World of Warcraft: Forever", true, Instant.parse("2026-11-21T10:00:00Z"), "alice");
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    @Test
    void createForwardsTheBodyAndTheApiStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        var expected = new BattleNetGameVariantRequest("WoW", "Forever", "PREFIX", List.of("Forever - "),
                "World of Warcraft: Forever", true);
        when(apiService.adminCreateBattleNetGameVariant(expected)).thenReturn(new ApiClient.Result<>(201, dto()));

        mvc.perform(admin(post(BASE))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceId").value("WoW:Forever"))
                .andExpect(jsonPath("$.patterns[0]").value("Forever - "));
    }

    @Test
    void conflictIsForwarded() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateBattleNetGameVariant(any())).thenReturn(new ApiClient.Result<>(409, null));

        mvc.perform(admin(post(BASE))).andExpect(status().isConflict());
    }

    @Test
    void updateAndDeleteRelayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminUpdateBattleNetGameVariant(any(), any())).thenReturn(new ApiClient.Result<>(200, dto()));
        when(apiService.adminDeleteBattleNetGameVariant(ID)).thenReturn(new ApiClient.Result<>(204, null));

        mvc.perform(admin(put(BASE + "/" + ID))).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(admin(delete(BASE + "/" + ID))).andExpect(status().isNoContent());
    }

    @Test
    void unreachableApiIs502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateBattleNetGameVariant(any())).thenReturn(new ApiClient.Result<>(502, null));

        mvc.perform(admin(post(BASE))).andExpect(status().isBadGateway());
    }

    @Test
    void nonAdminIsRefused() throws Exception {
        for (MockHttpServletRequestBuilder route : List.of(post(BASE), put(BASE + "/" + ID), delete(BASE + "/" + ID))) {
            mvc.perform(route.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder route : List.of(post(BASE), put(BASE + "/" + ID), delete(BASE + "/" + ID))) {
            mvc.perform(route.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        verify(apiService, never()).adminCreateBattleNetGameVariant(any());
        verify(apiService, never()).adminUpdateBattleNetGameVariant(any(), any());
        verify(apiService, never()).adminDeleteBattleNetGameVariant(any());
    }
}
