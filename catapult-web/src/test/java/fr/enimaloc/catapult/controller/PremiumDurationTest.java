package fr.enimaloc.catapult.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PremiumDurationTest {

    @ParameterizedTest
    @CsvSource({
            "30, premium.duration.seconds, 0, 30",
            "59, premium.duration.seconds, 0, 59",
            "60, premium.duration.minutes, 1, 0",
            "120, premium.duration.minutes, 2, 0",
            "90, premium.duration.minutes_seconds, 1, 30",
            "10, premium.duration.seconds, 0, 10",
    })
    void of_splitsASecondCountIntoItsMessageAndParts(int total, String key, int minutes, int seconds) {
        var duration = PremiumDuration.of(total);

        assertThat(duration.key()).isEqualTo(key);
        assertThat(duration.minutes()).isEqualTo(minutes);
        assertThat(duration.seconds()).isEqualTo(seconds);
    }
}
