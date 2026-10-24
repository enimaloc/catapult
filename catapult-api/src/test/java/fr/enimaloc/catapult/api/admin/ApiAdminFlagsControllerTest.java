package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiAdminFlagsController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@AutoConfigureMockMvc(addFilters = false) // the admin guard is ApiSecurityConfig's, tested with it
class ApiAdminFlagsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserFlagRepository flags;

    @Test
    void groupsValuesUnderEachKeyAndLeavesABareTagWithNone() throws Exception {
        when(flags.findDistinctKeyValues()).thenReturn(List.of(
                new Object[] {"beta", "off"}, new Object[] {"beta", "on"}, new Object[] {"tester", null}));

        mvc.perform(get("/api/admin/flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("beta"))
                .andExpect(jsonPath("$[0].values[0]").value("off"))
                .andExpect(jsonPath("$[0].values[1]").value("on"))
                .andExpect(jsonPath("$[1].key").value("tester"))
                .andExpect(jsonPath("$[1].values.length()").value(0));
    }
}
