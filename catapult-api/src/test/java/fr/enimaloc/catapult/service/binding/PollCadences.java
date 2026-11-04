package fr.enimaloc.catapult.service.binding;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test doubles for {@link PollCadence}. */
public final class PollCadences {

    private PollCadences() {}

    /** A cadence for which every account is due on every cycle. */
    public static PollCadence always() {
        PollCadence cadence = mock(PollCadence.class);
        when(cadence.isDue(any(), any())).thenReturn(true);
        when(cadence.tickSeconds()).thenReturn(1L);
        return cadence;
    }
}
