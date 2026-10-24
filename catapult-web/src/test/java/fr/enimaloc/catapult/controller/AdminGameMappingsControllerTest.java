package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.common.dto.admin.TwitchCategoryDto;
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
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminGameMappingsController.class)
@Import({WebSecurityConfig.class, ModelFiller.class})
class AdminGameMappingsControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    static final String BASE = "/admin/game-mappings";
    static final String BODY = "{\"sourceType\":\"STEAM\",\"sourceId\":\"730\",\"twitchGameId\":\"32399\","
            + "\"twitchGameName\":\"Counter-Strike\",\"ccls\":null,\"tws\":[\"violence\"],\"applyLive\":true}";

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private static GameMappingResult result() {
        return new GameMappingResult(new GameMappingDto(ID, "STEAM", "730", "CS", "32399", "Counter-Strike",
                null, Set.of("violence"), 3, Instant.parse("2026-10-24T10:00:00Z"), "alice"), 3, 1, 0);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    @Test
    void create_forwardsTheBody_andRelaysStatusAndResult() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        var expected = new GameMappingRequest("STEAM", "730", "32399", "Counter-Strike", null, Set.of("violence"), true);
        when(apiService.adminCreateGameMapping(expected)).thenReturn(new ApiClient.Result<>(201, result()));

        mvc.perform(admin(post(BASE))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mapping.id").value(ID.toString()))
                .andExpect(jsonPath("$.bindingsUpdated").value(3))
                .andExpect(jsonPath("$.liveChannelsUpdated").value(1));
    }

    @Test
    void create_relays400And409_withoutABody() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateGameMapping(any())).thenReturn(new ApiClient.Result<>(409, null));

        mvc.perform(admin(post(BASE))).andExpect(status().isConflict());

        when(apiService.adminCreateGameMapping(any())).thenReturn(new ApiClient.Result<>(400, null));
        mvc.perform(admin(post(BASE))).andExpect(status().isBadRequest());
    }

    @Test
    void update_andDelete_relayTheirStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminUpdateGameMapping(any(), any())).thenReturn(new ApiClient.Result<>(200, result()));
        when(apiService.adminDeleteGameMapping(ID, true)).thenReturn(new ApiClient.Result<>(200,
                new GameMappingResult(null, 3, 1, 0)));
        when(apiService.adminDeleteGameMapping(ID, false)).thenReturn(new ApiClient.Result<>(404, null));

        mvc.perform(admin(put(BASE + "/" + ID))).andExpect(status().isOk())
                .andExpect(jsonPath("$.mapping.twitchGameName").value("Counter-Strike"));
        mvc.perform(admin(delete(BASE + "/" + ID + "?applyLive=true"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.bindingsUpdated").value(3));
        mvc.perform(admin(delete(BASE + "/" + ID))).andExpect(status().isNotFound());
    }

    @Test
    void unreachable_is502() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateGameMapping(any())).thenReturn(new ApiClient.Result<>(502, null));
        when(apiService.adminDetectedGames("STEAM", "")).thenReturn(null);

        mvc.perform(admin(post(BASE))).andExpect(status().isBadGateway());
        mvc.perform(get(BASE + "/detected?sourceType=STEAM").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void lookups_answerJson_andDetectedNeedsAPlatform() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDetectedGames("STEAM", "cs")).thenReturn(List.of(new DetectedGameDto("STEAM", "730", "CS", 4)));
        when(apiService.adminSearchCategories("count")).thenReturn(List.of(new TwitchCategoryDto("32399", "Counter-Strike", null)));

        mvc.perform(get(BASE + "/detected?sourceType=STEAM&q=cs").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sourceId").value("730"))
                .andExpect(jsonPath("$[0].streamerCount").value(4));
        mvc.perform(get(BASE + "/categories?q=count").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Counter-Strike"));
        mvc.perform(get(BASE + "/detected").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyRoute_refusesVisitorsAndMembers_andActionsNeedCsrf() throws Exception {
        List<MockHttpServletRequestBuilder> routes = List.of(post(BASE), put(BASE + "/" + ID),
                delete(BASE + "/" + ID), get(BASE + "/detected?sourceType=STEAM"), get(BASE + "/categories?q=a"));
        for (MockHttpServletRequestBuilder route : routes) {
            mvc.perform(route.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder route : List.of(post(BASE), put(BASE + "/" + ID), delete(BASE + "/" + ID),
                get(BASE + "/detected?sourceType=STEAM"), get(BASE + "/categories?q=a"))) {
            mvc.perform(route.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        when(apiService.isAdmin()).thenReturn(true);
        mvc.perform(post(BASE).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verify(apiService, never()).adminCreateGameMapping(any());
        verify(apiService, never()).adminUpdateGameMapping(any(), any());
        verify(apiService, never()).adminDeleteGameMapping(any(), anyBoolean());
        verify(apiService, never()).adminDetectedGames(anyString(), anyString());
        verify(apiService, never()).adminSearchCategories(anyString());
    }
}
