package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.config.I18nConfiguration;
import fr.enimaloc.catapult.config.TwitchatCatalogConfig;
import fr.enimaloc.catapult.controller.ChannelActionsController;
import fr.enimaloc.catapult.controller.ModelFiller;
import fr.enimaloc.catapult.controller.NavEventStreamController;
import fr.enimaloc.catapult.controller.SessionChannels;
import fr.enimaloc.catapult.controller.SpaFragmentController;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@WebMvcTest(controllers = {ChannelActionsController.class, SpaFragmentController.class})
@Import({ModelFiller.class, WebSecurityConfig.class, TwitchatCatalogConfig.class, I18nConfiguration.class,
        InteractionMetrics.class, SimpleMeterRegistry.class})
class InteractionMetricsInterceptorTest {
    private static final UUID MEMBER = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;
    @MockitoBean ApiService apiService;
    @MockitoBean NavEventStreamController navEvents;

    @BeforeEach
    void loggedInAsTheMember() {
        when(apiService.currentUserId()).thenReturn(MEMBER);
        fr.enimaloc.catapult.controller.ApiServiceStubs.actionsAnswer(apiService, 204);
    }

    /** A session logged in as {@link #MEMBER}, listing {@code channels}. */
    private static MockHttpSession member(ChannelDto... channels) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        SessionChannels.remember(session, List.of(channels));
        return session;
    }

    private long actions(String action, String role, String outcome) {
        Timer timer = registry.find("catapult.web.actions")
                .tags("area", "channel", "action", action, "role", role, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double views(String page, String via, String role, String outcome) {
        var counter = registry.find("catapult.web.page.views")
                .tags("page", page, "via", via, "role", role, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anActionOnTheMembersOwnChannel_countsAsTheOwners() throws Exception {
        long before = actions("toggleBot", "owner", "success");

        mvc.perform(post("/channel/enimaloc/settings/bot").with(csrf())
                .session(member(new ChannelDto(MEMBER, "1", "enimaloc", "img", false))));

        assertThat(actions("toggleBot", "owner", "success")).isEqualTo(before + 1);
    }

    @Test
    void anActionOnAModeratedChannel_countsAsAModerators() throws Exception {
        long before = actions("recheckGame", "moderator", "success");

        mvc.perform(post("/channel/other/game/recheck").with(csrf())
                .session(member(new ChannelDto(UUID.randomUUID(), "2", "Other", "img", false))));

        assertThat(actions("recheckGame", "moderator", "success")).isEqualTo(before + 1);
    }

    @Test
    void aMalformedAction_countsAsAClientError() throws Exception {
        long before = actions("saveCclSettings", "anonymous", "client_error");

        mvc.perform(post("/channel/enimaloc/settings/ccl").with(csrf()).contentType("application/json").content("{"));

        assertThat(actions("saveCclSettings", "anonymous", "client_error")).isEqualTo(before + 1);
    }

    @Test
    void aSpaPage_countsAsAViewViaTheSpa() throws Exception {
        double before = views("landing", "spa", "anonymous", "success");

        mvc.perform(get("/spa/landing"));

        assertThat(views("landing", "spa", "anonymous", "success")).isEqualTo(before + 1);
    }

    @Test
    void anUnknownPage_countsAsUnknown_notUnderItsPath() throws Exception {
        double before = views("unknown", "spa", "member", "client_error");

        mvc.perform(get("/spa/whatever").session(member()));

        assertThat(views("unknown", "spa", "member", "client_error")).isEqualTo(before + 1);
        assertThat(registry.find("catapult.web.page.views").tag("page", "whatever").counter()).isNull();
    }

    @Test
    void theNavbarsChannels_areNotAPageView() throws Exception {
        mvc.perform(get("/spa/nav-channels"));

        assertThat(registry.find("catapult.web.page.views").tag("page", "nav-channels").counter()).isNull();
    }

    @Test
    void page_namesTheRouteOrCatalogPage() {
        assertThat(InteractionMetricsInterceptor.page("/")).isEqualTo("landing");
        assertThat(InteractionMetricsInterceptor.page("/channel/enimaloc")).isEqualTo("channel");
        assertThat(InteractionMetricsInterceptor.page("/privacy")).isEqualTo("privacy");
        assertThat(InteractionMetricsInterceptor.page("/admin")).isEqualTo("admin");
        assertThat(InteractionMetricsInterceptor.page("/wp-login.php")).isEqualTo("unknown");
    }
}
