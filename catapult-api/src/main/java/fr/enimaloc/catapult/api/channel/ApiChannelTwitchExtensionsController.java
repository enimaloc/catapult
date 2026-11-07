package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.ExtensionSlotDto;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.service.account.ChannelSettingsService;
import fr.enimaloc.catapult.service.binding.ChannelDashboardActions;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A channel's Twitch extensions, read with the broadcaster's token, and the extension layout of each of its
 * configurations (a binding, a custom binding, the two fallbacks), for its owner and its moderators.
 */
@RestController
@RequestMapping(ApiPaths.USER)
@RequiredArgsConstructor
public class ApiChannelTwitchExtensionsController {
    private final ApiUserResolver userResolver;
    private final TwitchService twitchService;
    private final ChannelDashboardActions dashboardActions;
    private final CustomBindingService customBindingService;
    private final ChannelSettingsService settingsService;

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @GetMapping("/twitch/extensions")
    public TwitchExtensionsDto extensions(@PathVariable UUID userId) {
        UserAccount channel = userResolver.user(userId);
        List<TwitchExtensionsDto.Installed> installed = twitchService.getUserExtensions(channel).stream()
                .map(ApiChannelTwitchExtensionsController::installed)
                .toList();
        return new TwitchExtensionsDto(installed, active(twitchService.getActiveExtensions(channel)));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/bindings/{bindingId}/extensions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bindingExtensions(@PathVariable UUID userId, @PathVariable UUID bindingId,
                                  @RequestBody List<ExtensionSlotDto> body) {
        dashboardActions.updateExtensionSlots(userResolver.user(userId), bindingId, validated(body));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/custom-bindings/{customBindingId}/extensions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void customBindingExtensions(@PathVariable UUID userId, @PathVariable UUID customBindingId,
                                        @RequestBody List<ExtensionSlotDto> body) {
        customBindingService.updateExtensionSlots(userResolver.user(userId), customBindingId, validated(body));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/no-game/extensions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void noGameExtensions(@PathVariable UUID userId, @RequestBody List<ExtensionSlotDto> body) {
        settingsService.saveNoGameExtensions(userResolver.user(userId), validated(body));
    }

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @PutMapping("/settings/incomplete-fallback/extensions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void incompleteFallbackExtensions(@PathVariable UUID userId, @RequestBody List<ExtensionSlotDto> body) {
        settingsService.saveIncompleteFallbackExtensions(userResolver.user(userId), validated(body));
    }

    /** The request's layout, or a 400 problem for a mobile slot, a slot out of Twitch's bounds or a duplicate. */
    private static Set<ExtensionSlotSetting> validated(List<ExtensionSlotDto> body) {
        try {
            return ExtensionSlotSetting.validated(body);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(ErrorCode.INVALID, e.getMessage());
        }
    }

    private static TwitchExtensionsDto.Installed installed(TwitchExtension extension) {
        return new TwitchExtensionsDto.Installed(extension.id(), extension.name(), extension.version(),
                extension.types().stream().map(type -> TwitchExtensionsDto.Type.valueOf(type.name())).toList(),
                extension.canActivate());
    }

    /** The active slots only, components first then overlays then panels, each in Twitch's numbering. */
    static List<TwitchExtensionsDto.Active> active(TwitchExtensionPlacement placement) {
        List<TwitchExtensionsDto.Active> active = new ArrayList<>();
        if (placement == null) return active;
        addActive(active, TwitchExtensionsDto.Type.COMPONENT, placement.component());
        addActive(active, TwitchExtensionsDto.Type.OVERLAY, placement.overlay());
        addActive(active, TwitchExtensionsDto.Type.PANEL, placement.panel());
        return active;
    }

    private static void addActive(List<TwitchExtensionsDto.Active> active, TwitchExtensionsDto.Type type,
                                  TwitchExtensionPlacement.Placement placement) {
        if (placement == null) return;
        List<TwitchExtensionPlacement.Slot> slots = placement.slots();
        for (int i = 0; i < slots.size(); i++) {
            TwitchExtensionPlacement.Slot slot = slots.get(i);
            if (slot != null && slot.active()) {
                active.add(new TwitchExtensionsDto.Active(type, i + 1, slot.id(), slot.name(), slot.version(),
                        slot.x(), slot.y()));
            }
        }
    }
}
