package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("steam.enabled")
public class SteamGameGetter implements GameGetter {

    private final SteamApiClient steamApiClient;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        if (user.getSteamId() == null) return Optional.empty();
        try {
            return steamApiClient.getPlayerSummary(user.getSteamId())
                .map(p -> new DetectedGame(p.gameId(), GameBinding.SourceType.STEAM, p.gameName()));
        } catch (Exception e) {
            log.warn("Failed to fetch current game from Steam for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
