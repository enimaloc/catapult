package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.repository.TwitchatActionTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatActionTokenServiceTest {

    @Mock private TwitchatActionTokenRepository repository;
    private TwitchatActionTokenService service;

    @BeforeEach
    void setup() {
        service = new TwitchatActionTokenService(repository, JsonMapper.builder().build());
    }

    @Test
    void generate_savesTokenWithPayloadAndFutureExpiry() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<TwitchatActionToken> captor = ArgumentCaptor.forClass(TwitchatActionToken.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UUID token = service.generate(userId, TwitchatActionType.DISABLE_BOT, Map.of("k", "v"));

        TwitchatActionToken saved = captor.getValue();
        assertThat(saved.getToken()).isEqualTo(token);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getActionType()).isEqualTo(TwitchatActionType.DISABLE_BOT);
        assertThat(saved.getPayloadJson()).contains("\"k\"").contains("\"v\"");
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
        assertThat(saved.getConsumedAt()).isNull();
    }

    @Test
    void consume_notConsumedYet_marksConsumedAndReturnsToken() {
        UUID token = UUID.randomUUID();
        TwitchatActionToken entity = new TwitchatActionToken();
        entity.setToken(token);
        when(repository.consume(eq(token), any(Instant.class))).thenReturn(1);
        when(repository.findById(token)).thenReturn(Optional.of(entity));

        Optional<TwitchatActionToken> result = service.consume(token);

        assertThat(result).contains(entity);
    }

    @Test
    void consume_alreadyConsumedOrExpired_returnsEmpty() {
        UUID token = UUID.randomUUID();
        when(repository.consume(eq(token), any(Instant.class))).thenReturn(0);

        Optional<TwitchatActionToken> result = service.consume(token);

        assertThat(result).isEmpty();
        verify(repository, never()).findById(any());
    }

    @Test
    void readPayload_roundTripsMap() {
        String json = service.writePayloadForTest(Map.of("a", "1", "b", "2"));

        Map<String, String> payload = service.readPayload(json);

        assertThat(payload).containsEntry("a", "1").containsEntry("b", "2");
    }
}
