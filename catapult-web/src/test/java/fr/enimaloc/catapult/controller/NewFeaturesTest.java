package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NewFeaturesTest {

    private static final Map<String, LocalDate> RELEASES = Map.of(
            "channel.commands", LocalDate.of(2026, 10, 21),
            "admin.meta", LocalDate.of(2026, 9, 1));

    private final ApiService api = mock(ApiService.class);

    @Test
    void unseen_listsTheFeaturesShippedLessThanAMonthAgoTheMemberHasntOpened() {
        when(api.seenFeatures()).thenReturn(List.of());

        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2026, 10, 25))).containsExactly("channel.commands");
    }

    @Test
    void unseen_dropsAFeatureTheMemberHasOpened() {
        when(api.seenFeatures()).thenReturn(List.of("channel.commands"));

        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2026, 10, 25))).isEmpty();
    }

    @Test
    void unseen_dropsAFeatureAMonthAfterItShipped() {
        when(api.seenFeatures()).thenReturn(List.of());

        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2026, 11, 20))).containsExactly("channel.commands");
        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2026, 11, 21))).isEmpty();
    }

    @Test
    void unseen_withNothingNew_doesntAskCatapultApi() {
        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2027, 1, 1))).isEmpty();
        verifyNoInteractions(api);
    }

    @Test
    void unseen_withCatapultApiDown_showsNoDot() {
        when(api.seenFeatures()).thenReturn(null);

        assertThat(NewFeatures.unseen(api, RELEASES, LocalDate.of(2026, 10, 25))).isEmpty();
    }
}
