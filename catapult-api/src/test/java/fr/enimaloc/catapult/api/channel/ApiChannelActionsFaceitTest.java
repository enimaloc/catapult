package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owner's FACEIT nickname: owner only, a moderator of the channel is refused. */
class ApiChannelActionsFaceitTest extends ApiChannelActionsTestSupport {

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
    void faceitSettings_byAModerator_areForbidden() throws Exception {
        mvc.perform(as(moderator, HttpMethod.PUT, "/settings/faceit/nickname", "{\"nickname\":\"Catapult\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/faceit/nickname", null))
                .andExpect(status().isForbidden());

        verifyNoInteractions(faceitAccountService);
    }

    @Test
    void faceitSettings_byTheOwner_areSavedAndDeleted() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/faceit/nickname", "{\"nickname\":\"Catapult\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/faceit/nickname", null))
                .andExpect(status().isNoContent());

        verify(faceitAccountService).save(owner, "Catapult");
        verify(faceitAccountService).delete(owner);
    }

    @Test
    void anUnknownNickname_isA400_andFaceitOutOfReachA503() throws Exception {
        doThrow(new ApiProblem(ErrorCode.BAD_REQUEST, "Unknown FACEIT nickname"))
                .when(faceitAccountService).save(owner, "Ghost");
        doThrow(new ApiProblem(ErrorCode.SERVICE_UNAVAILABLE, "FACEIT cannot be asked right now"))
                .when(faceitAccountService).save(owner, "Later");

        mvc.perform(as(owner, HttpMethod.PUT, "/settings/faceit/nickname", "{\"nickname\":\"Ghost\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/faceit/nickname", "{\"nickname\":\"Later\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
