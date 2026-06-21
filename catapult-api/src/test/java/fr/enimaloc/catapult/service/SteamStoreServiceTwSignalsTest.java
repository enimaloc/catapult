package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SteamStoreServiceTwSignalsTest {

    @Test
    void extractTwSignals_extractsIdsAndLowercasesNotes() {
        Map<String, Object> data = Map.of(
            "content_descriptors", Map.of(
                "ids",   List.of(2, 5),
                "notes", "GORE and FLASHING lights present"
            )
        );

        SteamStoreService.SteamTwSignals signals = SteamStoreServiceImpl.extractTwSignals(data);

        assertThat(signals.contentDescriptorIds()).containsExactlyInAnyOrder(2, 5);
        assertThat(signals.notesLowercase()).isEqualTo("gore and flashing lights present");
    }

    @Test
    void extractTwSignals_absentContentDescriptors_returnsEmptySignals() {
        SteamStoreService.SteamTwSignals signals = SteamStoreServiceImpl.extractTwSignals(Map.of());

        assertThat(signals.contentDescriptorIds()).isEmpty();
        assertThat(signals.notesLowercase()).isEmpty();
    }
}
