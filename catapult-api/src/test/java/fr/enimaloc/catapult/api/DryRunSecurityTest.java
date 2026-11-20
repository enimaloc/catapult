package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.admin.ApiAdminMemberDryRunController;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dryrun.MemberDryRun;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ApiSecurityConfig;
import fr.enimaloc.catapult.security.Authz;
import fr.enimaloc.catapult.security.MethodSecurityConfig;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.dryrun.DryRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may switch a member's dry run, through the real security chain: an admin both ways, the member only off. */
@WebMvcTest(
        controllers = {ApiAdminMemberDryRunController.class, ApiMeDryRunController.class},
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import({ApiSecurityConfig.class, DryRunSecurityTest.Keys.class, ApiUserResolver.class, MethodSecurityConfig.class,
        Authz.class})
@ActiveProfiles("test")
class DryRunSecurityTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Keys {
        @org.springframework.context.annotation.Bean
        SecretKey jwtSecretKey() {
            return new SecretKeySpec(new byte[32], "HmacSHA256");
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean fr.enimaloc.catapult.service.account.UserApiTokenService userApiTokenService;
    @MockitoBean DryRunService dryRunService;
    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ChannelAccessService channelAccessService;

    private static RequestPostProcessor remote() {
        return r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        };
    }

    private static RequestPostProcessor as(UserAccount account, String role) {
        return jwt().jwt(j -> j.subject(account.getId().toString()).claim("twitchId", account.getTwitchId()))
                .authorities(new SimpleGrantedAuthority(role));
    }

    private UserAccount registered(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(username + "-id");
        when(userAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void onlyAnAdminCanEnable() throws Exception {
        UserAccount member = registered("member");
        UserAccount admin = registered("admin");
        String enable = ApiPaths.ADMIN + "/members/" + member.getId() + "/dry-run/enable";

        mvc.perform(post(enable).with(remote()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(enable).with(remote()).with(as(member, "ROLE_USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dryRunService);

        MemberDryRun row = new MemberDryRun();
        row.setUserId(member.getId());
        row.setEnabledAt(Instant.parse("2026-11-20T10:00:00Z"));
        when(dryRunService.enable(any(), any())).thenReturn(row);
        mvc.perform(post(enable).with(remote()).with(as(admin, "ROLE_ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        verify(dryRunService).enable(admin.getId(), member.getId());
    }

    @Test
    void aMemberCanOnlyDisableTheirOwn() throws Exception {
        UserAccount member = registered("member");
        UserAccount other = registered("other");

        mvc.perform(post(ApiPaths.ADMIN + "/members/" + other.getId() + "/dry-run/disable").with(remote())
                        .with(as(member, "ROLE_USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/users/" + other.getId() + "/dry-run/disable").with(remote())
                        .with(as(member, "ROLE_USER")).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(dryRunService);

        mvc.perform(post("/api/internal/users/" + member.getId() + "/dry-run/disable").with(remote())
                        .with(as(member, "ROLE_USER")).with(csrf()))
                .andExpect(status().isNoContent());
        verify(dryRunService).disable(member.getId(), member.getId());
    }
}
