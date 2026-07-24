package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SteamPlaytimeFunctionTest {

    @Test
    void invokeReturnsFormattedPlaytime() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");

        when(client.getPlaytime("123", "1091500", null))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofMinutes(732))));

        SteamPlaytimeFunction fn = new SteamPlaytimeFunction(client, encryption);
        assertThat(fn.namespace()).isEqualTo("steam");
        assertThat(fn.name()).isEqualTo("playtime");
        assertThat(fn.parameterNames()).containsExactly("appId");
        assertThat(fn.invoke(user, new Object[]{"1091500"})).isEqualTo("12h12m");
    }

    @Test
    void invokeReturnsEmptyStringWhenNeverPlayed() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");

        when(client.getPlaytime("123", "0", null))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        SteamPlaytimeFunction fn = new SteamPlaytimeFunction(client, encryption);
        assertThat(fn.invoke(user, new Object[]{"0"})).isEqualTo("");
    }

    @Test
    void invokeReturnsEmptyStringWhenNoLinkedSteamAccount() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();

        SteamPlaytimeFunction fn = new SteamPlaytimeFunction(client, encryption);
        assertThat(fn.invoke(user, new Object[]{"1091500"})).isEqualTo("");
    }

    @Test
    void invokeUsesDecryptedPersonalTokenWhenPresent() throws Exception {
        SteamApiClient client = mock(SteamApiClient.class);
        TokenEncryptionService encryption = mock(TokenEncryptionService.class);
        UserAccount user = new UserAccount();
        user.setSteamId("123");
        user.setSteamPersonalToken("encrypted");

        when(encryption.decrypt("encrypted")).thenReturn("raw-token");
        when(client.getPlaytime("123", "1091500", "raw-token"))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofMinutes(60))));

        SteamPlaytimeFunction fn = new SteamPlaytimeFunction(client, encryption);
        assertThat(fn.invoke(user, new Object[]{"1091500"})).isEqualTo("1h0m");
    }
}
