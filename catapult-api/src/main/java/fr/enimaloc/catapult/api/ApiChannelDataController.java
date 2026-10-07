package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.StatusData;
import fr.enimaloc.catapult.common.dto.UserSettingsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.connections.ConnectionEventService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Read side of a channel's dashboard, for its owner and its moderators. */
@RestController
@RequestMapping("/api/channels/{username}")
@RequiredArgsConstructor
public class ApiChannelDataController {
    private final ApiUserResolver userResolver;
    private final ChannelDashboardAssembler dashboard;
    private final ActivityLogService activityLogService;
    private final ConnectionEventService connectionEventService;
    private final TwitchService twitchService;

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        return activityLogService.subscribe(userResolver.accessibleChannel(username, jwt).getId());
    }

    @GetMapping(value = "/connections", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connections(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        return connectionEventService.subscribe(userResolver.accessibleChannel(username, jwt).getId());
    }

    @GetMapping
    public ChannelPageData channelPage(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.accessibleChannel(username, viewer);
        return dashboard.page(viewer, channel, username, page, status, source);
    }

    @PostMapping("/steam/refresh-profile-cache")
    public ResponseEntity<Void> refreshProfileCache(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        dashboard.refreshSteamProfileCache(userResolver.ownChannel(username, jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public StatusData statusFragment(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.accessibleChannel(username, viewer);
        return dashboard.status(viewer, channel, username);
    }

    @GetMapping("/settings")
    public UserSettingsDto settings(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        return dashboard.settings(userResolver.accessibleChannel(username, jwt));
    }

    @GetMapping(value = "/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TwitchCategory> searchGames(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q) {
        UserAccount viewer = userResolver.viewer(jwt);
        userResolver.accessibleChannel(username, viewer);
        if (q.isBlank()) return List.of();
        return twitchService.searchCategories(viewer, q);
    }

    @GetMapping("/dtdd-mapping")
    public DtddMappingStatusDto dtddMappingStatus(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.accessibleChannel(username, viewer);
        return dashboard.dtddMapping(viewer, channel);
    }
}
