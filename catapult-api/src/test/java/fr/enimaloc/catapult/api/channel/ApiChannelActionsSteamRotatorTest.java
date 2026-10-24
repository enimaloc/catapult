package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With Steam enabled the key pool's rotator exists: a member's personal token changes refresh it. */
class ApiChannelActionsSteamRotatorTest extends ApiChannelActionsTestSupport {

    @MockitoBean SteamApiKeyRotator rotator;

    private UserAccount owner;

    @BeforeEach
    void setUp() {
        owner = registered("streamer");
        when(channelAccessService.canAccess(any(), eq(owner))).thenReturn(true);
        when(steamProfileDiagnostics.diagnose(owner)).thenReturn(new SteamProfileDto(true, false, false, false, 15L, true, true));
        when(tokenEncryptionService.encrypt("KEY")).thenReturn("enc");
    }

    @Test
    void save_refreshesTheRotator() throws Exception {
        mvc.perform(post("/api/channels/streamer/settings/steam-personal-token")
                        .with(userJwt(owner.getId())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"KEY\",\"shared\":true}"))
                .andExpect(status().isNoContent());

        verify(rotator).refreshKeys();
    }

    @Test
    void delete_refreshesTheRotator() throws Exception {
        mvc.perform(post("/api/channels/streamer/settings/steam-personal-token/delete")
                        .with(userJwt(owner.getId())).with(csrf()))
                .andExpect(status().isNoContent());

        verify(rotator).refreshKeys();
    }
}
