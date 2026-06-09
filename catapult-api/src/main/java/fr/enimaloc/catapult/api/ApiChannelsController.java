package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ApiChannelsController {

    private final UserAccountRepository userAccountRepository;
    private final ChannelAccessService channelAccessService;
    private final StreamStateService streamStateService;

    @GetMapping
    public ChannelListResponse list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));

        List<ChannelDto> channels = channelAccessService.getAccessibleChannels(viewer).stream()
                .map(account -> new ChannelDto(
                        account.getId(),
                        account.getTwitchId(),
                        account.getTwitchUsername(),
                        account.getProfileImageUrl(),
                        streamStateService.isLive(account)
                ))
                .toList();

        return new ChannelListResponse(viewer.getTwitchId(), channels);
    }

    public record ChannelDto(
            UUID id,
            String twitchId,
            String twitchUsername,
            String profileImageUrl,
            boolean live
    ) {}

    public record ChannelListResponse(
            String viewerTwitchId,
            List<ChannelDto> channels
    ) {}
}
