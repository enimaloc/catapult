package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.getter.DtddApiClient.DtddTopics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DtddSignalServiceTest {

    @Test
    void returnsUnionOfYesAndMostly_excludesNo() {
        DtddService dtddService = mock(DtddService.class);
        when(dtddService.getTopics("42", "Doki Doki")).thenReturn(Optional.of(new DtddTopics(
            List.of("A character dies", "Blood"),                   // yes
            List.of("A character commits suicide"),                  // no  (excluded)
            List.of("Gore", "Blood")                                 // mostly  (Blood dedup)
        )));
        DtddSignalService svc = new DtddSignalService(dtddService);
        assertThat(svc.getYesMostlyTopics("42", "Doki Doki"))
            .containsExactlyInAnyOrder("A character dies", "Blood", "Gore");
    }

    @Test
    void emptyWhenDtddReturnsEmpty() {
        DtddService dtddService = mock(DtddService.class);
        when(dtddService.getTopics("99", "X")).thenReturn(Optional.empty());
        DtddSignalService svc = new DtddSignalService(dtddService);
        assertThat(svc.getYesMostlyTopics("99", "X")).isEmpty();
    }
}
