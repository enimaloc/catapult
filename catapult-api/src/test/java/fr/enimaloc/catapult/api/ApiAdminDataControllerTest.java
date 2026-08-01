package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void unknownRepo_returns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(get("/api/admin/data/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
