package fr.enimaloc.catapult.admindata;

import fr.enimaloc.catapult.domain.ChatCommandFallback;
import fr.enimaloc.catapult.domain.TwDefinition;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_idcodec_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class IdCodecTest {

    @Autowired EntityManagerFactory emf;

    @Test
    void simpleId_roundTrips() {
        EntityType<TwDefinition> type = emf.getMetamodel().entity(TwDefinition.class);
        String encoded = IdCodec.encode("flashing-lights", type);
        assertThat(encoded).isEqualTo("flashing-lights");
        assertThat(IdCodec.decode(encoded, type)).isEqualTo("flashing-lights");
    }

    @Test
    void compositeId_roundTrips() {
        EntityType<ChatCommandFallback> type = emf.getMetamodel().entity(ChatCommandFallback.class);
        ChatCommandFallback.Pk pk = new ChatCommandFallback.Pk(UUID.randomUUID(), "user");
        String encoded = IdCodec.encode(pk, type);
        assertThat(encoded).contains("~");

        Object decoded = IdCodec.decode(encoded, type);
        assertThat(decoded).isInstanceOf(ChatCommandFallback.Pk.class);
        assertThat(decoded).isEqualTo(pk);
    }

    @Test
    void compositeId_encodesComponentsInAttributeNameOrder() {
        EntityType<ChatCommandFallback> type = emf.getMetamodel().entity(ChatCommandFallback.class);
        UUID commandId = UUID.randomUUID();
        ChatCommandFallback.Pk pk = new ChatCommandFallback.Pk(commandId, "user");
        // Pk fields are "command" then "placeholder" alphabetically -> command first.
        String encoded = IdCodec.encode(pk, type);
        assertThat(encoded).isEqualTo(commandId + "~user");
    }
}
