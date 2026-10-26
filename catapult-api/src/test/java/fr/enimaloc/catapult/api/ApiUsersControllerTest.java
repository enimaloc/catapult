package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The username lookup catapult-web turns a channel's page URL into the channel's account id with. */
@WebMvcTest(
        controllers = ApiUsersController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiUsersControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UserAccountRepository accounts;

    private final UserAccount streamer = new UserAccount();

    @BeforeEach
    void streamer() {
        streamer.setId(UUID.randomUUID());
        streamer.setTwitchUsername("streamer");
        when(accounts.findByTwitchUsername("streamer")).thenReturn(Optional.of(streamer));
    }

    private static RequestPostProcessor member() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void lookup_knownUsername_answersItsId() throws Exception {
        mvc.perform(get("/api/internal/users").param("twitchUsername", "streamer").with(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(streamer.getId().toString()))
                .andExpect(jsonPath("$.twitchUsername").value("streamer"));
    }

    /** The exact match {@code /api/channels/{username}} made: another case is another, unknown, username. */
    @Test
    void lookup_otherCase_is404() throws Exception {
        mvc.perform(get("/api/internal/users").param("twitchUsername", "Streamer").with(member()))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookup_unknown_is404Problem() throws Exception {
        mvc.perform(get("/api/internal/users").param("twitchUsername", "nobody-here").with(member()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
