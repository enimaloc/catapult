package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.debug.SessionDebug.LinkedAccount;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A session debug recording with one member in debug mode, linked to a bot, and what it recorded for that member. */
public final class TestSessionDebug {

    public final UUID member = UUID.randomUUID();
    public final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    public final SessionDebug debug;
    private final String service;

    public TestSessionDebug(String service, UUID bot, String... keys) {
        this.service = service;
        DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
        when(registry.hasAny()).thenReturn(true);
        when(registry.members()).thenReturn(Set.of(member));
        when(registry.modeOf(member)).thenReturn(Optional.of(new DebugMode(member, false)));
        LinkedAccount link = new LinkedAccount(bot, Set.of(keys));
        debug = new SessionDebug(registry, recorder, service, URI.create("test://" + service),
                userId -> member.equals(userId) ? Optional.of(link) : Optional.empty());
    }

    /** The body (request side) of the one exchange recorded for the member in {@code direction} as {@code operation}. */
    public String recorded(Direction direction, String operation) {
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(member), eq(direction), raw.capture(), eq(operation), eq(false), eq(service));
        byte[] body = raw.getValue().requestBody();
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}
