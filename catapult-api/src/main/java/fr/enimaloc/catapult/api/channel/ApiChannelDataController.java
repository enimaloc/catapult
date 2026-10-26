package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.StatusData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.connections.ConnectionEventService;
import fr.enimaloc.catapult.service.twitch.TwitchCategory;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import fr.enimaloc.catapult.security.Authz;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

/** Read side of a channel's dashboard, for its owner and its moderators (decided by {@code @authz}). */
@RestController
@RequestMapping("/api/channels/{username}")
@RequiredArgsConstructor
public class ApiChannelDataController {
    private final ApiUserResolver userResolver;
    private final ChannelDashboardAssembler dashboard;
    private final ActivityLogService activityLogService;
    private final ConnectionEventService connectionEventService;
    private final TwitchService twitchService;

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs(@PathVariable String username) {
        return activityLogService.subscribe(userResolver.channel(username).getId());
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping(value = "/connections", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connections(@PathVariable String username) {
        return connectionEventService.subscribe(userResolver.channel(username).getId());
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping
    public ChannelPageData channelPage(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.channel(username);
        return dashboard.page(viewer, channel, username, page, status, source);
    }

    @PreAuthorize("@authz.ownsChannel(#username)")
    @PostMapping("/steam/refresh-profile-cache")
    public ResponseEntity<Void> refreshProfileCache(@PathVariable String username) {
        dashboard.refreshSteamProfileCache(userResolver.channel(username));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping("/status")
    public StatusData statusFragment(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.channel(username);
        return dashboard.status(viewer, channel, username);
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping("/settings")
    public UserSettingsDto settings(@PathVariable String username) {
        return dashboard.settings(userResolver.channel(username));
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping(value = "/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TwitchCategory> searchGames(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q) {
        if (q.isBlank()) return List.of();
        return twitchService.searchCategories(userResolver.viewer(jwt), q);
    }

    /** The binding of the game the channel plays now (the OBS dock's), 204 when there is none. */
    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping("/bindings/current")
    public ResponseEntity<BindingDto> currentBinding(@PathVariable String username) {
        return dashboard.currentBinding(userResolver.channel(username))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("@authz.canAccessChannel(#username)")
    @GetMapping("/dtdd-mapping")
    public DtddMappingStatusDto dtddMappingStatus(@PathVariable String username, @AuthenticationPrincipal Jwt jwt,
                                                 Authentication authentication, HttpServletRequest request) {
        UserAccount viewer = userResolver.viewer(jwt);
        UserAccount channel = userResolver.channel(username);
        return dashboard.dtddMapping(viewer, channel, Authz.isAdmin(authentication, request));
    }
}
