package fr.enimaloc.catapult.monitoring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/** The Sentry defaults shipped in the properties and the compose file, read as text. */
class SentryDefaultsTest {

    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path COMPOSE = Path.of("../docker-compose.yml");

    private static final Pattern TRACES_DEFAULT_PROPERTIES = Pattern.compile("\\$\\{SENTRY_TRACES_SAMPLE_RATE:([0-9.]+)}");
    private static final Pattern TRACES_DEFAULT_COMPOSE = Pattern.compile("\\$\\{SENTRY_TRACES_SAMPLE_RATE:-([0-9.]+)}");

    @Test
    void tracesDefaultIsAtMostTenPercent() throws IOException {
        assertThat(defaultOf(PROPERTIES, TRACES_DEFAULT_PROPERTIES)).isLessThanOrEqualTo(0.1);
    }

    @Test
    void composeTracesDefaultIsAtMostTenPercent() throws IOException {
        assertThat(defaultOf(COMPOSE, TRACES_DEFAULT_COMPOSE)).isLessThanOrEqualTo(0.1);
    }

    @Test
    void sessionReplayStaysOff() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(PROPERTIES)) {
            properties.load(reader);
        }
        String replays = properties.getProperty("catapult.sentry.replays-session-sample-rate");
        if (replays == null) {
            fail("catapult.sentry.replays-session-sample-rate not found in " + PROPERTIES);
        }
        assertThat(replays).isEqualTo("0");
    }

    private static double defaultOf(Path file, Pattern pattern) throws IOException {
        Matcher matcher = pattern.matcher(Files.readString(file));
        if (!matcher.find()) {
            fail("SENTRY_TRACES_SAMPLE_RATE default not found in " + file);
        }
        return Double.parseDouble(matcher.group(1));
    }
}
