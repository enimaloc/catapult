package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.TwitchExtension;
import fr.enimaloc.catapult.service.twitch.TwitchExtensionPlacement;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A channel's Twitch extensions, read with the broadcaster's token, for its owner and its moderators. */
@RestController
@RequestMapping(ApiPaths.USER + "/twitch/extensions")
@RequiredArgsConstructor
public class ApiChannelTwitchExtensionsController {
    private final ApiUserResolver userResolver;
    private final TwitchService twitchService;

    @PreAuthorize("@authz.canAccessChannel(#userId)")
    @GetMapping
    public TwitchExtensionsDto extensions(@PathVariable UUID userId) {
        UserAccount channel = userResolver.user(userId);
        List<TwitchExtensionsDto.Installed> installed = twitchService.getUserExtensions(channel).stream()
                .map(ApiChannelTwitchExtensionsController::installed)
                .toList();
        return new TwitchExtensionsDto(installed, active(twitchService.getActiveExtensions(channel)));
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
