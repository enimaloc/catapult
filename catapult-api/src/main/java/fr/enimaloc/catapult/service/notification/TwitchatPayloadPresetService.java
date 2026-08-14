package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActivePreset;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.TwitchatPayloadPreset;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatActivePresetRepository;
import fr.enimaloc.catapult.repository.TwitchatPayloadPresetRepository;
import fr.enimaloc.catapult.service.notification.dto.TwitchatPresetPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchatPayloadPresetService {

    private final TwitchatPayloadPresetRepository presetRepository;
    private final TwitchatActivePresetRepository activePresetRepository;
    private final ObjectMapper jackson;

    @Transactional(readOnly = true)
    public List<TwitchatPayloadPreset> listPresets(UserAccount user) {
        return presetRepository.findByUser(user);
    }

    @Transactional
    public TwitchatPayloadPreset createPreset(UserAccount user, TwitchatNotificationEventType eventType,
                                               String name, String payloadJson) {
        parseAndValidate(payloadJson);
        TwitchatPayloadPreset preset = new TwitchatPayloadPreset();
        preset.setId(UUID.randomUUID());
        preset.setUser(user);
        preset.setEventType(eventType);
        preset.setName(name);
        preset.setPayloadJson(payloadJson);
        Instant now = Instant.now();
        preset.setCreatedAt(now);
        preset.setUpdatedAt(now);
        return presetRepository.save(preset);
    }

    @Transactional
    public TwitchatPayloadPreset updatePreset(UserAccount user, UUID presetId, String name, String payloadJson) {
        parseAndValidate(payloadJson);
        TwitchatPayloadPreset preset = requireOwnedPreset(user, presetId);
        preset.setName(name);
        preset.setPayloadJson(payloadJson);
        preset.setUpdatedAt(Instant.now());
        return presetRepository.save(preset);
    }

    @Transactional
    public void deletePreset(UserAccount user, UUID presetId) {
        TwitchatPayloadPreset preset = requireOwnedPreset(user, presetId);
        activePresetRepository.deleteByPreset(preset);
        presetRepository.delete(preset);
    }

    @Transactional(readOnly = true)
    public Map<TwitchatNotificationEventType, UUID> getActivePresets(UserAccount user) {
        Map<TwitchatNotificationEventType, UUID> result = new EnumMap<>(TwitchatNotificationEventType.class);
        for (TwitchatActivePreset row : activePresetRepository.findByUserId(user.getId())) {
            result.put(row.getEventType(), row.getPreset().getId());
        }
        return result;
    }

    @Transactional
    public void setActivePreset(UserAccount user, TwitchatNotificationEventType eventType, UUID presetId) {
        if (presetId == null) {
            activePresetRepository.deleteById(new TwitchatActivePreset.Key(user.getId(), eventType));
            return;
        }
        TwitchatPayloadPreset preset = requireOwnedPreset(user, presetId);
        if (preset.getEventType() != eventType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preset event type mismatch");
        }
        TwitchatActivePreset active = activePresetRepository.findByUserIdAndEventType(user.getId(), eventType)
                .orElseGet(() -> {
                    TwitchatActivePreset a = new TwitchatActivePreset();
                    a.setUserId(user.getId());
                    a.setEventType(eventType);
                    return a;
                });
        active.setPreset(preset);
        activePresetRepository.save(active);
    }

    /**
     * Never throws: a corrupted or missing preset must never block the notification it's for.
     * Callers treat an empty result as "use TwitchatDefaultPayloads for this event type".
     */
    @Transactional(readOnly = true)
    public Optional<TwitchatPresetPayload> findActivePresetPayload(UserAccount user,
                                                                     TwitchatNotificationEventType eventType) {
        return activePresetRepository.findByUserIdAndEventType(user.getId(), eventType)
                .flatMap(active -> parsePayload(active.getPreset()));
    }

    private Optional<TwitchatPresetPayload> parsePayload(TwitchatPayloadPreset preset) {
        try {
            return Optional.of(jackson.readValue(preset.getPayloadJson(), TwitchatPresetPayload.class));
        } catch (Exception e) {
            log.warn("Twitchat preset {} has unparsable payload, falling back to default: {}",
                    preset.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses and validates a preset's JSON: it must parse, and `message` must be non-blank.
     * Throws ResponseStatusException(BAD_REQUEST) on failure. Public because TwitchatNotifier
     * also needs it, for the "Test preset" button — rendering an unsaved preset must fail the
     * same way saving it would, not silently produce a broken notification.
     */
    public TwitchatPresetPayload parseAndValidate(String payloadJson) {
        TwitchatPresetPayload parsed;
        try {
            parsed = jackson.readValue(payloadJson, TwitchatPresetPayload.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preset JSON: " + e.getMessage());
        }
        if (parsed.message() == null || parsed.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preset message must not be blank");
        }
        return parsed;
    }

    private TwitchatPayloadPreset requireOwnedPreset(UserAccount user, UUID presetId) {
        TwitchatPayloadPreset preset = presetRepository.findById(presetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!preset.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return preset;
    }
}
