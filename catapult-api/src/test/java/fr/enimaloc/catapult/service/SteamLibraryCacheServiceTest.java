package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Steam library pre-caching: owned games warm the IGDB cache, at startup and when Steam gets linked. */
class SteamLibraryCacheServiceTest {

    private final SteamApiClient steam = mock(SteamApiClient.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final SteamLibraryCacheService service = new SteamLibraryCacheService(steam, igdb, accounts, encryption);

    private static UserAccount steamUser(String steamId, String personalToken) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setSteamId(steamId);
        user.setSteamPersonalToken(personalToken);
        return user;
    }

    @Test
    void startup_warmsEveryLinkedLibrary_thenTheCclCache() {
        when(accounts.findBySteamIdNotNull()).thenReturn(List.of(steamUser("1", "enc"), steamUser("2", null)));
        when(encryption.decrypt("enc")).thenReturn("personal");
        when(steam.getOwnedGameIds("1", "personal")).thenReturn(CompletableFuture.completedFuture(List.of("620")));
        when(steam.getOwnedGameIds("2", null)).thenReturn(CompletableFuture.completedFuture(List.of()));

        service.preloadAllUserLibraries();

        verify(igdb).prewarmSteamAppIds(List.of("620"));
        verify(igdb).prewarmCclCache();
    }

    @Test
    void startup_withoutLinkedUsers_stillWarmsTheCclCache() {
        when(accounts.findBySteamIdNotNull()).thenReturn(List.of());

        service.preloadAllUserLibraries();

        verify(igdb).prewarmCclCache();
        verify(steam, never()).getOwnedGameIds(anyString(), any());
    }

    @Test
    void linkingSteam_warmsThatLibrary_andFailuresAreSwallowed() {
        UserAccount user = steamUser("1", null);
        when(steam.getOwnedGameIds("1", null)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        service.onSteamLinked(new SteamLinkedEvent(this, user));

        verify(igdb, never()).prewarmSteamAppIds(anyList());
        verify(igdb).prewarmCclCache();
    }

    @Test
    void anUnlinkedUser_hasNoLibraryToWarm() {
        service.onSteamLinked(new SteamLinkedEvent(this, steamUser(null, null)));

        verify(steam, never()).getOwnedGameIds(anyString(), any());
        verify(igdb).prewarmCclCache();
    }
}
