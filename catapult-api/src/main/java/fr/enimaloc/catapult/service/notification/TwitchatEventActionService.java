package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatEventAction;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadMode;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatEventActionRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The payloads each Catapult event sends to Twitchat, per account. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchatEventActionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Read raw: the legacy preset entities are gone, their tables only await a later drop.
    private static final String ACTIVE_PRESETS = """
            SELECT ap.event_type, p.payload_json
            FROM twitchat_active_preset ap JOIN twitchat_payload_preset p ON p.id = ap.preset_id
            WHERE ap.user_id = ?""";

    private final TwitchatEventActionRepository repository;
    private final TwitchatWidgetSettingsRepository widgetSettingsRepository;
    private final JdbcTemplate jdbc;
    private final TwitchatPayloadValidator validator;

    /** Every event, in declaration order, with its payloads (possibly none). */
    public Map<TwitchatNotificationEventType, List<TwitchatEventAction>> all(UserAccount user) {
        Map<TwitchatNotificationEventType, List<TwitchatEventAction>> byEvent = new EnumMap<>(TwitchatNotificationEventType.class);
        for (TwitchatNotificationEventType type : TwitchatNotificationEventType.values()) {
            byEvent.put(type, new ArrayList<>());
        }
        repository.findByUserOrderByPositionAsc(user).forEach(action -> byEvent.get(action.getEventType()).add(action));
        return byEvent;
    }

    public List<TwitchatEventAction> enabledActions(UserAccount user, TwitchatNotificationEventType type) {
        return repository.findByUserAndEventTypeAndEnabledTrueOrderByPositionAsc(user, type);
    }

    public void validate(List<TwitchatEventActionDto> payloads) {
        List<TwitchatValidationError> errors = validator.validate(payloads);
        if (!errors.isEmpty()) {
            throw new TwitchatValidationException(errors);
        }
    }

    /** Replaces an event's whole list, in the given order; nothing changes when one payload is invalid. */
    @Transactional
    public void replace(UserAccount user, TwitchatNotificationEventType type, List<TwitchatEventActionDto> payloads) {
        validate(payloads);
        repository.deleteAllOfEvent(user, type);
        Instant now = Instant.now();
        for (int i = 0; i < payloads.size(); i++) {
            TwitchatEventActionDto payload = payloads.get(i);
            repository.save(newAction(user, type, i, payload.action(), TwitchatPayloadMode.valueOf(payload.mode()),
                    payload.dataJson(), payload.enabled(), now));
        }
    }

    public Map<String, Object> parseData(String dataJson) {
        return validator.parse(dataJson);
    }

    /**
     * Gives the account its starting payloads, once: each formerly notifying event gets its
     * active legacy preset converted, else its default notification. Skipped when the account
     * already has payloads (it's then only marked done).
     */
    @Transactional
    public void ensureInitialized(UserAccount user) {
        TwitchatWidgetSettings settings = widgetSettingsRepository.findById(user.getId()).orElse(null);
        if (settings == null || settings.isEventActionsInitialized()) {
            return;
        }
        if (!repository.existsByUser(user)) {
            Map<TwitchatNotificationEventType, String> presets = activePresets(user.getId());
            Instant now = Instant.now();
            for (TwitchatNotificationEventType type : TwitchatEventDefaults.SEEDED) {
                Map<String, Object> data = presets.containsKey(type)
                        ? TwitchatEventDefaults.fromLegacyPreset(type, legacy(presets.get(type)))
                        : TwitchatEventDefaults.chatMessage(type);
                repository.save(newAction(user, type, 0, TwitchatPayloadRenderer.CHAT_MESSAGE, TwitchatPayloadMode.FORM,
                        JSON.writeValueAsString(data), true, now));
            }
        }
        settings.setEventActionsInitialized(true);
        widgetSettingsRepository.save(settings);
    }

    private Map<TwitchatNotificationEventType, String> activePresets(UUID userId) {
        Map<TwitchatNotificationEventType, String> presets = new EnumMap<>(TwitchatNotificationEventType.class);
        try {
            jdbc.query(ACTIVE_PRESETS, rs -> {
                presets.put(TwitchatNotificationEventType.valueOf(rs.getString("event_type")), rs.getString("payload_json"));
            }, userId);
        } catch (DataAccessException e) {
            log.debug("No legacy Twitchat presets to convert for {}: {}", userId, e.getMessage());
        }
        return presets;
    }

    private Map<String, Object> legacy(String payloadJson) {
        try {
            return validator.parse(payloadJson);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static TwitchatEventAction newAction(UserAccount user, TwitchatNotificationEventType type, int position,
                                                 String action, TwitchatPayloadMode mode, String dataJson,
                                                 boolean enabled, Instant now) {
        TwitchatEventAction entity = new TwitchatEventAction();
        entity.setId(UUID.randomUUID());
        entity.setUser(user);
        entity.setEventType(type);
        entity.setPosition(position);
        entity.setAction(action);
        entity.setMode(mode);
        entity.setDataJson(dataJson);
        entity.setEnabled(enabled);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
