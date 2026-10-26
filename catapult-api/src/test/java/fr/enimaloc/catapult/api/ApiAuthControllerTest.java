package fr.enimaloc.catapult.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.enimaloc.catapult.api.error.ProblemAdvice;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.AuthCodeStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ApiAuthControllerTest {

    @Mock UserAccountRepository accounts;
    @Mock AuthCodeStore codeStore;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiAuthController(codeStore, new ApiUserResolver(accounts)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new ProblemAdvice())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(UUID subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(subject.toString())
                .claim("roles", List.of("ROLE_USER")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void validate_deletedAccount_is401() throws Exception {
        UUID gone = UUID.randomUUID();
        when(accounts.findById(gone)).thenReturn(Optional.empty());
        signedInAs(gone);

        mvc.perform(get("/api/auth/validate"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void me_deletedAccount_is401() throws Exception {
        UUID gone = UUID.randomUUID();
        when(accounts.findById(gone)).thenReturn(Optional.empty());
        signedInAs(gone);

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void validate_existingAccount_answersItsInfo() throws Exception {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId("tid");
        account.setTwitchUsername("streamer");
        account.setStatus(UserAccount.Status.ACTIVE);
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        signedInAs(account.getId());

        mvc.perform(get("/api/auth/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.username").value("streamer"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
    }
}
