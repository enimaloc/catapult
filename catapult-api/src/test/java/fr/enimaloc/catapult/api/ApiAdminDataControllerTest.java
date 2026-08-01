package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_admin_data_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
@WithMockUser(roles = "ADMIN")
class ApiAdminDataControllerTest {

    @Autowired WebApplicationContext wac;
    @Autowired TwDefinitionRepository twDefinitionRepository;
    @Autowired UserGroupRepository userGroupRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired ChatCommandDefinitionRepository chatCommandDefinitionRepository;

    @Test
    void listRepositories_includesKnownRepos() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='tw-definition')]").exists());
    }

    @Test
    void entityList_paginatesAndSearches() throws Exception {
        twDefinitionRepository.deleteAll();
        for (int i = 0; i < 3; i++) {
            TwDefinition d = new TwDefinition();
            d.setId("tw-" + i);
            d.setLabel(i == 1 ? "Flashing lights" : "Other " + i);
            twDefinitionRepository.save(d);
        }

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/tw-definition").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3));

        mvc.perform(get("/api/admin/data/tw-definition").param("q", "flashing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value("tw-1"));
    }

    @Test
    void entityList_rowsExposeIdAndBasicFields() throws Exception {
        twDefinitionRepository.deleteAll();
        TwDefinition d = new TwDefinition();
        d.setId("solo-row");
        d.setLabel("Solo row");
        twDefinitionRepository.save(d);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/tw-definition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("solo-row"))
                .andExpect(jsonPath("$.content[0].label").value("Solo row"));
    }

    @Test
    void entityList_rowWithLazySingularRelation_returns200WithRenderedLabel() throws Exception {
        chatCommandDefinitionRepository.deleteAll();

        UserAccount owner = new UserAccount();
        owner.setTwitchUsername("list-owner-" + java.util.UUID.randomUUID());
        owner = userAccountRepository.save(owner);

        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setUser(owner);
        definition.setName("greet");
        definition.setTemplate("Hello!");
        definition.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        chatCommandDefinitionRepository.save(definition);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        // entityList reads TwDefinition/ChatCommandDefinition rows outside any repository-level
        // transaction; ChatCommandDefinition.user is a FetchType.LAZY @ManyToOne, so the row it
        // returns here is fetched by a fresh JPQL query — its "user" relation is an uninitialized
        // Hibernate proxy at read time. Without @Transactional(readOnly = true) on entityList and
        // proxy-safe label resolution, this used to throw LazyInitializationException /
        // IllegalArgumentException instead of returning 200.
        mvc.perform(get("/api/admin/data/chat-command-definition"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].user").exists())
                .andExpect(jsonPath("$.content[0].user_label").value(
                        org.hamcrest.Matchers.containsString("list-owner")));
    }

    @Test
    void unknownRepo_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void entityDetail_returnsFullRow() throws Exception {
        twDefinitionRepository.deleteAll();
        TwDefinition d = new TwDefinition();
        d.setId("detail-row");
        d.setLabel("Detail row");
        d.setDescription("A description");
        twDefinitionRepository.save(d);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/tw-definition/detail-row"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("detail-row"))
                .andExpect(jsonPath("$.description").value("A description"));
    }

    @Test
    void entityDetail_unknownId_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/tw-definition/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_savesNewRow() throws Exception {
        twDefinitionRepository.deleteAll();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(post("/api/admin/data/tw-definition").with(csrf())
                        .contentType("application/json")
                        .content("{\"id\":\"new-row\",\"label\":\"New row\",\"enabled\":\"true\",\"sortOrder\":\"1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-row"));

        mvc.perform(get("/api/admin/data/tw-definition/new-row"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("New row"));
    }

    @Test
    void update_savesChangesToExistingRow() throws Exception {
        twDefinitionRepository.deleteAll();
        TwDefinition d = new TwDefinition();
        d.setId("update-row");
        d.setLabel("Old label");
        twDefinitionRepository.save(d);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(put("/api/admin/data/tw-definition/update-row").with(csrf())
                        .contentType("application/json")
                        .content("{\"label\":\"New label\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("New label"));
    }

    @Test
    void update_invalidScalarValue_returns400() throws Exception {
        twDefinitionRepository.deleteAll();
        TwDefinition d = new TwDefinition();
        d.setId("bad-update");
        d.setLabel("Label");
        twDefinitionRepository.save(d);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(put("/api/admin/data/tw-definition/bad-update").with(csrf())
                        .contentType("application/json")
                        .content("{\"sortOrder\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void entityDetail_rendersLazyCollectionRelation() throws Exception {
        userGroupRepository.deleteAll();

        UserAccount member = new UserAccount();
        member.setTwitchUsername("member-user-" + java.util.UUID.randomUUID());
        member = userAccountRepository.save(member);

        UserGroup group = new UserGroup();
        group.setKey("lazy-collection-group");
        group.setName("Lazy collection group");
        group.getMembers().add(member);
        group = userGroupRepository.save(group);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/user-group/" + group.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].id").exists())
                .andExpect(jsonPath("$.members[0].label").value(
                        org.hamcrest.Matchers.containsString("member-user")));
    }

    @Test
    void update_unresolvedRelationTarget_returns400() throws Exception {
        chatCommandDefinitionRepository.deleteAll();

        UserAccount owner = new UserAccount();
        owner.setTwitchUsername("cmd-owner-" + java.util.UUID.randomUUID());
        owner = userAccountRepository.save(owner);

        ChatCommandDefinition definition = new ChatCommandDefinition();
        definition.setUser(owner);
        definition.setName("greet");
        definition.setTemplate("Hello!");
        definition.setPermission(ChatCommandEvent.SenderRole.VIEWERS);
        definition = chatCommandDefinitionRepository.save(definition);

        String nonexistentUserId = java.util.UUID.randomUUID().toString();

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(put("/api/admin/data/chat-command-definition/" + definition.getId()).with(csrf())
                        .contentType("application/json")
                        .content("{\"user\":\"" + nonexistentUserId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_removesRow() throws Exception {
        twDefinitionRepository.deleteAll();
        TwDefinition d = new TwDefinition();
        d.setId("delete-row");
        d.setLabel("Delete row");
        twDefinitionRepository.save(d);

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(delete("/api/admin/data/tw-definition/delete-row").with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/admin/data/tw-definition/delete-row"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_unknownId_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(delete("/api/admin/data/tw-definition/does-not-exist").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
