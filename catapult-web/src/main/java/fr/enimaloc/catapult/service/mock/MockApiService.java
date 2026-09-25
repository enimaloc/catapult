package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.ws.event.ChannelUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    private String code;
    private MockData data;

    /** Exposes the live singleton instance for {@link MockAdminController} to read and mutate. */
    public MockData getData() {
        return data;
    }

    /** Publishes a {@link ChannelUpdatedEvent} for any live /channel/{username} tab. */
    void broadcast() {
        if (data != null) {
            eventPublisher.publishEvent(new ChannelUpdatedEvent(data.getChannelDto().twitchUsername()));
        }
    }

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
        broadcast();
    }

    @Override
    public void recheckGame(String username) {
        log.trace("[{}] recheckGame({})", code, username);
        // no-op in mock mode
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        log.trace("[{}] cclToggle({}, {}, {})", code, username, bindingId, enabled);
        data.setBindingCclEnabled(bindingId, enabled);
        broadcast();
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("[{}] ignoredToggle({}, {}, {})", code, username, bindingId, ignored);
        data.setBindingIgnored(bindingId, ignored);
        broadcast();
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        log.trace("[{}] deleteBinding({}, {})", code, username, bindingId);
        data.deleteBinding(bindingId);
        broadcast();
    }

    @Override
    public Object searchGames(String username, String q) {
        log.trace("[{}] searchGames({}, {})", code, username, q);
        return List.of(Map.of("id", "509658", "name", "Celeste"));
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("[{}] updateBinding({}, {}, {}, {}, {})", code, username, bindingId, twitchGameId, twitchGameName, ccls);
        data.updateBindingGame(bindingId, twitchGameId, twitchGameName, ccls);
        broadcast();
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        log.trace("[{}] saveTws({}, {})", code, bindingId, tws);
        data.setBindingTws(bindingId, tws);
        broadcast();
    }

    @Override
    public void resetTws(String bindingId) {
        log.trace("[{}] resetTws({})", code, bindingId);
        data.resetBindingTws(bindingId);
        broadcast();
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("[{}] toggleTwEnabled({}, {})", code, bindingId, enabled);
        data.setBindingTwEnabled(bindingId, enabled);
        broadcast();
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        log.trace("[{}] saveSteamToken({}, {}, {})", code, username, token, shared);
        data.saveSteamToken(shared);
        broadcast();
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        log.trace("[{}] steamTokenSharing({}, {})", code, username, shared);
        data.setSteamTokenShared(shared);
        broadcast();
    }

    @Override
    public void deleteSteamToken(String username) {
        log.trace("[{}] deleteSteamToken({})", code, username);
        data.deleteSteamToken();
        broadcast();
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
        data.minecraftEnroll(name);
        broadcast();
    }

    @Override
    public void minecraftSync(String username) {
        log.trace("[{}] minecraftSync({})", code, username);
        data.minecraftSync();
        broadcast();
    }

    @Override
    public void minecraftDisconnect(String username) {
        log.trace("[{}] minecraftDisconnect({})", code, username);
        data.minecraftDisconnect();
        broadcast();
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls) {
        log.trace("[{}] saveCclSettings({}, {}, {})", code, username, enabled, blockedCcls);
        data.saveCclSettings(enabled, blockedCcls);
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws) {
        log.trace("[{}] saveTwSettings({}, {}, {})", code, username, enabled, blockedTws);
        data.saveTwSettings(enabled, blockedTws);
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
        data.saveNoGameSettings(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("[{}] saveIncompleteFallbackSettings({}, {}, {}, {})", code, username, twitchGameId, twitchGameName, ccls);
        data.saveIncompleteFallbackSettings(twitchGameId, twitchGameName, ccls);
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
