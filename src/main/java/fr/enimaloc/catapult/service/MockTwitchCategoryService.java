package fr.enimaloc.catapult.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.twitch-category", havingValue = "true")
public class MockTwitchCategoryService implements TwitchCategoryService {

    private static final List<TwitchCategory> MOCK_CATEGORIES = List.of(
        new TwitchCategory("509658", "Just Chatting", ""),
        new TwitchCategory("26936", "Music", ""),
        new TwitchCategory("21779", "League of Legends", ""),
        new TwitchCategory("516575", "VALORANT", ""),
        new TwitchCategory("1469308723", "Science & Technology", ""),
        new TwitchCategory("32982", "Grand Theft Auto V", ""),
        new TwitchCategory("509659", "ASMR", "")
    );

    @Override
    public List<TwitchCategory> searchCategories(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT);
        return MOCK_CATEGORIES.stream()
            .filter(c -> c.name().toLowerCase(Locale.ROOT).contains(q))
            .toList();
    }
}
