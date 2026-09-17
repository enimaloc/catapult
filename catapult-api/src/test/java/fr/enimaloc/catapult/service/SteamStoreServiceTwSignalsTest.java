package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SteamStoreServiceTwSignalsTest {

    @Test
    void extractTwSignals_extractsIdsAndLowercasesNotes() {
        SteamStoreService.SteamStorePage page = pageWithContentDescriptors(
                new SteamStoreService.SteamStorePage.ContentDescriptors(
                        new int[] {2, 5}, "GORE and FLASHING lights present"));

        SteamStoreService.SteamTwSignals signals = SteamStoreServiceImpl.extractTwSignals(page);

        assertThat(signals.contentDescriptorIds()).containsExactlyInAnyOrder(2, 5);
        assertThat(signals.notesLowercase()).isEqualTo("gore and flashing lights present");
    }

    @Test
    void extractTwSignals_absentContentDescriptors_returnsEmptySignals() {
        SteamStoreService.SteamTwSignals signals = SteamStoreServiceImpl.extractTwSignals(pageWithContentDescriptors(null));

        assertThat(signals.contentDescriptorIds()).isEmpty();
        assertThat(signals.notesLowercase()).isEmpty();
    }

    /** Builds a SteamStorePage with every field defaulted except the one under test. */
    private static SteamStoreService.SteamStorePage pageWithContentDescriptors(
            SteamStoreService.SteamStorePage.ContentDescriptors contentDescriptors) {
        return new SteamStoreService.SteamStorePage(
                null, null, 0, 0, false, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                contentDescriptors, null);
    }
}
