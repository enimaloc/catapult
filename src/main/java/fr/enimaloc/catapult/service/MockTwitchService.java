package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.twitch", havingValue = "true")
public class MockTwitchService implements TwitchService {

    private static final String USER_PREFIX = "mock-user-";

    @Value("${app.mock.users-count:3}")
    private int mockUsersCount;

    private final TwitchCategoryService twitchCategoryService;

    @Override
    public void updateChannel(UserAccount user, GameBinding binding) {
        log.debug("[Mock Twitch] updateChannel() — no-op for user {}", user.getId());
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
    public void resetToDefault(UserAccount user) {
        log.debug("[Mock Twitch] resetToDefault() — no-op for user {}", user.getId());
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        List<String> ids = computeModeratedChannels(viewer.getTwitchId());
        log.debug("[Mock Twitch] getModeratedChannelIds() — {} moderates {}", viewer.getTwitchId(), ids);
        return ids;
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
