package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class MinecraftService {
    public static final String MINECRAFT_SERVICE_URL = "https://api.minecraftservices.com";
    private final RestClient restClient;

    public Token getMinecraftToken(XboxService.Token token) {
        return restClient.post()
                .uri(URI.create(MINECRAFT_SERVICE_URL + "/authentication/login_with_xbox"))
                .body(Map.of("identityToken", "XBL3.0 x="+token.displayClaims().xui()[0].uhs()+";"+token.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Token.class);
    }

    public FriendsList removeFriend(Token token, @Nullable String playerName, @Nullable String profileId) {
        return removeFriend(token.accessToken, playerName, profileId);
    }

    public FriendsList removeFriend(String token, @Nullable String playerName, @Nullable String profileId) {
        return manageFriends(token, playerName, profileId, "REMOVE");
    }

    public FriendsList removeFriend(Token token, FriendsList.Friend friend) {
        return removeFriend(token.accessToken, friend);
    }

    public FriendsList removeFriend(String token, FriendsList.Friend friend) {
        return manageFriends(token, null, friend.profileId(), "REMOVE");
    }

    public FriendsList addFriend(Token token, @Nullable String playerName, @Nullable String profileId) {
        return addFriend(token.accessToken, playerName, profileId);
    }

    public FriendsList addFriend(String token, @Nullable String playerName, @Nullable String profileId) {
        return manageFriends(token, playerName, profileId, "ADD");
    }

    public FriendsList manageFriends(Token token, @Nullable String playerName, @Nullable String profileId, String action) {
        return manageFriends(token.accessToken, playerName, profileId, action);
    }

    public FriendsList manageFriends(String token, @Nullable String playerName, @Nullable String profileId, String action) {
        Map<String, String> body = new HashMap<>();
        if (playerName != null) body.put("name", playerName);
        if (profileId != null) body.put("profileId", profileId);
        body.put("updateType", action);
        return restClient.put()
                .uri(URI.create(MINECRAFT_SERVICE_URL + "/friends"))
                .body(body)
                .header("Authorization", "Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(FriendsList.class);
    }

    public FriendsList getFriends(Token token) {
        return getFriends(token.accessToken);
    }

    public FriendsList getFriends(String token) {
        return restClient.get()
                .uri(URI.create(MINECRAFT_SERVICE_URL + "/friends"))
                .header("Authorization", "Bearer "+token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(FriendsList.class);
    }

    public PresenceList updatePresence(Token token, PresenceStatus status) {
        return updatePresence(token.accessToken, status);
    }

    public PresenceList updatePresence(String token, PresenceStatus status) {
        return updatePresence(token, new PresenceUpdate(status, new PresenceUpdate.JoinInfo(null, new String[0])));
    }

    public PresenceList updatePresence(Token token, PresenceUpdate update) {
        return updatePresence(token.accessToken, update);
    }

    /**
     * Déclare la présence du joueur et retourne en échange celles de ses amis
     * (l'API n'expose pas de GET séparé, l'échange est bidirectionnel).
     */
    public PresenceList updatePresence(String token, PresenceUpdate update) {
        return restClient.post()
                .uri(URI.create(MINECRAFT_SERVICE_URL + "/presence"))
                .body(update)
                .header("Authorization", "Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(PresenceList.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Token(
            String username,
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            Object[] roles,
            @JsonProperty("token_type") String tokenType,
            Object metadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FriendsList(
            Friend[] friends,
            Friend[] incomingRequests,
            Friend[] outgoingRequests,
            boolean empty
    ) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Friend(String profileId, String name) {}
    }

    public enum PresenceStatus {
        OFFLINE, ONLINE, PLAYING_OFFLINE, PLAYING_HOSTED_SERVER, PLAYING_REALMS, PLAYING_SERVER
    }

    public record PresenceUpdate(PresenceStatus status, JoinInfo joinInfo) {

        public record JoinInfo(@Nullable String value, String[] invites) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresenceList(Presence[] presence) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Presence(
                String profileId,
                String pmid,
                PresenceStatus status,
                JoinInfo joinInfo,
                Instant lastUpdated
        ) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            public record JoinInfo(String value, boolean invited) {}
        }
    }
}
