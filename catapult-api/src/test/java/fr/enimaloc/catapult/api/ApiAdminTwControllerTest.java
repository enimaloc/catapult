package fr.enimaloc.catapult.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.domain.TwDefinition;
import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.AdminTwService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
}
