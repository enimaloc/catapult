package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserFlagRepository;
import fr.enimaloc.catapult.repository.account.UserGroupRepository;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.service.account.AdminMemberService;
import fr.enimaloc.catapult.service.account.AdminMigrationService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
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
@Import(AdminMemberService.class)
class ApiAdminFlagsControllerTest {
    @MockitoBean fr.enimaloc.catapult.service.account.UserApiTokenService userApiTokenService;
    @Autowired MockMvc mvc;
    @MockitoBean UserFlagRepository flags;
    @MockitoBean UserAccountRepository accounts;
    @MockitoBean UserGroupRepository groups;
    @MockitoBean StreamStateService streamState;
    @MockitoBean AccountService accountService;
    @MockitoBean BotToggleService botToggle;
    @MockitoBean AdminMigrationService migrations;
    @MockitoBean MemberDebugService memberDebug;

    @Test
    void groupsValuesUnderEachKeyAndLeavesABareTagWithNone() throws Exception {
        when(flags.findDistinctKeyValues()).thenReturn(List.of(
                new Object[] {"beta", "off"}, new Object[] {"beta", "on"}, new Object[] {"tester", null}));

        mvc.perform(get("/api/internal/admin/flags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("beta"))
                .andExpect(jsonPath("$[0].values[0]").value("off"))
                .andExpect(jsonPath("$[0].values[1]").value("on"))
                .andExpect(jsonPath("$[1].key").value("tester"))
                .andExpect(jsonPath("$[1].values.length()").value(0));
    }
}
