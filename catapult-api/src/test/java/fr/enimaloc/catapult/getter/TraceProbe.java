package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.service.debug.RawExchange;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs a getter under a detection trace, as the chain does, and reads the entry it leaves for its provider. */
public final class TraceProbe {

    private TraceProbe() {
    }

    /** The {@code none} entry the trace of {@code user} holds after {@code call} ran under it. */
    public static JsonNode entryAfter(UserAccount user, Supplier<?> call) {
        DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
        DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
        when(registry.modeOf(user.getId()))
            .thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(user.getId(), false)));
        try (DetectionTrace.Scope ignored = DetectionTrace.openIfDebugged(registry, recorder, user.getId())) {
            call.get();
            DetectionTrace.provider("PROBE", "none", null, null);
        }
        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(user.getId()), eq(Direction.OUT), raw.capture(), eq("detection.cycle"),
            eq(false), eq("detection"));
        return JsonMapper.builder().build()
            .readTree(new String(raw.getValue().requestBody(), StandardCharsets.UTF_8)).path("providers").get(0);
    }
}
