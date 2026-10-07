package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.SteamAddKeyRequest;
import fr.enimaloc.catapult.common.dto.SteamDeleteKeyRequest;
import fr.enimaloc.catapult.common.dto.SteamKeyStatus;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared Steam Web API key pool, as administered (keys are only ever shown masked). */
class ApiAdminSteamKeysControllerTest {

    private static final String KEY = "0123456789ABCDEF0123456789ABCDEF";
    private static final String OTHER = "FEDCBA9876543210FEDCBA9876543210";

    private final SteamApiKeyRepository repository = mock(SteamApiKeyRepository.class);
    private final SteamApiKeyRotator rotator = mock(SteamApiKeyRotator.class);
    private final AdminEventPublisher events = mock(AdminEventPublisher.class);
    private final ApiAdminSteamKeysController controller = new ApiAdminSteamKeysController(repository, rotator, events);

    @BeforeEach
    void setUp() {
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of());
    }

    private static SteamApiKeyEntry entry(String key, String ownerName) {
        SteamApiKeyEntry entry = new SteamApiKeyEntry(key);
        if (ownerName != null) {
            UserAccount owner = new UserAccount();
            owner.setTwitchUsername(ownerName);
            entry.setOwner(owner);
        }
        return entry;
    }

    @Test
    void page_masksKeys_andShowsOwnersAndBlocks() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(entry(KEY, "donor"), entry(OTHER, null), entry("short", null)));
        when(rotator.getKeyBlockedUntil()).thenReturn(Map.of(
                KEY, System.currentTimeMillis() + 90_000,
                OTHER, System.currentTimeMillis() - 1_000));

        var page = controller.page();

        assertThat(page.steamEnabled()).isTrue();
        assertThat(page.keys()).hasSize(3);
        SteamKeyStatus first = page.keys().getFirst();
        assertThat(first.id()).isEqualTo(ApiKeyHasher.id(KEY));
        assertThat(first.masked()).isEqualTo("0123…CDEF");
        assertThat(first.owner()).isEqualTo("donor");
        assertThat(first.blocked()).isTrue();
        assertThat(first.blockedForSeconds()).isBetween(85L, 90L);
        assertThat(page.keys().get(1).blocked()).isFalse();
        assertThat(page.keys().get(1).blockedForSeconds()).isZero();
        assertThat(page.keys().get(2).masked()).isEqualTo("…");
    }

    @Test
    void withoutRotatorOrEvents_steamIsReportedDisabled_andEditsStillWork() {
        ApiAdminSteamKeysController bare = new ApiAdminSteamKeysController(repository, null, null);
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(entry(KEY, null)));
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entry(KEY, null)));

        assertThat(bare.page().steamEnabled()).isFalse();
        bare.add(new SteamAddKeyRequest(OTHER));
        bare.delete(new SteamDeleteKeyRequest(ApiKeyHasher.id(KEY)));
        bare.refresh();

        verify(repository).deleteById(KEY);
    }

    @Test
    void add_storesNewKeys_refreshesTheRotator_andAnnouncesThem() {
        when(repository.existsById(KEY)).thenReturn(false);

        controller.add(new SteamAddKeyRequest("  " + KEY + " "));

        verify(repository).save(any(SteamApiKeyEntry.class));
        verify(rotator).refreshKeys();
        ArgumentCaptor<Object> status = ArgumentCaptor.forClass(Object.class);
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_STEAM), status.capture());
        assertThat(status.getValue()).isEqualTo(new SteamKeyStatus(ApiKeyHasher.id(KEY), "0123…CDEF", null, false, 0L));
    }

    @Test
    void add_ignoresKnownKeys_andRejectsMalformedOnes() {
        when(repository.existsById(KEY)).thenReturn(true);
        controller.add(new SteamAddKeyRequest(KEY));
        verify(repository, never()).save(any());

        assertThatThrownBy(() -> controller.add(new SteamAddKeyRequest("not-a-key")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void delete_findsTheKeyByItsId() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entry(OTHER, null), entry(KEY, null)));

        controller.delete(new SteamDeleteKeyRequest(ApiKeyHasher.id(KEY)));

        verify(repository).deleteById(KEY);
        verify(rotator).refreshKeys();
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_STEAM, ApiKeyHasher.id(KEY));
    }

    @Test
    void delete_unknownIds_are404() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entry(KEY, null)));

        assertThatThrownBy(() -> controller.delete(new SteamDeleteKeyRequest("nope")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void refresh_reloadsTheRotator_andBroadcastsTheList() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(entry(KEY, null)));

        controller.refresh();

        verify(rotator).refreshKeys();
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_STEAM), anyList());
    }
}
