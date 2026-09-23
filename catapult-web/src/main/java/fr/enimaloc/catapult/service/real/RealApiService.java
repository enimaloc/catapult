package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.SaveBody;
import fr.enimaloc.catapult.common.dto.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.TwEnabledBody;
import fr.enimaloc.catapult.common.dto.UpdateBindingRequest;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Profile("!mock")
public class RealApiService implements ApiService {
    private final ApiClient client;

    @Override
    public TokenResponse exchangeCode(String code) {
        return client.post("/api/auth/exchange?code={code}", TokenResponse.class, code);
    }

    @Override
    public ChannelListResponse channelList() {
        return client.get("/api/channel", ChannelListResponse.class);
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        StringBuilder path = new StringBuilder("/api/channels/{username}?page={page}");
        List<Object> vars = new ArrayList<>(List.of(username, page));
        if (status != null && !status.isBlank()) {
            path.append("&status={status}");
            vars.add(status);
        } else if (source != null && !source.isBlank()) {
            path.append("&source={source}");
            vars.add(source);
        }
        return client.get(path.toString(), ChannelPageData.class, vars.toArray());
    }

    @Override
    public void toggleBot(String username) {
        client.postVoid("/api/channels/{username}/settings/bot", null, username);
    }

    @Override
    public void recheckGame(String username) {
        client.postVoid("/api/channels/{username}/game/recheck", null, username);
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/ccl-toggle",
                new CclToggleRequest(enabled), username, bindingId);
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/ignored-toggle",
                new IgnoredToggleRequest(ignored), username, bindingId);
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/delete", null, username, bindingId);
    }

    @Override
    public Object searchGames(String username, String q) {
        return client.get("/api/channels/{username}/games/search?q={q}", Object.class, username, q);
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}",
                new UpdateBindingRequest(twitchGameId, twitchGameName, ccls), username, bindingId);
    }

    @Override
    public void saveTws(String bindingId, java.util.Set<String> tws) {
        client.postVoid("/api/channel/bindings/{bindingId}/tws", new SaveBody(tws), bindingId);
    }

    @Override
    public void resetTws(String bindingId) {
        client.postVoid("/api/channel/bindings/{bindingId}/tws/reset", null, bindingId);
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        client.postVoid("/api/channel/bindings/{bindingId}/tw-enabled", new TwEnabledBody(enabled), bindingId);
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token",
                new SteamTokenRequest(token, shared), username);
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token/sharing",
                new SteamTokenSharingRequest(shared), username);
    }

    @Override
    public void deleteSteamToken(String username) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token/delete", null, username);
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        client.postVoid("/api/channels/{username}/steam/refresh-profile-cache", null, username);
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        return client.get("/api/connect/minecraft", LinkStateResponse.class);
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        client.postVoid("/api/connect/minecraft", java.util.Map.of("name", name));
    }

    @Override
    public void minecraftSync(String username) {
        client.postVoid("/api/connect/minecraft/sync", null);
    }

    @Override
    public void minecraftDisconnect(String username) {
        client.reqVoid(org.springframework.http.HttpMethod.DELETE, "/api/connect/minecraft", null, null);
    }
}
