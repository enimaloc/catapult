package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.StreamStateService;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminMembersController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminMembersFlagsTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean AccountService accountService;
    @MockitoBean BotToggleService botToggleService;
    @MockitoBean AdminMigrationService adminMigrationService;
    @MockitoBean UserFlagRepository userFlagRepository;
    @MockitoBean UserGroupRepository userGroupRepository;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void setFlag_upserts_value_returns_204() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userFlagRepository.findByUserAndFlagKey(user, "tier")).thenReturn(Optional.empty());
        when(userFlagRepository.save(any(UserFlag.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/members/" + userId + "/flags")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"tier\",\"value\":\"vip\"}"))
                .andExpect(status().isNoContent());

        verify(userFlagRepository).save(any(UserFlag.class));
    }

    @Test
    void setFlag_blank_key_returns_400() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));

        mvc.perform(post("/api/admin/members/" + userId + "/flags")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"   \",\"value\":\"vip\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setFlag_unknown_user_returns_404() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/members/" + userId + "/flags")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"tier\",\"value\":\"vip\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addToGroup_unknown_group_returns_404() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userGroupRepository.findByKey("missing-group")).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/members/" + userId + "/groups")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupKey\":\"missing-group\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addToGroup_happy_path_returns_204() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        UserGroup group = new UserGroup();
        group.setKey("beta");
        group.setName("Beta");
        group.setMembers(new HashSet<>());

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userGroupRepository.findByKey("beta")).thenReturn(Optional.of(group));
        when(userGroupRepository.save(any(UserGroup.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/members/" + userId + "/groups")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupKey\":\"beta\"}"))
                .andExpect(status().isNoContent());

        verify(userGroupRepository).save(any(UserGroup.class));
    }

    @Test
    void toggleBot_delegatesToBotToggleServiceWithFlippedValue() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setBotEnabled(true);
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));

        mvc.perform(post("/api/admin/members/" + userId + "/bot/toggle")
                        .with(adminJwt())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(botToggleService).setBotEnabled(user, false);
    }

    @Test
    void targeting_returns_flags_and_groups_for_member() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount();
        user.setId(userId);

        UserFlag flag = new UserFlag();
        flag.setUser(user);
        flag.setFlagKey("tier");
        flag.setFlagValue("vip");

        UserGroup group = new UserGroup();
        group.setKey("beta");
        group.setName("Beta");
        Set<UserAccount> members = new HashSet<>();
        members.add(user);
        group.setMembers(members);

        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userFlagRepository.findByUser(user)).thenReturn(List.of(flag));
        when(userGroupRepository.findAll()).thenReturn(List.of(group));

        mvc.perform(get("/api/admin/members/" + userId + "/targeting")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags[0].key").value("tier"))
                .andExpect(jsonPath("$.flags[0].value").value("vip"))
                .andExpect(jsonPath("$.groupKeys[0]").value("beta"));
    }
}
