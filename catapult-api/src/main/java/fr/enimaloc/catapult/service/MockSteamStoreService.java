package fr.enimaloc.catapult.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Locale;
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

    @Override
    public Optional<String> fetchDescription(String appId, Locale locale) {
        return Optional.empty();
    }

    @Override
    public Optional<SteamStorePage> fetchData(String appId, Locale locale, boolean resolveEffectiveParent) {
        return Optional.empty();
    }

    @Override
    public Optional<String> fetchIADisclosure(String appId, Locale locale) {
        return Optional.empty();
    }
}
