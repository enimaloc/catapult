package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
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

/** The owner's Blizzard settings: owner only, a moderator of the channel is refused. */
class ApiChannelActionsBlizzardTest extends ApiChannelActionsTestSupport {

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
    void settings_byAModerator_areForbidden() throws Exception {
        mvc.perform(as(moderator, HttpMethod.PUT, "/settings/blizzard/region", "{\"region\":\"eu\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.PUT, "/settings/blizzard/wow-main",
                "{\"region\":\"eu\",\"characterId\":1}")).andExpect(status().isForbidden());
        mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/blizzard/wow-characters", null))
                .andExpect(status().isForbidden());

        verifyNoInteractions(blizzardAccountService);
    }

    @Test
    void region_byTheOwner_isSaved() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/blizzard/region", "{\"region\":\"kr\"}"))
                .andExpect(status().isNoContent());

        verify(blizzardAccountService).setRegion(owner, "kr");
    }

    @Test
    void wowMain_byTheOwner_isSaved() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/blizzard/wow-main",
                "{\"region\":\"us\",\"characterId\":42}")).andExpect(status().isNoContent());

        verify(blizzardAccountService).setWowMain(owner, BlizzardRegion.US, 42L);
    }

    @Test
    void wowMain_withAnUnknownRegion_is400() throws Exception {
        mvc.perform(as(owner, HttpMethod.PUT, "/settings/blizzard/wow-main",
                "{\"region\":\"cn\",\"characterId\":42}")).andExpect(status().isBadRequest());

        verifyNoInteractions(blizzardAccountService);
    }

    @Test
    void wowCharacters_deleteByTheOwner() throws Exception {
        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/blizzard/wow-characters", null))
                .andExpect(status().isNoContent());

        verify(blizzardAccountService).deleteWowCharacters(owner);
    }

    @Test
    void bungieUnlink_byTheOwnerOnly() throws Exception {
        mvc.perform(as(moderator, HttpMethod.DELETE, "/settings/bungie", null)).andExpect(status().isForbidden());
        verifyNoInteractions(bungieLinkService);

        mvc.perform(as(owner, HttpMethod.DELETE, "/settings/bungie", null)).andExpect(status().isNoContent());
        verify(bungieLinkService).unlink(any());
    }
}
