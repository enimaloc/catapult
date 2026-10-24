package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ActivationOutcome;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.CustomBindingSave;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChannelCustomBindingsController.class)
@Import(WebSecurityConfig.class)
class ChannelCustomBindingsControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = "{\"name\":\"Art\",\"twitchGameId\":\"509660\",\"twitchGameName\":\"Art\","
            + "\"ccls\":[\"Violence\"],\"tws\":[],\"uninterruptible\":true}";
    private static final CustomBindingRequest REQUEST = new CustomBindingRequest("Art", "509660", "Art",
            Set.of("Violence"), Set.of(), true);
    private static final CustomBindingDto DTO = new CustomBindingDto(ID, "Art", "509660", "Art",
            List.of("Violence"), List.of(), true);

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void state_isRelayed_orBadGatewayWithoutAnAnswer() throws Exception {
        when(apiService.customBindings("enimaloc")).thenReturn(
                new CustomBindingsStateDto(true, List.of(DTO), ID, Instant.parse("2026-01-01T00:00:00Z")));
        mvc.perform(get("/channel/enimaloc/custom-bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeId").value(ID.toString()))
                .andExpect(jsonPath("$.bindings[0].name").value("Art"));

        when(apiService.customBindings("other")).thenReturn(null);
        mvc.perform(get("/channel/other/custom-bindings")).andExpect(status().isBadGateway());
    }

    @Test
    void create_relaysTheBodyAndCatapultApisStatus() throws Exception {
        when(apiService.createCustomBinding("enimaloc", REQUEST)).thenReturn(new CustomBindingSave<>(201, DTO, null));
        mvc.perform(post("/channel/enimaloc/custom-bindings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID.toString()));

        when(apiService.createCustomBinding(any(), any())).thenReturn(new CustomBindingSave<>(409, null, "limit_reached"));
        mvc.perform(post("/channel/enimaloc/custom-bindings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("limit_reached"));

        when(apiService.createCustomBinding(any(), any())).thenReturn(new CustomBindingSave<>(409, null, "name_taken"));
        mvc.perform(post("/channel/enimaloc/custom-bindings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value("name_taken"));
    }

    @Test
    void update_relaysHowReapplyingWent() throws Exception {
        when(apiService.updateCustomBinding("enimaloc", ID, REQUEST))
                .thenReturn(new CustomBindingSave<>(200, new CustomBindingSaveResult(DTO, "bot_disabled"), null));
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reapplyOutcome").value("bot_disabled"))
                .andExpect(jsonPath("$.binding.name").value("Art"));

        when(apiService.updateCustomBinding(any(), any(), any())).thenReturn(new CustomBindingSave<>(400, null, "invalid"));
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid"));

        when(apiService.updateCustomBinding(any(), any(), any())).thenReturn(new CustomBindingSave<>(404, null, null));
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteAndDeactivate_answerCatapultApisStatus() throws Exception {
        when(apiService.deleteCustomBinding("enimaloc", ID)).thenReturn(204);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/delete").with(csrf()))
                .andExpect(status().isNoContent());
        when(apiService.deleteCustomBinding("enimaloc", ID)).thenReturn(403);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/delete").with(csrf()))
                .andExpect(status().isForbidden());

        when(apiService.deactivateCustomBinding("enimaloc")).thenReturn(204);
        mvc.perform(post("/channel/enimaloc/custom-bindings/deactivate").with(csrf()))
                .andExpect(status().isNoContent());
        verify(apiService).deactivateCustomBinding("enimaloc");
    }

    @Test
    void activate_appliedIs204() throws Exception {
        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.APPLIED);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void activate_unavailableAndPausedAre409WithTheirCode() throws Exception {
        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.TWITCH_UNAVAILABLE);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("twitch_unavailable"));

        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.BOT_DISABLED);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bot_disabled"));
    }

    @Test
    void activate_failureRefusalAndUnknownKeepTheirStatus() throws Exception {
        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.FAILED);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isBadGateway());
        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.FORBIDDEN);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isForbidden());
        when(apiService.activateCustomBinding("enimaloc", ID)).thenReturn(ActivationOutcome.NOT_FOUND);
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAction_withoutCsrf_isRejected() throws Exception {
        mvc.perform(post("/channel/enimaloc/custom-bindings/" + ID + "/activate")).andExpect(status().isForbidden());
        mvc.perform(post("/me/api-token")).andExpect(status().isForbidden());
    }

    @Test
    void apiTokenStatus_isRelayed() throws Exception {
        when(apiService.apiTokenStatus()).thenReturn(new ApiTokenStatusDto(true, Instant.parse("2026-01-01T00:00:00Z")));
        mvc.perform(get("/me/api-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        when(apiService.apiTokenStatus()).thenReturn(null);
        mvc.perform(get("/me/api-token")).andExpect(status().isBadGateway());
    }

    @Test
    void regenerate_showsTheTokenOnce_neverCacheable() throws Exception {
        when(apiService.regenerateApiToken()).thenReturn(
                new ApiClient.Result<>(200, new ApiTokenCreatedDto("cat_secret", Instant.parse("2026-01-01T00:00:00Z"))));
        mvc.perform(post("/me/api-token").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.token").value("cat_secret"));

        when(apiService.regenerateApiToken()).thenReturn(new ApiClient.Result<>(403, null));
        mvc.perform(post("/me/api-token").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    @Test
    void revoke_answersCatapultApisStatus() throws Exception {
        when(apiService.revokeApiToken()).thenReturn(204);
        mvc.perform(post("/me/api-token/delete").with(csrf())).andExpect(status().isNoContent());
        when(apiService.revokeApiToken()).thenReturn(502);
        mvc.perform(post("/me/api-token/delete").with(csrf())).andExpect(status().isBadGateway());
    }
}
