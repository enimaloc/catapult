package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owner's RetroAchievements account settings: owner only, a moderator of the channel is refused. */
class ApiChannelActionsRetroAchievementsTest extends ApiChannelActionsTestSupport {

    private UserAccount owner;
    private UserAccount moderator;

    @BeforeEach
    void setUp() {
        owner = registered("streamer");
        moderator = registered("mod");
        when(channelAccessService.canAccess(any(), eq(owner))).thenReturn(true);
    }

    private MockHttpServletRequestBuilder as(UserAccount user, HttpMethod method, String path, String json) {
        MockHttpServletRequestBuilder request = request(method, "/api/internal/users/" + owner.getId() + path)
                .with(userJwt(user.getId())).with(csrf());
        return json == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void apiKey_byAModerator_isForbidden() throws Exception {
        mvc.perform(as(moderator, HttpMethod.PUT, "/settings/retroachievements/api-key", "{\"apiKey\":\"abcdefghij0123456789\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/retroachievements/api-key", null))
                .andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.PUT, "/settings/retroachievements/username", "{\"username\":\"n1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/retroachievements/username", null))
                .andExpect(status().isForbidden());

        verifyNoInteractions(retroAchievementsAccountService);
    }

    @Test
    void apiKey_byTheOwner_isSaved() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/retroachievements/api-key", "{\"apiKey\":\"abcdefghij0123456789\"}"))
                .andExpect(status().isNoContent());

        verify(retroAchievementsAccountService).saveApiKey(owner, "abcdefghij0123456789");
    }

    @Test
    void apiKey_deleteByTheOwner() throws Exception {
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/retroachievements/api-key", null))
                .andExpect(status().isNoContent());

        verify(retroAchievementsAccountService).deleteApiKey(owner);
    }

    @Test
    void username_byTheOwner_isSavedThenDeleted() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/retroachievements/username", "{\"username\":\"n1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/retroachievements/username", null))
                .andExpect(status().isNoContent());

        verify(retroAchievementsAccountService).saveUsername(owner, "n1");
        verify(retroAchievementsAccountService).deleteUsername(owner);
    }
}
