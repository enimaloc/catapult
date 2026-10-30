package fr.enimaloc.catapult.api.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.binding.BindingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_channel_tw_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
@WithMockUser
class ApiChannelTwControllerTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired UserAccountRepository userAccountRepository;
    @MockitoBean BindingService bindingService;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    private UserAccount seededUser() {
        return member("channel-tw-user", "channel_tw_user");
    }

    /** The member whose Twitch id is {@code twitchId}, created on first use (the context outlives a test). */
    private UserAccount member(String twitchId, String username) {
        return userAccountRepository.findByTwitchId(twitchId).orElseGet(() -> {
            UserAccount account = new UserAccount();
            account.setTwitchId(twitchId);
            account.setTwitchUsername(username);
            account.setStatus(UserAccount.Status.ACTIVE);
            return userAccountRepository.save(account);
        });
    }

    @Test
    void saveTws_callsBindingService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(put("/api/internal/users/{userId}/bindings/{bindingId}/tws", user.getId(), bindingId)
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId())))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"tws\":[\"violence_graphic\",\"vomit\"]}"))
                .andExpect(status().isNoContent());

        verify(bindingService).setTwsForBinding(any(UserAccount.class), eq(bindingId), any());
    }

    @Test
    void reset_callsService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(delete("/api/internal/users/{userId}/bindings/{bindingId}/tws", user.getId(), bindingId)
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId())))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(bindingService).resetTws(any(UserAccount.class), eq(bindingId));
    }

    @Test
    void resetToDefaults_callsService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(post("/api/internal/users/{userId}/bindings/{bindingId}/reset", user.getId(), bindingId)
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId())))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(bindingService).resetToDefaults(any(UserAccount.class), eq(bindingId));
    }

    @Test
    void toggleTwEnabled_callsService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(put("/api/internal/users/{userId}/bindings/{bindingId}/tw-enabled", user.getId(), bindingId)
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId())))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());

        verify(bindingService).toggleTwEnabled(any(UserAccount.class), eq(bindingId), eq(false));
    }

    @Test
    void suggestTws_returnsPreview() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        when(bindingService.previewTws(any(UserAccount.class), eq(bindingId)))
                .thenReturn(Set.of("violence_graphic"));

        mvc.perform(get("/api/internal/users/{userId}/bindings/{bindingId}/tws/suggestions", user.getId(), bindingId)
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId()))))
                .andExpect(status().isOk());
    }

    /** A self route: another member's account (a channel they moderate, even) is refused, its TWs untouched. */
    @Test
    void anotherMembersBindings_areForbidden() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();
        UserAccount other = member("channel-tw-other", "channel_tw_other");

        mvc.perform(put("/api/internal/users/{userId}/bindings/{bindingId}/tws", other.getId(), UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(user.getId().toString()).claim("twitchId", user.getTwitchId())))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"tws\":[]}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bindingService);
    }
}
