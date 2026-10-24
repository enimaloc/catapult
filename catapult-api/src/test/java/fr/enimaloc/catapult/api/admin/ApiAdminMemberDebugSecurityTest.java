package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminMemberDebugController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, ApiAdminMemberDebugSecurityTest.Keys.class})
@ActiveProfiles("test")
class ApiAdminMemberDebugSecurityTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Keys {
        @org.springframework.context.annotation.Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean MemberDebugService debugService;
    @MockitoBean DebugExchangeRepository exchanges;
    @MockitoBean UserAccountRepository users;

    private final String base = "/api/admin/members/" + UUID.randomUUID() + "/debug";

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote() {
        return r -> { r.setRemoteAddr("203.0.113.9"); return r; };
    }

    @Test
    void nonAdminJwt_is403() throws Exception {
        mvc.perform(post(base + "/enable").with(remote())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get(base + "/exchanges").with(remote())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(debugService);
    }

    @Test
    void noJwt_is401() throws Exception {
        mvc.perform(get(base).with(remote())).andExpect(status().isUnauthorized());
    }

    @Test
    void adminJwt_reachesTheController() throws Exception {
        mvc.perform(get(base).with(remote()).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }
}
