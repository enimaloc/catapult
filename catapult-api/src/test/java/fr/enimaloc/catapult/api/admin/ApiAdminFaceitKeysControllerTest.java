package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddKeyRequest;
import fr.enimaloc.catapult.common.dto.admin.KeyStatus;
import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.getter.faceit.FaceitApiKeyRotator;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import fr.enimaloc.catapult.service.ApiKeyHasher;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.faceit.FaceitKeyPoolService;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
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
class ApiAdminFaceitKeysControllerTest {

    static final String RAW = "12345678-abcd-4ef0-9abc-0123456789ab";

    @Mock FaceitApiKeyRepository repository;
    @Mock AdminEventPublisher events;
    EnrichmentProperties properties = new EnrichmentProperties();
    FaceitApiKeyRotator rotator;
    ApiAdminFaceitKeysController controller;

    @BeforeEach
    void setUp() {
        lenient().when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new FaceitApiKeyRotator(repository, new SimpleMeterRegistry());
        controller = new ApiAdminFaceitKeysController(new FaceitKeyPoolService(repository, rotator, properties,
                events));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "12345678abcd4ef09abc0123456789ab", "12345678-abcd-4ef0-9abc-0123456789ag",
            "12345678-abcd-4ef0-9abc-0123456789ab0", "x 12345678-abcd-4ef0-9abc-0123456789ab"})
    void add_refusesAnInvalidFormat_with400(String key) {
        assertThatThrownBy(() -> controller.add(new AddKeyRequest(key)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void add_refusesAMissingBodyOrKey() {
        assertThatThrownBy(() -> controller.add(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.add(new AddKeyRequest(null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void add_savesTheTrimmedKey_andTellsTheAdminMasked() {
        when(repository.existsById(RAW)).thenReturn(false);

        controller.add(new AddKeyRequest("  " + RAW + " "));

        ArgumentCaptor<FaceitApiKeyEntry> saved = ArgumentCaptor.forClass(FaceitApiKeyEntry.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKey()).isEqualTo(RAW);
        ArgumentCaptor<Object> status = ArgumentCaptor.forClass(Object.class);
        verify(events).keyAdded(eq(AdminEventPublisher.PROVIDER_FACEIT), status.capture());
        assertThat(status.getValue().toString()).doesNotContain(RAW);
    }

    @Test
    void add_aKeyAlreadyThere_changesNothing() {
        when(repository.existsById(RAW)).thenReturn(true);

        controller.add(new AddKeyRequest(RAW));

        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void page_isMasked_withTheEnabledFlag() {
        when(repository.findByExclusiveFalseWithOwner()).thenReturn(List.of(new FaceitApiKeyEntry(RAW)));

        var page = controller.page();

        assertThat(page.faceitEnabled()).isTrue();
        KeyStatus key = page.keys().getFirst();
        assertThat(key.masked()).isEqualTo("1234…89ab");
        assertThat(key.id()).isEqualTo(ApiKeyHasher.id(RAW));
        assertThat(page.toString()).doesNotContain(RAW);

        properties.getFaceit().setEnabled(false);
        assertThat(controller.page().faceitEnabled()).isFalse();
    }

    @Test
    void delete_removesTheKeyOfThatId_andUnknownIs404() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry(RAW)));

        controller.delete(ApiKeyHasher.id(RAW));

        verify(repository).deleteById(RAW);
        verify(events).keyDeleted(AdminEventPublisher.PROVIDER_FACEIT, ApiKeyHasher.id(RAW));
        assertThatThrownBy(() -> controller.delete("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void refresh_reloadsTheRotator_andTellsTheAdmin() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(new FaceitApiKeyEntry(RAW)));

        controller.refresh();

        assertThat(rotator.hasKeys()).isTrue();
        verify(events).keysRefreshed(eq(AdminEventPublisher.PROVIDER_FACEIT), anyList());
    }
}
