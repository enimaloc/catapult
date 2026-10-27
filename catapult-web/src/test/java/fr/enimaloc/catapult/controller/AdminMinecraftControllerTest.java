package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.service.http.ApiError;
import fr.enimaloc.catapult.service.http.ApiErrorText;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.StaticMessageSource;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminMinecraftController.class)
@Import({WebSecurityConfig.class, ModelFiller.class, AdminMinecraftControllerTest.Texts.class})
class AdminMinecraftControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final String BASE = "/admin/minecraft-accounts";

    @TestConfiguration
    static class Texts {
        @Bean
        StaticMessageSource messageSource() {
            StaticMessageSource messages = new StaticMessageSource();
            messages.addMessage("admin.minecraft.default-label", Locale.FRENCH, "Compte Minecraft");
            messages.addMessage("admin.minecraft.default-label", Locale.ENGLISH, "Minecraft account");
            messages.addMessage("api.error.minecraft_device_code_refused", Locale.FRENCH, "Device-code Microsoft refusé: {0}");
            messages.addMessage("api.error.minecraft_device_code_refused", Locale.ENGLISH, "Microsoft refused the device code: {0}");
            return messages;
        }

        @Bean
        ApiErrorText apiErrorText(StaticMessageSource messageSource) {
            return new ApiErrorText(messageSource);
        }
    }

    private static ApiError problem(String json) throws Exception {
        tools.jackson.databind.JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        return new ApiError(fr.enimaloc.catapult.common.error.ErrorCode.fromCode(node.get("code").stringValue()).orElse(null),
                null, node);
    }

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean fr.enimaloc.catapult.controller.twitchat.TwitchatEventForms twitchatEventForms;

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void enabledAndResetLimit_patchTheAccount_andRelayTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminPatchMinecraftAccount(ID, Map.of("enabled", false))).thenReturn(200);
        when(apiService.adminPatchMinecraftAccount(ID, Map.of("friendLimitReached", false))).thenReturn(404);

        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(admin(post(BASE + "/" + ID + "/reset-limit"), "{}")).andExpect(status().isNotFound());
        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminPatchMinecraftAccount(ID, Map.of());
    }

    @Test
    void delete_withLinks_is409() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminDeleteMinecraftAccount(ID)).thenReturn(409);

        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isConflict());
        verify(apiService).adminDeleteMinecraftAccount(ID);
    }

    @Test
    void deviceCode_isAnsweredAsJson_orRelaysTheStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminMinecraftDeviceCode())
                .thenReturn(new ApiService.MinecraftDeviceCodeStart(200,
                        new MinecraftDeviceCodeDto("dc-1", "ABCD-1234", "https://www.microsoft.com/link", 5, 900), null))
                .thenReturn(new ApiService.MinecraftDeviceCodeStart(502, null, null))
                .thenReturn(new ApiService.MinecraftDeviceCodeStart(200, null, null));

        mvc.perform(admin(post(BASE + "/device-code"), "{}")).andExpect(status().isOk())
                .andExpect(content().json("{\"deviceCode\":\"dc-1\",\"userCode\":\"ABCD-1234\","
                        + "\"verificationUri\":\"https://www.microsoft.com/link\",\"interval\":5,\"expiresIn\":900}", org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(admin(post(BASE + "/device-code"), "{}")).andExpect(status().isBadGateway())
                .andExpect(content().json("{}", org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(admin(post(BASE + "/device-code"), "{}")).andExpect(status().isBadGateway());
    }

    @Test
    void deviceCodeRefused_showsTheSameFrenchLine() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminMinecraftDeviceCode()).thenReturn(new ApiService.MinecraftDeviceCodeStart(502, null,
                problem("{\"code\":\"minecraft_device_code_refused\",\"upstream\":\"AADSTS70002\"}")));

        mvc.perform(admin(post(BASE + "/device-code"), "{}").locale(Locale.FRENCH)).andExpect(status().isBadGateway())
                .andExpect(content().json("{\"message\":\"Device-code Microsoft refusé: AADSTS70002\"}", org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(admin(post(BASE + "/device-code"), "{}").locale(Locale.ENGLISH)).andExpect(status().isBadGateway())
                .andExpect(content().json("{\"message\":\"Microsoft refused the device code: AADSTS70002\"}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void deviceCodeOtherError_hasNoMessage() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminMinecraftDeviceCode())
                .thenReturn(new ApiService.MinecraftDeviceCodeStart(404, null, problem("{\"code\":\"not_found\"}")))
                .thenReturn(new ApiService.MinecraftDeviceCodeStart(502, null, null));

        mvc.perform(admin(post(BASE + "/device-code"), "{}").locale(Locale.FRENCH)).andExpect(status().isNotFound())
                .andExpect(content().json("{}", org.springframework.test.json.JsonCompareMode.STRICT));
        mvc.perform(admin(post(BASE + "/device-code"), "{}").locale(Locale.FRENCH)).andExpect(status().isBadGateway())
                .andExpect(content().json("{}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void create_relays202Then200() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateMinecraftAccount("dc-1", "Bot1"))
                .thenReturn(new ApiClient.Result<>(202, Map.of("status", "PENDING")))
                .thenReturn(new ApiClient.Result<>(200, Map.of("id", ID.toString(), "label", "Bot1",
                        "minecraftUsername", "CatapultBot1", "linkCount", 0)));

        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"dc-1\",\"label\":\" Bot1 \"}"))
                .andExpect(status().isAccepted());
        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"dc-1\",\"label\":\"Bot1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"minecraftUsername\":\"CatapultBot1\",\"label\":\"Bot1\"}",
                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void create_anErrorIsRelayedAsItsStatusAlone_andABlankLabelIsFilledWithTheBundlesDefault() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateMinecraftAccount("dc-1", "Minecraft account"))
                .thenReturn(new ApiClient.Result<>(502, Map.of("message", "Xbox chain failed", "trace", "x")));

        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"dc-1\",\"label\":\"  \"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("{}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void create_withoutALabel_sendsTheDefaultInTheAdminsLanguage() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminCreateMinecraftAccount(any(), any())).thenReturn(new ApiClient.Result<>(202, null));

        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"c\",\"label\":\"  \"}").locale(Locale.FRENCH))
                .andExpect(status().isAccepted());
        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"c\",\"label\":\"  \"}").locale(Locale.ENGLISH))
                .andExpect(status().isAccepted());
        verify(apiService).adminCreateMinecraftAccount("c", "Compte Minecraft");
        verify(apiService).adminCreateMinecraftAccount("c", "Minecraft account");
    }

    @Test
    void reauth_relaysItsStatus() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.adminReauthMinecraftAccount(ID, "dc-2"))
                .thenReturn(new ApiClient.Result<>(202, null))
                .thenReturn(new ApiClient.Result<>(200, Map.of("label", "Bot1", "minecraftUsername", "CatapultBot1")))
                .thenReturn(new ApiClient.Result<>(404, null));

        mvc.perform(admin(post(BASE + "/" + ID + "/reauth"), "{\"deviceCode\":\"dc-2\"}")).andExpect(status().isAccepted());
        mvc.perform(admin(post(BASE + "/" + ID + "/reauth"), "{\"deviceCode\":\"dc-2\"}")).andExpect(status().isOk())
                .andExpect(content().json("{\"minecraftUsername\":\"CatapultBot1\",\"label\":\"Bot1\"}"));
        mvc.perform(admin(post(BASE + "/" + ID + "/reauth"), "{\"deviceCode\":\"dc-2\"}")).andExpect(status().isNotFound())
                .andExpect(content().json("{}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void aBlankDeviceCode_is400_withoutCallingTheApi() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(admin(post(BASE + "/create"), "{\"label\":\"Bot1\"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE + "/" + ID + "/reauth"), "{\"deviceCode\":\" \"}")).andExpect(status().isBadRequest());
        verify(apiService, never()).adminCreateMinecraftAccount(any(), any());
        verify(apiService, never()).adminReauthMinecraftAccount(any(), any());
    }

    @Test
    void visitorIs401_memberIs403_andTheApiIsNeverCalled() throws Exception {
        mvc.perform(post(BASE + "/device-code").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        when(apiService.isAdmin()).thenReturn(false);
        mvc.perform(admin(post(BASE + "/device-code"), "{}")).andExpect(status().isForbidden());
        mvc.perform(admin(post(BASE + "/create"), "{\"deviceCode\":\"dc\"}")).andExpect(status().isForbidden());
        mvc.perform(admin(post(BASE + "/" + ID + "/delete"), "{}")).andExpect(status().isForbidden());
        mvc.perform(admin(post(BASE + "/" + ID + "/enabled"), "{\"enabled\":true}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminMinecraftDeviceCode();
        verify(apiService, never()).adminCreateMinecraftAccount(any(), any());
        verify(apiService, never()).adminDeleteMinecraftAccount(any());
        verify(apiService, never()).adminPatchMinecraftAccount(any(), any());
    }

    @Test
    void withoutCsrf_isRefused() throws Exception {
        when(apiService.isAdmin()).thenReturn(true);

        mvc.perform(post(BASE + "/" + ID + "/delete").sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(apiService, never()).adminDeleteMinecraftAccount(any());
    }
}
