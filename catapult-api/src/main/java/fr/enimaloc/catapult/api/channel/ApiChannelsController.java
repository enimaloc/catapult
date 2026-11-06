package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.premium.PremiumUiGate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.USER + "/channels")
@RequiredArgsConstructor
public class ApiChannelsController {

    private final ApiUserResolver userResolver;
    private final ChannelAccessService channelAccessService;
    private final StreamStateService streamStateService;
    private final PremiumUiGate premiumUiGate;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public ChannelListResponse list(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        UserAccount viewer = userResolver.viewer(jwt);

        List<ChannelDto> channels = channelAccessService.getAccessibleChannels(viewer).stream()
                .map(account -> new ChannelDto(
                        account.getId(),
                        account.getTwitchId(),
                        account.getTwitchUsername(),
                        account.getProfileImageUrl(),
                        streamStateService.isLive(account)
                ))
                .toList();

        // Read with the navbar's channels, the one call every page makes: it shows the premium entry or not
        return new ChannelListResponse(viewer.getTwitchId(), channels, premiumUiGate.isOpenFor(viewer));
    }
}
