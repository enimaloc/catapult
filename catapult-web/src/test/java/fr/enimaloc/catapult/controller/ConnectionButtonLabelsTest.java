package fr.enimaloc.catapult.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The account cards of the connections tab name their buttons alike: the card's title already says which account. */
class ConnectionButtonLabelsTest {

    private static final String PREFIX = "channel.connections.";
    private static final List<String> CONNECT_KEYS = List.of("steam.connect", "xbox.connect", "minecraft.enroll",
            "battlenet.connect", "ankama.connect");
    private static final List<String> DISCONNECT_KEYS = List.of("steam.disconnect", "xbox.disconnect",
            "minecraft.disconnect", "battlenet.disconnect", "ankama.disconnect");

    @ParameterizedTest
    @ValueSource(strings = {"messages.properties", "messages_en.properties", "messages_fr.properties"})
    void accountCards_shareTheirConnectAndDisconnectLabels(String bundle) throws IOException {
        Properties messages = load(bundle);

        assertThat(CONNECT_KEYS.stream().map(key -> messages.getProperty(PREFIX + key)).distinct().toList())
                .as("connect labels in " + bundle).hasSize(1).doesNotContainNull();
        assertThat(DISCONNECT_KEYS.stream().map(key -> messages.getProperty(PREFIX + key)).distinct().toList())
                .as("disconnect labels in " + bundle).hasSize(1).doesNotContainNull();
    }

    private static Properties load(String bundle) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ConnectionButtonLabelsTest.class.getResourceAsStream("/lang/" + bundle)) {
            assertThat(in).as(bundle).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
