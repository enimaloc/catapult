package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("mock")
public class MockApiService implements ApiService {
    private String code;
    private MockData data;

    @Override
    public TokenResponse exchangeCode(String code) {
        log.trace("exchangeCode({})", code);
        this.code = code;
        data = MockData.fromJwt(code);
        return new TokenResponse(code);
    }

    @Override
    public ChannelListResponse channelList() {
        log.trace("[{}] channelList()", code);
        return data.getChannelList();
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        log.trace("[{}] channelPage({}, {}, {})", code, page, status, source);
        return data.getPage(username, status, source);
    }

    @Override
    public void toggleBot(String username) {
        log.trace("[{}] toggleBot({})", code, username);
        data.setBotEnabled(!data.isBotEnabled());
    }

    @Override
    public void recheckGame(String username) {
        log.trace("[{}] recheckGame({})", code, username);
        // no-op in mock mode
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        log.trace("[{}] cclToggle({}, {}, {})", code, username, bindingId, enabled);
        // no-op in mock mode
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("[{}] ignoredToggle({}, {}, {})", code, username, bindingId, ignored);
        // no-op in mock mode
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        log.trace("[{}] deleteBinding({}, {})", code, username, bindingId);
        // no-op in mock mode
    }

    @Override
    public Object searchGames(String username, String q) {
        log.trace("[{}] searchGames({}, {})", code, username, q);
        return List.of(Map.of("id", "509658", "name", "Celeste"));
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("[{}] updateBinding({}, {}, {}, {}, {})", code, username, bindingId, twitchGameId, twitchGameName, ccls);
        // no-op in mock mode
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        log.trace("[{}] saveTws({}, {})", code, bindingId, tws);
        // no-op in mock mode
    }

    @Override
    public void resetTws(String bindingId) {
        log.trace("[{}] resetTws({})", code, bindingId);
        // no-op in mock mode
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("[{}] toggleTwEnabled({}, {})", code, bindingId, enabled);
        // no-op in mock mode
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        log.trace("[{}] saveSteamToken({}, {}, {})", code, username, token, shared);
        // no-op in mock mode
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        log.trace("[{}] steamTokenSharing({}, {})", code, username, shared);
        // no-op in mock mode
    }

    @Override
    public void deleteSteamToken(String username) {
        log.trace("[{}] deleteSteamToken({})", code, username);
        // no-op in mock mode
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        log.trace("[{}] refreshSteamProfileCache({})", code, username);
        // no-op in mock mode
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        log.trace("[{}] minecraftStatus({})", code, username);
        return data.getMinecraftLink();
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        log.trace("[{}] minecraftEnroll({}, {})", code, username, name);
        // no-op in mock mode
    }

    @Override
    public void minecraftSync(String username) {
        log.trace("[{}] minecraftSync({})", code, username);
        // no-op in mock mode
    }

    @Override
    public void minecraftDisconnect(String username) {
        log.trace("[{}] minecraftDisconnect({})", code, username);
        // no-op in mock mode
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls) {
        log.trace("[{}] saveCclSettings({}, {}, {})", code, username, enabled, blockedCcls);
        // no-op in mock mode
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws) {
        log.trace("[{}] saveTwSettings({}, {}, {})", code, username, enabled, blockedTws);
        // no-op in mock mode
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        log.trace("[{}] channelSettings({})", code, username);
        return data.getUserSettings();
    }

    @Override
    public void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        log.trace("[{}] saveNoGameSettings({}, {}, {}, {}, {}, {}, {})", code, username, twitchGameId, twitchGameName,
                ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
        // no-op in mock mode
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("[{}] saveIncompleteFallbackSettings({}, {}, {}, {})", code, username, twitchGameId, twitchGameName, ccls);
        // no-op in mock mode
    }

    @Override
    public fr.enimaloc.catapult.common.dto.DtddMappingStatusDto dtddMappingStatus(String username) {
        log.trace("[{}] dtddMappingStatus({})", code, username);
        return new fr.enimaloc.catapult.common.dto.DtddMappingStatusDto(null, null, false, null);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.SearchResponse dtddSearch(String q) {
        log.trace("[{}] SearchResponse({})", code, q);
        return new fr.enimaloc.catapult.common.dto.SearchResponse(List.of());
    }

    @Override
    public void dtddValidate(String igdbId) {
        log.trace("[{}] dtddValidate({})", code, igdbId);
        // no-op in mock mode
    }

    @Override
    public void dtddPropose(String igdbId, Long dtddId, String reason) {
        log.trace("[{}] dtddPropose({}, {}, {})", code, igdbId, dtddId, reason);
        // no-op in mock mode
    }
}
