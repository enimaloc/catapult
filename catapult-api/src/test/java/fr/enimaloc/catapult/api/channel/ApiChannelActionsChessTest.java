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

/** The owner's chess accounts settings: owner only, a moderator of the channel is refused. */
class ApiChannelActionsChessTest extends ApiChannelActionsTestSupport {

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
    void aModeratorIsForbidden() throws Exception {
        for (String site : new String[]{"chesscom", "lichess"}) {
            mvc.perform(as(moderator, HttpMethod.PUT, "/settings/chess/" + site, "{\"username\":\"hikaru\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/chess/" + site, null))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(chessAccountService);
    }

    @Test
    void theOwnerSavesThenDeletesTheChesscomName() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/chess/chesscom", "{\"username\":\"hikaru\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/chess/chesscom", null))
                .andExpect(status().isNoContent());

        verify(chessAccountService).saveChesscom(owner, "hikaru");
        verify(chessAccountService).deleteChesscom(owner);
    }

    @Test
    void theOwnerSavesThenDeletesTheLichessName() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/chess/lichess", "{\"username\":\"DrNykterstein\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/chess/lichess", null))
                .andExpect(status().isNoContent());

        verify(chessAccountService).saveLichess(owner, "DrNykterstein");
        verify(chessAccountService).deleteLichess(owner);
    }
}
