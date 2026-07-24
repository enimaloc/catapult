package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SteamGetProfileFunctionTest {

    @Test
    void invokeReturnsProfileFieldsWhenPublicAndFound() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");

        when(client.getProfileStatus("123", null))
            .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(true, false)));
        when(client.getPlayerProfile("123", null)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new SteamApiClient.PlayerSummary("456", "Valorant", "MyStreamer", "online"))));

        SteamGetProfileFunction fn = new SteamGetProfileFunction(client, encryption);
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("getProfile");
        assertThat(fn.parameterNames()).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("displayName")).isEqualTo("MyStreamer");
        assertThat(result.get("onlineStatus")).isEqualTo("online");
        assertThat(result.get("currentGame")).isEqualTo("Valorant");
    }

    @Test
    void invokeReturnsEmptyFieldsWhenProfileIsPrivate() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");

        when(client.getProfileStatus("123", null))
            .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(false, false)));

        SteamGetProfileFunction fn = new SteamGetProfileFunction(client, encryption);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("displayName")).isEqualTo("");
        assertThat(result.get("onlineStatus")).isEqualTo("");
        assertThat(result.get("currentGame")).isEqualTo("");
    }

    @Test
    void invokeReturnsEmptyFieldsWhenNoLinkedSteamAccount() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();

        SteamGetProfileFunction fn = new SteamGetProfileFunction(client, encryption);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("displayName")).isEqualTo("");
        assertThat(result.get("onlineStatus")).isEqualTo("");
        assertThat(result.get("currentGame")).isEqualTo("");
    }

    @Test
    void invokeReturnsNoGameAsEmptyStringWhenNotPlayingAnything() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");

        when(client.getProfileStatus("123", null))
            .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(true, false)));
        when(client.getPlayerProfile("123", null)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new SteamApiClient.PlayerSummary(null, null, "MyStreamer", "online"))));

        SteamGetProfileFunction fn = new SteamGetProfileFunction(client, encryption);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) fn.invoke(user, new Object[0]);
        assertThat(result.get("currentGame")).isEqualTo("");
    }
}
