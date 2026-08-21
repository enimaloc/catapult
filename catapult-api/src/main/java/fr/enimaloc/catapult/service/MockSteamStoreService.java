package fr.enimaloc.catapult.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.mock.steam", havingValue = "true")
public class MockSteamStoreService implements SteamStoreService {

    @Override
    public Map<String, Set<String>> fetchCcls(Collection<String> appIds) {
        return Map.of();
    }

    @Override
    public Optional<ResolvedParentApp> resolveEffectiveApp(String appId) {
        return Optional.empty();
    }

    @Override
    public Map<String, SteamTwSignals> fetchTwSignals(Collection<String> appIds) {
        return Map.of();
    }
}
