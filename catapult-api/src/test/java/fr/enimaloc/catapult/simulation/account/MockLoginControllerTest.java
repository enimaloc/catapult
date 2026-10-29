package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.AuthCodeStore;
import fr.enimaloc.catapult.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * The fictitious login: the choice page in front of Twitch's authorization, and the one-time code a fictitious
 * account gets, handed to catapult-web's callback as the real login does. Only a loopback client reaches either.
 */
class MockLoginControllerTest {

    private static final String AUTHORIZE = "/oauth2/authorization/twitch";
    private static final String WEB_URL = "http://web.test";

    private final FictitiousAccountService accounts = mock(FictitiousAccountService.class);
    private final AuthCodeStore codes = new AuthCodeStore();
    private final JwtService jwtService = new JwtService(Jwts.SIG.HS256.key().build(), -1);
    private final MockLoginNonces nonces = new MockLoginNonces();
    private final MockLoginController.ChoicePage choicePage = new MockLoginController.ChoicePage(accounts, nonces);
    private MockMvc mvc;
    private UserAccount alice;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new MockLoginController(accounts, jwtService, codes, nonces, WEB_URL))
                .addFilters(choicePage)
                .build();
        alice = new UserAccount();
        alice.setId(UUID.randomUUID());
        alice.setTwitchId("mock-a1b2c3d4");
        alice.setTwitchUsername("alice");
        when(accounts.fictitiousAccounts()).thenReturn(List.of(alice));
        when(accounts.fictitious(any(UUID.class))).thenAnswer(call ->
                alice.getId().equals(call.getArgument(0)) ? Optional.of(alice) : Optional.empty());
    }

    @Test
    void choice_page_lists_fictitious_accounts() throws Exception {
        MvcResult result = mvc.perform(get(AUTHORIZE).queryParam("web_state", "s-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn();

        String page = result.getResponse().getContentAsString();
        assertThat(page)
                .contains("Continue with Twitch")
                .contains(AUTHORIZE + "?web_state=s-1&amp;real=1")
                .contains("alice")
                .contains(MockLoginController.LOGIN_PATH + alice.getId());
    }

    @Test
    void choice_page_escapes_account_names() throws Exception {
        alice.setTwitchUsername("<b>alice</b>");

        String page = mvc.perform(get(AUTHORIZE)).andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("<b>alice</b>").contains("&lt;b&gt;alice&lt;/b&gt;");
    }

    @Test
    void real_param_falls_through_to_oauth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", AUTHORIZE);
        request.setParameter("real", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        choicePage.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void other_paths_fall_through() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/xbox");
        MockFilterChain chain = new MockFilterChain();

        choicePage.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void fictitious_login_issues_a_code_and_redirects_to_web_callback() throws Exception {
        MvcResult result = mvc.perform(login(alice.getId().toString())
                        .cookie(new Cookie("catapult_web_state", "s-1")))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(WEB_URL + "/auth/callback?code=").endsWith("&state=s-1");
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        Optional<String> jwt = codes.consume(code);
        assertThat(jwt).isPresent();
        Claims claims = jwtService.validate(jwt.get());
        assertThat(claims.getSubject()).isEqualTo(alice.getId().toString());
        assertThat(claims.get("twitchId", String.class)).isEqualTo("mock-a1b2c3d4");
        assertThat(claims.get("roles", List.class)).containsExactly("ROLE_USER");
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void fictitious_admin_gets_admin_rights() throws Exception {
        when(accounts.isAdmin(alice.getId())).thenReturn(true);

        String location = mvc.perform(login(alice.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        Claims claims = jwtService.validate(codes.consume(code).orElseThrow());
        assertThat(claims.get("roles", List.class)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void unknown_or_real_account_gets_404() throws Exception {
        mvc.perform(login(UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mvc.perform(login("not-a-uuid")).andExpect(status().isNotFound());
    }

    @Test
    void remote_client_gets_404() throws Exception {
        mvc.perform(get(AUTHORIZE).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mvc.perform(login(alice.getId().toString()).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        // What Tomcat's RemoteIpValve leaves once it has resolved the relayed client
        mvc.perform(login(alice.getId().toString()).with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
                .andExpect(status().isNotFound());

        verify(accounts, never()).fictitiousAccounts();
        verify(accounts, never()).fictitious(any(UUID.class));
    }

    /** The login form's POST, with a nonce of the choice page. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String accountId) {
        return post(MockLoginController.LOGIN_PATH + accountId)
                .param(MockLoginController.NONCE_PARAM, nonces.issue());
    }

    @Test
    void login_without_a_valid_nonce_gets_404() throws Exception {
        String path = MockLoginController.LOGIN_PATH + alice.getId();
        mvc.perform(post(path)).andExpect(status().isNotFound());
        mvc.perform(post(path).param(MockLoginController.NONCE_PARAM, "forged")).andExpect(status().isNotFound());

        String nonce = nonces.issue();
        mvc.perform(post(path).param(MockLoginController.NONCE_PARAM, nonce)).andExpect(status().is3xxRedirection());
        mvc.perform(post(path).param(MockLoginController.NONCE_PARAM, nonce)).andExpect(status().isNotFound());
    }

    @Test
    void the_choice_page_nonce_signs_in_once() throws Exception {
        String page = mvc.perform(get(AUTHORIZE)).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher field = java.util.regex.Pattern
                .compile("name=\"" + MockLoginController.NONCE_PARAM + "\" value=\"([^\"]+)\"").matcher(page);
        assertThat(field.find()).isTrue();
        String path = MockLoginController.LOGIN_PATH + alice.getId();

        mvc.perform(post(path).param(MockLoginController.NONCE_PARAM, field.group(1)))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post(path).param(MockLoginController.NONCE_PARAM, field.group(1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void choice_page_matches_path_parameter_variants() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", AUTHORIZE + ";jsessionid=ABC123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        choicePage.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("Continue with Twitch");
    }

    @Test
    void choice_page_matches_under_a_context_path() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ctx" + AUTHORIZE);
        request.setContextPath("/ctx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        choicePage.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("Continue with Twitch");
    }
}
