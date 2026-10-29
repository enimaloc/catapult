package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.twitch", havingValue = "true")
public class MockTwitchService implements TwitchService {

    private static final String USER_PREFIX = "mock-user-";

    @Value("${app.mock.users-count:3}")
    private int mockUsersCount;

    private final TwitchCategoryService twitchCategoryService;
    private final UserSettingsRepository userSettingsRepository;

    /** The category each channel was last put on, by account id: what Twitch would show. */
    private final Map<UUID, String> categoryNameByUser = new ConcurrentHashMap<>();

    @Override
    public ChannelUpdateOutcome updateChannelReporting(UserAccount user, GameBinding binding) {
        log.debug("[Mock Twitch] updateChannelReporting() — no-op for user {}", user.getId());
        remember(user, binding.getTwitchGameName());
        return ChannelUpdateOutcome.APPLIED;
    }

    @Override
    public ChannelUpdateOutcome setCategory(UserAccount user, String twitchGameId, String twitchGameName) {
        log.debug("[Mock Twitch] setCategory() — no-op for user {}", user.getId());
        remember(user, twitchGameName);
        return ChannelUpdateOutcome.APPLIED;
    }

    /** The name of the category the channel of account {@code userId} was last put on, if any. */
    public Optional<String> lastCategoryName(UUID userId) {
        return Optional.ofNullable(categoryNameByUser.get(userId));
    }

    private void remember(UserAccount user, String twitchGameName) {
        if (twitchGameName != null) {
            categoryNameByUser.put(user.getId(), twitchGameName);
        }
    }

    @Override
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        return Optional.empty();
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return twitchCategoryService.searchCategories(query);
    }

    @Override
    public ChannelUpdateOutcome resetToDefault(UserAccount user) {
        log.debug("[Mock Twitch] resetToDefault() — no-op for user {}", user.getId());
        // the default category the real reset would put the channel on
        userSettingsRepository.findById(user.getId())
            .ifPresent(settings -> remember(user, settings.getNoGameTwitchGameName()));
        return ChannelUpdateOutcome.APPLIED;
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        List<String> ids = computeModeratedChannels(viewer.getTwitchId());
        log.debug("[Mock Twitch] getModeratedChannelIds() — {} moderates {}", viewer.getTwitchId(), ids);
        return ids;
    }

    @Override
    public List<TwitchExtension> getUserExtensions(UserAccount user) {
        return List.of();
    }

    @Override
    public TwitchExtensionPlacement getActiveExtensions(UserAccount user) {
        return TwitchExtensionPlacement.empty();
    }

    @Override
    public TwitchExtensionPlacement setActiveExtensions(UserAccount user, TwitchExtensionPlacement placement) {
        return TwitchExtensionPlacement.empty();
    }

    // For mock-user-N: randomly picks a non-empty subset of the channels it could moderate
    // (mock-admin + mock-user-0 … mock-user-(N-1)). Seeded on N for reproducibility.
    private List<String> computeModeratedChannels(String twitchId) {
        if (!twitchId.startsWith(USER_PREFIX)) return List.of();
        int idx;
        try {
            idx = Integer.parseInt(twitchId.substring(USER_PREFIX.length()));
        } catch (NumberFormatException e) {
            return List.of();
        }
        if (idx < 0 || idx >= mockUsersCount) return List.of();

        List<String> pool = new ArrayList<>();
        pool.add("mock-admin");
        for (int i = 0; i < idx; i++) pool.add(USER_PREFIX + i);

        Random rng = new Random(idx);
        List<String> result = new ArrayList<>();
        for (String channel : pool) {
            if (rng.nextBoolean()) result.add(channel);
        }
        if (result.isEmpty()) result.add(pool.get(rng.nextInt(pool.size())));
        return result;
    }
}
