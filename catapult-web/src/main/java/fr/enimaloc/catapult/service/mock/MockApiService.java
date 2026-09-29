package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.ws.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
        eventPublisher.publishEvent(new BotStateChangedEvent(username, data.isBotEnabled()));
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
        eventPublisher.publishEvent(new CclStateEvent(username, bindingId, enabled));
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("[{}] ignoredToggle({}, {}, {})", code, username, bindingId, ignored);
        data.setBindingIgnored(bindingId, ignored);
        eventPublisher.publishEvent(new BindingIgnoredStateEvent(username, bindingId, ignored));
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        log.trace("[{}] deleteBinding({}, {})", code, username, bindingId);
        data.deleteBinding(bindingId);
        eventPublisher.publishEvent(new BindingDeletedEvent(username, bindingId));
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
        eventPublisher.publishEvent(new BindingUpdatedEvent(username, bindingId, twitchGameId, twitchGameName, ccls));
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        log.trace("[{}] saveTws({}, {})", code, bindingId, tws);
        data.setBindingTws(bindingId, tws);
        eventPublisher.publishEvent(new TwUpdatedEvent(data.getChannelDto().twitchUsername(), bindingId, tws));
    }

    @Override
    public void resetTws(String bindingId) {
        log.trace("[{}] resetTws({})", code, bindingId);
        data.resetBindingTws(bindingId);
        eventPublisher.publishEvent(new TwResetEvent(data.getChannelDto().twitchUsername(), bindingId));
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("[{}] toggleTwEnabled({}, {})", code, bindingId, enabled);
        data.setBindingTwEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new TwEnabledStateEvent(data.getChannelDto().twitchUsername(), bindingId, enabled));
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        log.trace("[{}] saveSteamToken({}, {}, {})", code, username, token, shared);
        data.saveSteamToken(shared);
        eventPublisher.publishEvent(new SteamTokenSavedEvent(username, shared));
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        log.trace("[{}] steamTokenSharing({}, {})", code, username, shared);
        data.setSteamTokenShared(shared);
        eventPublisher.publishEvent(new SteamTokenSharedStateEvent(username, shared));
    }

    @Override
    public void deleteSteamToken(String username) {
        log.trace("[{}] deleteSteamToken({})", code, username);
        data.deleteSteamToken();
        eventPublisher.publishEvent(new SteamTokenDeletedEvent(username));
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
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftEnrollEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftSync(String username) {
        log.trace("[{}] minecraftSync({})", code, username);
        data.minecraftSync();
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftSyncEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftDisconnect(String username) {
        log.trace("[{}] minecraftDisconnect({})", code, username);
        data.minecraftDisconnect();
        eventPublisher.publishEvent(new MinecraftDisconnectedEvent(username));
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
