package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.AdminTwService;
import fr.enimaloc.catapult.service.TwBackfillService;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_admin_tw_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
@WithMockUser(roles = "ADMIN")
class ApiAdminTwControllerTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @MockitoBean AdminTwService adminTwService;
    @MockitoBean AdminCclService adminCclService;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;
    @MockitoBean TwBackfillService twBackfillService;

    @Test
    void rebuildForcePinned_delegatesToBackfillService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(post("/api/admin/tw/rebuild/force-pinned").with(csrf()))
                .andExpect(status().isAccepted());
        verify(twBackfillService).forceRebuildAllIncludingPinned();
    }

    @Test
    void list_returnsDefinitions() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        TwDefinition d = new TwDefinition();
        d.setId("x");
        d.setLabel("X");
        when(adminTwService.list()).thenReturn(List.of(d));
        mvc.perform(get("/api/admin/tw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("x"));
    }

    @Test
    void create_returnsCreated() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        TwDefinition d = new TwDefinition();
        d.setId("ok");
        d.setLabel("OK");
        when(adminTwService.create(any(), any(), any(), any(Integer.class))).thenReturn(d);
        mvc.perform(post("/api/admin/tw").with(csrf())
                        .contentType("application/json")
                        .content("{\"id\":\"ok\",\"label\":\"OK\",\"sortOrder\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ok"));
    }

    @Test
    void get_returnsNotFoundWhenMissing() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(adminTwService.get("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/tw/missing")).andExpect(status().isNotFound());
    }

    @Test
    void listSteamKeywords_returnsSavedKeywords() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(adminTwService.listSteamKeywords("gore")).thenReturn(List.of("blood", "gore"));
        mvc.perform(get("/api/admin/tw/gore/steam-keywords"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("blood"));
    }

    @Test
    void addSteamKeyword_delegatesToService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(post("/api/admin/tw/gore/steam-keywords").with(csrf())
                        .contentType("application/json")
                        .content("{\"keyword\":\"blood\"}"))
                .andExpect(status().isNoContent());
        verify(adminTwService).addSteamKeyword("gore", "blood");
    }

    @Test
    void removeSteamKeyword_delegatesToService() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mvc.perform(delete("/api/admin/tw/gore/steam-keywords/blood").with(csrf()))
                .andExpect(status().isNoContent());
        verify(adminTwService).removeSteamKeyword("gore", "blood");
    }

    @Test
    void testSteamKeywords_returnsMatchResult() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(adminTwService.testSteamSignals("gore", "730", "gore")).thenReturn(
                new AdminTwService.SteamSignalTestResult("blood everywhere", List.of("blood"), true, Set.of(2)));
        mvc.perform(post("/api/admin/tw/gore/steam-keywords/test").with(csrf())
                        .contentType("application/json")
                        .content("{\"appId\":\"730\",\"draftKeyword\":\"gore\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedKeywords[0]").value("blood"))
                .andExpect(jsonPath("$.draftKeywordMatch").value(true))
                .andExpect(jsonPath("$.matchedContentDescriptorIds[0]").value(2));
    }
}
