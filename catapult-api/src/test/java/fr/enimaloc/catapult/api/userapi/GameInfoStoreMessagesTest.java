package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** GameInfoAssembler names the store of every source type: each one needs a label, in both languages. */
class GameInfoStoreMessagesTest {

    @Test
    void everySourceType_hasAStoreLabel_inBothLanguages() throws IOException {
        for (String name : new String[]{"messages.properties", "messages_fr.properties"}) {
            Properties bundle = new Properties();
            try (InputStream in = GameInfoStoreMessagesTest.class.getResourceAsStream("/lang/" + name)) {
                assertThat(in).as(name).isNotNull();
                bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (GameBinding.SourceType type : GameBinding.SourceType.values()) {
                assertThat(bundle.getProperty("game_info.store." + type.name())).as(name + " " + type).isNotBlank();
            }
        }
    }
}
