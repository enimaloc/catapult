package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.ChannelDto;
import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Profile("mock")
public class MockApiService implements ApiService {
    private String jwt = "0";

    @Override
    public TokenResponse exchangeCode(String code) {
        jwt = code;
        return new TokenResponse(code);
    }

    @Override
    public ChannelListResponse channelList() {
        return MockData.getChannelList(jwt);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.ChannelPageData channelPage(String username, int page, String status, String source) {
        return MockData.getChannelPage(username, status, source);
    }

    @Override
    public void toggleBot(String username) {
        // no-op in mock mode
    }

    @Override
    public void recheckGame(String username) {
        // no-op in mock mode
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        // no-op in mock mode
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        // no-op in mock mode
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        // no-op in mock mode
    }

    @Override
    public Object searchGames(String username, String q) {
        return List.of(Map.of("id", "509658", "name", "Celeste"));
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        // no-op in mock mode
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        // no-op in mock mode
    }

    @Override
    public void resetTws(String bindingId) {
        // no-op in mock mode
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        // no-op in mock mode
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        // no-op in mock mode
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        // no-op in mock mode
    }

    @Override
    public void deleteSteamToken(String username) {
        // no-op in mock mode
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        // no-op in mock mode
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        return new LinkStateResponse("NONE", null, null);
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        // no-op in mock mode
    }

    @Override
    public void minecraftSync(String username) {
        // no-op in mock mode
    }

    @Override
    public void minecraftDisconnect(String username) {
        // no-op in mock mode
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls) {
        // no-op in mock mode
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws) {
        // no-op in mock mode
    }

    @Override
    public fr.enimaloc.catapult.common.dto.UserSettingsDto channelSettings(String username) {
        return MockData.getChannelSettings();
    }

    @Override
    public void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        // no-op in mock mode
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        // no-op in mock mode
    }

    @Override
    public fr.enimaloc.catapult.common.dto.DtddMappingStatusDto dtddMappingStatus(String username) {
        return new fr.enimaloc.catapult.common.dto.DtddMappingStatusDto(null, null, false, null);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.SearchResponse dtddSearch(String q) {
        return new fr.enimaloc.catapult.common.dto.SearchResponse(List.of());
    }

    @Override
    public void dtddValidate(String igdbId) {
        // no-op in mock mode
    }

    @Override
    public void dtddPropose(String igdbId, Long dtddId, String reason) {
        // no-op in mock mode
    }
}
