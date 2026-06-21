package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.BindingService;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("mock-web")
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
        // MockWebDataInitializer creates "mock-admin" + "mock-user-0..N" — pick the first user.
        return userAccountRepository.findByTwitchId("mock-user-0").orElseThrow();
    }

    @Test
    void saveTws_callsBindingService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(post("/api/channel/bindings/{id}/tws", bindingId)
                        .with(jwt().jwt(j -> j.claim("twitchId", user.getTwitchId())))
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
        mvc.perform(post("/api/channel/bindings/{id}/tws/reset", bindingId)
                        .with(jwt().jwt(j -> j.claim("twitchId", user.getTwitchId())))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(bindingService).resetTws(any(UserAccount.class), eq(bindingId));
    }

    @Test
    void toggleTwEnabled_callsService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        UserAccount user = seededUser();

        UUID bindingId = UUID.randomUUID();
        mvc.perform(post("/api/channel/bindings/{id}/tw-enabled", bindingId)
                        .with(jwt().jwt(j -> j.claim("twitchId", user.getTwitchId())))
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

        mvc.perform(get("/api/channel/bindings/{id}/tws/suggest", bindingId)
                        .with(jwt().jwt(j -> j.claim("twitchId", user.getTwitchId()))))
                .andExpect(status().isOk());
    }
}
