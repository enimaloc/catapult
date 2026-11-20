package fr.enimaloc.catapult.repository.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:debugspecs;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DebugExchangeSpecsTest {

    @Autowired DebugExchangeRepository repository;

    private final UUID member = UUID.randomUUID();

    @BeforeEach
    void seed() {
        save("out-ok", Direction.OUT, 200, null);
        save("out-4xx", Direction.OUT, 404, null);
        save("out-no-answer", Direction.OUT, null, "Read timed out");
        save("out-no-status", Direction.OUT, null, null);
        save("in-message", Direction.IN, null, null);
        save("in-failed", Direction.IN, null, "invalid callback");
        save("in-with-status", Direction.IN, 500, null);
        save("obs-ok", "OBS", Direction.OUT, 100, null);
        save("obs-identify", "OBS", Direction.OUT, null, null);
        save("obs-failed", "OBS", Direction.OUT, 600, "No source was found.");
        save("twitchat-sent", "TWITCHAT", Direction.OUT, null, null);
        save("twitchat-timeout", "TWITCHAT", Direction.OUT, null, "timeout");
    }

    private void save(String operation, Direction direction, Integer status, String error) {
        save(operation, direction == Direction.IN ? "WS" : "GET", direction, status, error);
    }

    private void save(String operation, String method, Direction direction, Integer status, String error) {
        DebugExchange e = new DebugExchange();
        e.setUserId(member);
        e.setOccurredAt(Instant.now());
        e.setDirection(direction);
        e.setService("twitch");
        e.setOperation(operation);
        e.setMethod(method);
        e.setUrl("wss://eventsub.wss.twitch.tv/ws");
        e.setStatus(status);
        e.setError(error);
        repository.save(e);
    }

    private java.util.List<String> operations(Boolean outcome) {
        return repository.findAll(DebugExchangeSpecs.matching(member, null, null, outcome, null, null)).stream()
                .map(DebugExchange::getOperation).toList();
    }

    @Test
    void anIncomingMessageWithoutStatus_isASuccess_unlessItHasAnError() {
        assertThat(operations(true)).containsExactlyInAnyOrder("out-4xx", "out-no-answer", "out-no-status", "in-failed",
                "in-with-status", "obs-failed", "twitchat-timeout");
        assertThat(operations(false)).containsExactlyInAnyOrder("out-ok", "in-message", "obs-ok", "obs-identify",
                "twitchat-sent");
        assertThat(operations(null)).hasSize(12);
    }

    @Test
    void theSqlFilter_agreesWithIsError() {
        for (DebugExchange e : repository.findAll()) {
            assertThat(operations(e.isError())).as(e.getOperation()).contains(e.getOperation());
        }
    }
}
