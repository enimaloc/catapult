package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.ws.event.*;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("mock")
public class MockApiService implements ApiService {
    private final ApplicationEventPublisher eventPublisher;

    /**
     * One {@link MockData} per mock connection, keyed by a random token minted in
     * {@link #exchangeCode} — the same token {@link fr.enimaloc.catapult.controller.AuthController}
     * stores under {@link ApiClient#SESSION_JWT_KEY}, so each browser session gets its own
     * isolated mock state instead of every tab sharing one global instance.
     */
    private final Map<String, MockData> data = new ConcurrentHashMap<>();

    /**
     * Resolves the calling session's data the same way {@link ApiClient} resolves its JWT.
     * The token is a random UUID minted in {@link #exchangeCode}, not a JWT, so it can't be
     * fed to {@link MockData#fromJwt} — if the entry is missing (e.g. the server restarted and
     * lost its in-memory state while the browser session cookie survived), data is regenerated
     * deterministically from the token via {@link MockData#randomFrom} so it stays the same
     * across repeated requests instead of reshuffling every time it's rebuilt.
     */
    private MockData currentData() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpSession session = attrs.getRequest().getSession(false);
        String token = session != null ? (String) session.getAttribute(ApiClient.SESSION_JWT_KEY) : null;
        if (token != null && !data.containsKey(token)) data.put(token, MockData.randomFrom(token));
        return token != null ? data.get(token) : null;
    }

    /** Exposes the calling session's live instance for {@link fr.enimaloc.catapult.controller.mock.MockAdminController} to read and mutate. */
    public MockData getData() {
        return currentData();
    }

    @Override
    public TokenResponse exchangeCode(String code) {
        log.trace("exchangeCode({})", code);
        // A full JSON config from the mock login form still gets honored, so scenarios stay
        // reproducible; anything else (the digit quick-launch links) gets fresh random data
        // instead of always reusing the same fixed preset.
        MockData mockData = code != null && code.startsWith("{") ? MockData.fromJwt(code) : MockData.random();
        String token = UUID.randomUUID().toString();
        data.put(token, mockData);
        return new TokenResponse(token);
    }

    @Override
    public ChannelListResponse channelList() {
        log.trace("channelList()");
        return currentData().getChannelList();
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        log.trace("channelPage({}, {}, {}, {})", username, page, status, source);
        return currentData().getPage(username, status, source);
    }

    @Override
    public void toggleBot(String username) {
        log.trace("toggleBot({})", username);
        MockData data = currentData();
        data.setBotEnabled(!data.isBotEnabled());
        eventPublisher.publishEvent(new BotStateChangedEvent(username, data.isBotEnabled()));
    }

    @Override
    public void recheckGame(String username) {
        log.trace("recheckGame({})", username);
        // no-op in mock mode
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        log.trace("cclToggle({}, {}, {})", username, bindingId, enabled);
        currentData().setBindingCclEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new CclStateEvent(username, bindingId, enabled));
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("ignoredToggle({}, {}, {})", username, bindingId, ignored);
        currentData().setBindingIgnored(bindingId, ignored);
        eventPublisher.publishEvent(new BindingIgnoredStateEvent(username, bindingId, ignored));
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        log.trace("deleteBinding({}, {})", username, bindingId);
        currentData().deleteBinding(bindingId);
        eventPublisher.publishEvent(new BindingDeletedEvent(username, bindingId));
    }

    @Override
    public Object searchGames(String username, String q) {
        log.trace("searchGames({}, {})", username, q);
        return Arrays.stream(MockData.CATEGORIES_DTO)
                .filter(dto -> dto.name().toLowerCase().contains(q.toLowerCase()))
                .map(dto -> Map.of("id", dto.id(), "name", dto.name()))
                .limit(10)
                .toList();
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("updateBinding({}, {}, {}, {}, {})", username, bindingId, twitchGameId, twitchGameName, ccls);
        currentData().updateBindingGame(bindingId, twitchGameId, twitchGameName, ccls);
        eventPublisher.publishEvent(new BindingUpdatedEvent(username, bindingId, twitchGameId, twitchGameName, ccls));
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        log.trace("saveTws({}, {})", bindingId, tws);
        MockData data = currentData();
        data.setBindingTws(bindingId, tws);
        eventPublisher.publishEvent(new TwUpdatedEvent(data.getChannelDto().twitchUsername(), bindingId, tws));
    }

    @Override
    public void resetTws(String bindingId) {
        log.trace("resetTws({})", bindingId);
        MockData data = currentData();
        data.resetBindingTws(bindingId);
        eventPublisher.publishEvent(new TwResetEvent(data.getChannelDto().twitchUsername(), bindingId));
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("toggleTwEnabled({}, {})", bindingId, enabled);
        MockData data = currentData();
        data.setBindingTwEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new TwEnabledStateEvent(data.getChannelDto().twitchUsername(), bindingId, enabled));
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        log.trace("saveSteamToken({}, {}, {})", username, token, shared);
        currentData().saveSteamToken(shared);
        eventPublisher.publishEvent(new SteamTokenSavedEvent(username, shared));
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        log.trace("steamTokenSharing({}, {})", username, shared);
        currentData().setSteamTokenShared(shared);
        eventPublisher.publishEvent(new SteamTokenSharedStateEvent(username, shared));
    }

    @Override
    public void deleteSteamToken(String username) {
        log.trace("deleteSteamToken({})", username);
        currentData().deleteSteamToken();
        eventPublisher.publishEvent(new SteamTokenDeletedEvent(username));
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        log.trace("refreshSteamProfileCache({})", username);
        // no-op in mock mode
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        log.trace("minecraftStatus({})", username);
        return currentData().getMinecraftLink();
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        log.trace("minecraftEnroll({}, {})", username, name);
        MockData data = currentData();
        data.minecraftEnroll(name);
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftEnrollEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftSync(String username) {
        log.trace("minecraftSync({})", username);
        MockData data = currentData();
        data.minecraftSync();
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftSyncEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftDisconnect(String username) {
        log.trace("minecraftDisconnect({})", username);
        currentData().minecraftDisconnect();
        eventPublisher.publishEvent(new MinecraftDisconnectedEvent(username));
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls) {
        log.trace("saveCclSettings({}, {}, {})", username, enabled, blockedCcls);
        currentData().saveCclSettings(enabled, blockedCcls);
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws) {
        log.trace("saveTwSettings({}, {}, {})", username, enabled, blockedTws);
        currentData().saveTwSettings(enabled, blockedTws);
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        log.trace("channelSettings({})", username);
        return currentData().getUserSettings();
    }

    @Override
    public void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        log.trace("saveNoGameSettings({}, {}, {}, {}, {}, {}, {})", username, twitchGameId, twitchGameName,
                ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
        currentData().saveNoGameSettings(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        log.trace("saveIncompleteFallbackSettings({}, {}, {}, {})", username, twitchGameId, twitchGameName, ccls);
        currentData().saveIncompleteFallbackSettings(twitchGameId, twitchGameName, ccls);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.DtddMappingStatusDto dtddMappingStatus(String username) {
        log.trace("dtddMappingStatus({})", username);
        return new fr.enimaloc.catapult.common.dto.DtddMappingStatusDto(null, null, false, null);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.SearchResponse dtddSearch(String q) {
        log.trace("dtddSearch({})", q);
        return new fr.enimaloc.catapult.common.dto.SearchResponse(List.of());
    }

    @Override
    public void dtddValidate(String igdbId) {
        log.trace("dtddValidate({})", igdbId);
        // no-op in mock mode
    }

    @Override
    public void dtddPropose(String igdbId, Long dtddId, String reason) {
        log.trace("dtddPropose({}, {}, {})", igdbId, dtddId, reason);
        // no-op in mock mode
    }
}
