package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import fr.enimaloc.catapult.getter.supercell.SupercellApiKeyRotator;
import fr.enimaloc.catapult.repository.supercell.SupercellApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import fr.enimaloc.catapult.service.supercell.SupercellKeyPoolService;
import fr.enimaloc.catapult.service.supercell.SupercellTestKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAdminSupercellKeysControllerTest {

    static final String COC = SupercellTestKeys.clash("admin");
    static final String ROYALE = SupercellTestKeys.key("royale", "203.0.113.7/32", "admin");

    @Mock SupercellApiKeyRepository repository;
    @Mock AdminEventPublisher events;
    EnrichmentProperties properties = new EnrichmentProperties();
    SupercellApiKeyRotator rotator;
    ApiAdminSupercellKeysController controller;

    @BeforeEach
    void setUp() {
        lenient().when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new SupercellApiKeyRotator(repository, new SimpleMeterRegistry());
        controller = new ApiAdminSupercellKeysController(
                new SupercellKeyPoolService(repository, rotator, properties, events));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fake-key", "abc.def.ghi"})
    void add_refusesAnInvalidFormat_with400(String key) {
        assertStatus(() -> controller.add("clash-of-clans", new AddKeyRequest(key)), HttpStatus.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    @Test
    void add_refusesAMissingBodyOrKey() {
        assertStatus(() -> controller.add("clash-of-clans", null), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.add("clash-of-clans", new AddKeyRequest(null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void anUnknownGame_is404() {
        assertStatus(() -> controller.page("hay-day"), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.add("hay-day", new AddKeyRequest(COC)), HttpStatus.NOT_FOUND);
    }

    @Test
    void add_refusesAKeyOfAnotherGame() {
        assertStatus(() -> controller.add("clash-of-clans", new AddKeyRequest(ROYALE)), HttpStatus.BAD_REQUEST);
        verify(repository, never()).save(any());
    }

    @Test
    void add_takesAKeyWhosePayloadCannotBeRead() {
        String key = SupercellTestKeys.unreadable();
        when(repository.existsById(key)).thenReturn(false);

        controller.add("brawl-stars", new AddKeyRequest(key));

        verify(repository).save(any());
    }

    @Test
    void add_savesTheTrimmedKeyInItsGame_andTellsTheAdminMasked() {
        when(repository.existsById(COC)).thenReturn(false);

        controller.add("Clash-Of-Clans", new AddKeyRequest("  " + COC + " "));

        ArgumentCaptor<SupercellApiKeyEntry> saved = ArgumentCaptor.forClass(SupercellApiKeyEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKey()).isEqualTo(COC);
        assertThat(saved.getValue().getGame()).isEqualTo("clash-of-clans");
        ArgumentCaptor<Object> status = ArgumentCaptor.forClass(Object.class);
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_CLASHOFCLANS), status.capture());
        assertThat(status.getValue().toString()).doesNotContain(COC).contains("IP 203.0.113.7/32");
    }

    @Test
    void add_aKeyAlreadyThere_changesNothing() {
        when(repository.existsById(COC)).thenReturn(true);

        controller.add("clash-of-clans", new AddKeyRequest(COC));

        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void page_listsTheGamesKeysOnly_masked_withTheEnabledFlag() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(
                new SupercellApiKeyEntry(COC, "clash-of-clans"), new SupercellApiKeyEntry(ROYALE, "clash-royale")));

        var page = controller.page("clash-of-clans");

        assertThat(page.supercellEnabled()).isTrue();
        assertThat(page.keys()).hasSize(1);
        KeyStatus key = page.keys().getFirst();
        assertThat(key.id()).isEqualTo(ApiKeyHasher.id(COC));
        assertThat(key.masked()).endsWith("(IP 203.0.113.7/32)");
        assertThat(page.toString()).doesNotContain(COC);

        properties.getSupercell().setEnabled(false);
        assertThat(controller.page("clash-of-clans").supercellEnabled()).isFalse();
    }

    @Test
    void page_showsAKeyRefusedFromThisIpAsBlocked() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry(COC, "clash-of-clans")));
        when(repository.findByExclusiveFalseWithOwner())
                .thenReturn(List.of(new SupercellApiKeyEntry(COC, "clash-of-clans")));
        rotator.refreshKeys();
        rotator.onIpRefused(fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame.CLASH_OF_CLANS, COC);

        KeyStatus key = controller.page("clash-of-clans").keys().getFirst();

        assertThat(key.blocked()).isTrue();
        assertThat(key.blockedForSeconds()).isPositive();
    }

    @Test
    void delete_removesTheKeyOfThatIdInThatGame_andElseIs404() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry(COC, "clash-of-clans")));

        assertStatus(() -> controller.delete("clash-royale", ApiKeyHasher.id(COC)), HttpStatus.NOT_FOUND);
        controller.delete("clash-of-clans", ApiKeyHasher.id(COC));

        verify(repository).deleteById(COC);
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_CLASHOFCLANS, ApiKeyHasher.id(COC));
        assertStatus(() -> controller.delete("clash-of-clans", "nope"), HttpStatus.NOT_FOUND);
    }

    @Test
    void refresh_reloadsTheRotator_andTellsTheAdminOfThatGame() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new SupercellApiKeyEntry(COC, "clash-of-clans")));

        controller.refresh("brawl-stars");

        assertThat(rotator.hasKeys(fr.enimaloc.catapult.service.enrichment.supercell.SupercellGame.CLASH_OF_CLANS))
                .isTrue();
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_BRAWLSTARS), anyList());
    }
}
