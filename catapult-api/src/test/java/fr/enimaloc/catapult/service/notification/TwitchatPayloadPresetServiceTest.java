package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActivePreset;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.TwitchatPayloadPreset;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatActivePresetRepository;
import fr.enimaloc.catapult.repository.TwitchatPayloadPresetRepository;
import fr.enimaloc.catapult.service.notification.dto.TwitchatPresetPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatPayloadPresetServiceTest {

    @Mock private TwitchatPayloadPresetRepository presetRepository;
    @Mock private TwitchatActivePresetRepository activePresetRepository;
    private final ObjectMapper jackson = JsonMapper.builder().build();
    private TwitchatPayloadPresetService service;

    private UserAccount user;

    @BeforeEach
    void setup() {
        service = new TwitchatPayloadPresetService(presetRepository, activePresetRepository, jackson);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void createPreset_validJson_savesAndReturnsPreset() {
        when(presetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatPayloadPreset result = service.createPreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                "Discret", "{\"message\":\"Le bot tourne.\"}");

        assertThat(result.getName()).isEqualTo("Discret");
        assertThat(result.getEventType()).isEqualTo(TwitchatNotificationEventType.STREAM_STARTED);
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getId()).isNotNull();
    }

    @Test
    void createPreset_invalidJson_rejectedWithBadRequest() {
        assertThatThrownBy(() -> service.createPreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                "Cassé", "not json"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(presetRepository);
    }

    @Test
    void createPreset_blankMessage_rejectedWithBadRequest() {
        assertThatThrownBy(() -> service.createPreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                "Vide", "{\"message\":\"  \"}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(presetRepository);
    }

    @Test
    void updatePreset_notOwnedByUser_notFound() {
        TwitchatPayloadPreset other = new TwitchatPayloadPreset();
        other.setId(UUID.randomUUID());
        UserAccount otherUser = new UserAccount();
        otherUser.setId(UUID.randomUUID());
        other.setUser(otherUser);
        when(presetRepository.findById(other.getId())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updatePreset(user, other.getId(), "x", "{\"message\":\"x\"}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deletePreset_removesActivePresetRowToo() {
        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setUser(user);
        when(presetRepository.findById(preset.getId())).thenReturn(Optional.of(preset));

        service.deletePreset(user, preset.getId());

        verify(activePresetRepository).deleteByPreset(preset);
        verify(presetRepository).delete(preset);
    }

    @Test
    void setActivePreset_nullPresetId_revertsToDefault() {
        service.setActivePreset(user, TwitchatNotificationEventType.STREAM_STARTED, null);

        verify(activePresetRepository).deleteById(new TwitchatActivePreset.Key(
                user.getId(), TwitchatNotificationEventType.STREAM_STARTED));
    }

    @Test
    void setActivePreset_eventTypeMismatch_rejectedWithBadRequest() {
        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setUser(user);
        preset.setEventType(TwitchatNotificationEventType.BOT_ENABLED);
        when(presetRepository.findById(preset.getId())).thenReturn(Optional.of(preset));

        assertThatThrownBy(() -> service.setActivePreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                preset.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void findActivePresetPayload_noActiveRow_returnsEmpty() {
        when(activePresetRepository.findByUserIdAndEventType(user.getId(), TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.empty());

        assertThat(service.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED)).isEmpty();
    }

    @Test
    void findActivePresetPayload_corruptedJson_returnsEmptyInsteadOfThrowing() {
        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setPayloadJson("{not valid");
        TwitchatActivePreset active = new TwitchatActivePreset();
        active.setUserId(user.getId());
        active.setEventType(TwitchatNotificationEventType.STREAM_STARTED);
        active.setPreset(preset);
        when(activePresetRepository.findByUserIdAndEventType(user.getId(), TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(active));

        assertThat(service.findActivePresetPayload(user, TwitchatNotificationEventType.STREAM_STARTED)).isEmpty();
    }

    @Test
    void findActivePresetPayload_validJson_returnsParsedPayload() {
        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setPayloadJson("{\"message\":\"Custom\",\"icon\":\"live\"}");
        TwitchatActivePreset active = new TwitchatActivePreset();
        active.setUserId(user.getId());
        active.setEventType(TwitchatNotificationEventType.STREAM_STARTED);
        active.setPreset(preset);
        when(activePresetRepository.findByUserIdAndEventType(user.getId(), TwitchatNotificationEventType.STREAM_STARTED))
                .thenReturn(Optional.of(active));

        Optional<TwitchatPresetPayload> result = service.findActivePresetPayload(user,
                TwitchatNotificationEventType.STREAM_STARTED);

        assertThat(result).isPresent();
        assertThat(result.get().message()).isEqualTo("Custom");
        assertThat(result.get().icon()).isEqualTo("live");
    }

    @Test
    void listPresets_delegatesToRepository() {
        List<TwitchatPayloadPreset> presets = List.of(new TwitchatPayloadPreset());
        when(presetRepository.findByUser(user)).thenReturn(presets);

        assertThat(service.listPresets(user)).isSameAs(presets);
    }

    @Test
    void createPreset_oldActionsMapFormat_rejectedAsInvalidJson() {
        // Guards the schema migration: a preset saved in the old
        // Map<TwitchatActionType, {label,theme}> shape must fail Jackson deserialization against
        // the new List<TwitchatRawAction> field type, not silently misinterpret it.
        assertThatThrownBy(() -> service.createPreset(user, TwitchatNotificationEventType.STREAM_STARTED,
                "Old format", "{\"message\":\"Live.\",\"actions\":{\"DISABLE_BOT\":{\"label\":\"Stop\",\"theme\":\"alert\"}}}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void parseAndValidate_validJson_returnsParsedPayload() {
        TwitchatPresetPayload result = service.parseAndValidate("{\"message\":\"Live.\"}");

        assertThat(result.message()).isEqualTo("Live.");
    }

    @Test
    void parseAndValidate_blankMessage_rejectedWithBadRequest() {
        assertThatThrownBy(() -> service.parseAndValidate("{\"message\":\"  \"}"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
