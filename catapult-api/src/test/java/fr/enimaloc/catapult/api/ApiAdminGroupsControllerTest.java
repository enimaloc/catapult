package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserGroupRepository;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminGroupsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
class ApiAdminGroupsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserGroupRepository groupRepository;
    @MockitoBean UserAccountRepository userAccountRepository;

    @Test
    void createRejectsDuplicateKey() throws Exception {
        when(groupRepository.existsByKey("beta")).thenReturn(true);
        mvc.perform(post("/api/admin/groups")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"beta\",\"name\":\"Beta\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createPersistsNewGroup() throws Exception {
        when(groupRepository.existsByKey("beta")).thenReturn(false);
        when(groupRepository.save(any(UserGroup.class))).thenAnswer(i -> i.getArgument(0));
        mvc.perform(post("/api/admin/groups")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"beta\",\"name\":\"Beta\"}"))
                .andExpect(status().isCreated());

        verify(groupRepository).save(any(UserGroup.class));
    }

    @Test
    void createRejectsBlankKey() throws Exception {
        mvc.perform(post("/api/admin/groups")
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"\",\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listReturnsGroupSummaries() throws Exception {
        UserGroup group = new UserGroup();
        group.setKey("beta");
        group.setName("Beta");
        group.setMembers(new HashSet<>());
        when(groupRepository.findAll()).thenReturn(List.of(group));

        mvc.perform(get("/api/admin/groups")
                        .with(adminJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void renameUnknownGroupReturns404() throws Exception {
        when(groupRepository.findById(any())).thenReturn(Optional.empty());
        mvc.perform(post("/api/admin/groups/{id}/rename", UUID.randomUUID())
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMemberUnknownUserReturns404() throws Exception {
        UserGroup group = new UserGroup();
        group.setKey("beta");
        group.setName("Beta");
        group.setMembers(new HashSet<>());
        when(groupRepository.findById(any())).thenReturn(Optional.of(group));
        when(userAccountRepository.findByTwitchUsername(any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/groups/{id}/members", UUID.randomUUID())
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twitchUsername\":\"unknown\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMemberHappyPath() throws Exception {
        UserGroup group = new UserGroup();
        group.setKey("beta");
        group.setName("Beta");
        group.setMembers(new HashSet<>());
        UserAccount user = new UserAccount();
        when(groupRepository.findById(any())).thenReturn(Optional.of(group));
        when(userAccountRepository.findByTwitchUsername(any())).thenReturn(Optional.of(user));
        when(groupRepository.save(any(UserGroup.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(post("/api/admin/groups/{id}/members", UUID.randomUUID())
                        .with(adminJwt())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"twitchUsername\":\"streamer\"}"))
                .andExpect(status().isNoContent());

        verify(groupRepository).save(any(UserGroup.class));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.claim("twitchId", "123"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
