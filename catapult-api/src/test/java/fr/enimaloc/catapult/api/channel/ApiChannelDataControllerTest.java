package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.StatusData;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.connections.ConnectionEventService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ApiChannelDataController.class,
        excludeAutoConfiguration = ThymeleafAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "fr\\.enimaloc\\.catapult\\.experiment\\.thymeleaf\\..*"))
@Import(ApiUserResolver.class)
class ApiChannelDataControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserAccountRepository userAccountRepository;
    @MockitoBean ChannelAccessService channelAccessService;
    @MockitoBean ChannelDashboardAssembler dashboard;
    @MockitoBean ActivityLogService activityLogService;
    @MockitoBean ConnectionEventService connectionEventService;
    @MockitoBean TwitchService twitchService;

    private UserAccount owner;
    private UserAccount moderator;

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        return account;
    }

    private static RequestPostProcessor as(UserAccount account) {
        return jwt().jwt(j -> j.subject(account.getId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @BeforeEach
    void setUp() {
        owner = account("streamer");
        moderator = account("mod");
        for (UserAccount account : List.of(owner, moderator)) {
            when(userAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        }
        when(userAccountRepository.findByTwitchUsername("streamer")).thenReturn(Optional.of(owner));
        when(channelAccessService.canAccess(any(), any())).thenReturn(true);
    }

    @Test
    void status_delegatesWithThePathUsername() throws Exception {
        when(dashboard.status(moderator, owner, "streamer"))
                .thenReturn(new StatusData("streamer", false, true, true, null));

        mvc.perform(get("/api/channels/streamer/status").with(as(moderator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelUsername").value("streamer"))
                .andExpect(jsonPath("$.isLive").value(true));
    }

    @Test
    void page_passesPagingAndFilters() throws Exception {
        mvc.perform(get("/api/channels/streamer").param("page", "3").param("status", "AUTO").with(as(owner)))
                .andExpect(status().isOk());

        verify(dashboard).page(owner, owner, "streamer", 3, "AUTO", null);
    }

    @Test
    void settings() throws Exception {
        mvc.perform(get("/api/channels/streamer/settings").with(as(moderator))).andExpect(status().isOk());

        verify(dashboard).settings(owner);
    }

    @Test
    void dtddMapping() throws Exception {
        when(dashboard.dtddMapping(moderator, owner)).thenReturn(new DtddMappingStatusDto(null, null, true, "42"));

        mvc.perform(get("/api/channels/streamer/dtdd-mapping").with(as(moderator)))
                .andExpect(jsonPath("$.igdbId").value("42"))
                .andExpect(jsonPath("$.canValidateDirectly").value(true));
    }

    @Test
    void searchGames_asksTwitchAsTheViewer() throws Exception {
        when(twitchService.searchCategories(moderator, "doom")).thenReturn(List.of(new TwitchCategory("1", "Doom", null)));

        mvc.perform(get("/api/channels/streamer/games/search").param("q", "doom").with(as(moderator)))
                .andExpect(jsonPath("$[0].name").value("Doom"));
    }

    @Test
    void searchGames_blankQueryReturnsNothing() throws Exception {
        mvc.perform(get("/api/channels/streamer/games/search").with(as(moderator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verifyNoInteractions(twitchService);
    }

    @Test
    void refreshProfileCache_isOwnerOnly() throws Exception {
        mvc.perform(post("/api/channels/streamer/steam/refresh-profile-cache").with(as(owner)).with(csrf()))
                .andExpect(status().isNoContent());
        verify(dashboard).refreshSteamProfileCache(owner);

        mvc.perform(post("/api/channels/streamer/steam/refresh-profile-cache").with(as(moderator)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void streams_subscribeToTheChannelsEvents() throws Exception {
        when(activityLogService.subscribe(owner.getId())).thenReturn(new SseEmitter());
        when(connectionEventService.subscribe(owner.getId())).thenReturn(new SseEmitter());

        mvc.perform(get("/api/channels/streamer/logs").with(as(moderator))).andExpect(status().isOk());
        mvc.perform(get("/api/channels/streamer/connections").with(as(moderator))).andExpect(status().isOk());

        verify(activityLogService).subscribe(owner.getId());
        verify(connectionEventService).subscribe(owner.getId());
    }

    @Test
    void inaccessibleChannel_isForbidden() throws Exception {
        when(channelAccessService.canAccess(moderator, owner)).thenReturn(false);

        for (String path : List.of("", "/status", "/settings", "/dtdd-mapping", "/games/search", "/logs")) {
            mvc.perform(get("/api/channels/streamer" + path).with(as(moderator))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(dashboard);
    }

    @Test
    void unknownChannel_isNotFound() throws Exception {
        mvc.perform(get("/api/channels/nobody").with(as(owner))).andExpect(status().isNotFound());
    }

    @Test
    void unknownCaller_isUnauthorized() throws Exception {
        mvc.perform(get("/api/channels/streamer").with(as(account("ghost")))).andExpect(status().isUnauthorized());
    }
}
