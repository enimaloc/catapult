package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The members list's dry-run pastille and the menu item switching it. */
@WebMvcTest(controllers = AdminTabsFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class})
class AdminTabsFragmentControllerDryRunTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;
    @MockitoBean BroadcastState broadcastState;

    @BeforeEach
    void admin() {
        when(apiService.isAdmin()).thenReturn(true);
        when(apiService.currentUserId()).thenReturn(ADMIN);
    }

    private String members(boolean dryRun, Locale locale) throws Exception {
        when(apiService.adminMembers("", 0)).thenReturn(new AdminMembersPage(List.of(
                new AdminMemberDto(ID, "2", "viewer", "img", null, false, true, "ACTIVE", NOW, null, false, null, dryRun)),
                Map.of(), false, 0, 1, 1));
        return mvc.perform(get("/spa/admin/members").locale(locale).sessionAttr(ApiClient.SESSION_JWT_KEY, "jwt"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void outOfDryRun_offersToTurnItOn_once_confirmed_withoutThePastille() throws Exception {
        String html = members(false, Locale.ENGLISH);

        assertThat(html).contains("data-action=\"dry-run/enable\"", "Turn on dry run",
                        "Put &quot;viewer&quot; in dry run? Catapult will stop changing their channel until it is turned off.")
                .doesNotContain("dry-run/disable", "admin-member-dry-run-badge");
    }

    @Test
    void inDryRun_offersToTurnItOff_andShowsThePastille() throws Exception {
        String html = members(true, Locale.ENGLISH);

        assertThat(html).contains("data-action=\"dry-run/disable\"", "Turn off dry run", "admin-member-dry-run-badge", ">Dry run<")
                .doesNotContain("dry-run/enable");
    }

    @Test
    void inFrench() throws Exception {
        String html = members(true, Locale.FRENCH);

        assertThat(html).contains("Désactiver le dry run", ">Dry run<");
    }
}
