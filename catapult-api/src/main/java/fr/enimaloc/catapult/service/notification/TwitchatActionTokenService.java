package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.repository.TwitchatActionTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TwitchatActionTokenService {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final TwitchatActionTokenRepository repository;
    private final ObjectMapper jackson;

    @Transactional
    public UUID generate(UUID userId, TwitchatActionType type, Map<String, String> payload) {
        TwitchatActionToken token = new TwitchatActionToken();
        token.setToken(UUID.randomUUID());
        token.setUserId(userId);
        token.setActionType(type);
        token.setPayloadJson(writePayload(payload));
        token.setExpiresAt(Instant.now().plus(TTL));
        repository.save(token);
        return token.getToken();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TwitchatActionToken> consume(UUID token) {
        if (repository.consume(token) == 0) {
            return Optional.empty();
        }
        return repository.findById(token);
    }

    public Map<String, String> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return jackson.readValue(json, Map.class);
    }

    private String writePayload(Map<String, String> payload) {
        return jackson.writeValueAsString(payload == null ? Map.of() : payload);
    }

    // package-visible seam used only by the test above to build a round-trippable payload
    String writePayloadForTest(Map<String, String> payload) {
        return writePayload(payload);
    }
}
