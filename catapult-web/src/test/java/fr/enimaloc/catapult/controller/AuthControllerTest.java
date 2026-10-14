package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import(WebSecurityConfig.class)
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void callback_storesTheJwtInTheSessionAndOpensTheMembersOwnChannel() throws Exception {
        when(apiService.exchangeCode("code-1")).thenReturn(new TokenResponse("the-jwt"));
        when(apiService.channelList()).thenReturn(new ChannelListResponse("2", List.of(
                channel("1", "moderated"), channel("2", "own"))));

        mvc.perform(get("/auth/callback").param("code", "code-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/channel/own"))
                .andExpect(request().sessionAttribute(ApiClient.SESSION_JWT_KEY, "the-jwt"));
    }

    @Test
    void callback_failedExchange_goesBackToLoginWithAnError() throws Exception {
        when(apiService.exchangeCode("expired")).thenReturn(null);

        mvc.perform(get("/auth/callback").param("code", "expired"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(request().sessionAttributeDoesNotExist(ApiClient.SESSION_JWT_KEY));
    }

    @Test
    void mockLoginPage_doesNotExistOutsideTheMockProfile() throws Exception {
        mvc.perform(get("/oauth2/authorization/twitch")).andExpect(status().isNotFound());
    }

    @Test
    void home_isTheFirstChannel_whenTheMembersOwnIsNotListed() {
        assertThat(AuthController.home(new ChannelListResponse("9", List.of(channel("1", "a"), channel("2", "b")))))
                .isEqualTo("/channel/a");
    }

    @Test
    void home_isTheLanding_withoutAnyChannel() {
        assertThat(AuthController.home(new ChannelListResponse("9", List.of()))).isEqualTo("/");
        assertThat(AuthController.home(null)).isEqualTo("/");
    }

    private static ChannelDto channel(String twitchId, String username) {
        return new ChannelDto(UUID.randomUUID(), twitchId, username, "img", false);
    }
}
