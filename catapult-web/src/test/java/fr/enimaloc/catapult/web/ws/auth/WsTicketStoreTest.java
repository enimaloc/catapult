package fr.enimaloc.catapult.web.ws.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import fr.enimaloc.catapult.web.ws.auth.WsTicketStore.AuthSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class WsTicketStoreTest {

    @Test
    void issue_then_consume_returns_snapshot() {
        WsTicketStore store = new WsTicketStore();
        UUID userId = UUID.randomUUID();

        String ticket = store.issue(userId, Set.of("ROLE_USER"));

        var snap = store.consume(ticket);
        assertThat(snap).isPresent();
        assertThat(snap.get().userId()).isEqualTo(userId);
        assertThat(snap.get().roles()).containsExactly("ROLE_USER");
    }

    @Test
    void second_consume_returns_empty() {
        WsTicketStore store = new WsTicketStore();
        String ticket = store.issue(UUID.randomUUID(), Set.of());

        assertThat(store.consume(ticket)).isPresent();
        assertThat(store.consume(ticket)).isEmpty();
    }

    @Test
    void blank_or_null_token_returns_empty() {
        WsTicketStore store = new WsTicketStore();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
    }

    @Test
    void issued_tickets_are_unique_and_url_safe() {
        WsTicketStore store = new WsTicketStore();
        String a = store.issue(UUID.randomUUID(), Set.of());
        String b = store.issue(UUID.randomUUID(), Set.of());
        assertThat(a).isNotEqualTo(b);
        // base64-url: A-Z a-z 0-9 - _ (no padding)
        assertThat(a).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    void ticket_expires_after_ttl() {
        AtomicLong nanos = new AtomicLong(0);
        Ticker ticker = nanos::get;
        Cache<String, AuthSnapshot> cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(WsTicketStore.TTL)
                .maximumSize(WsTicketStore.MAX_ENTRIES)
                .build();
        WsTicketStore store = new WsTicketStore(cache);

        String ticket = store.issue(UUID.randomUUID(), Set.of());

        // Advance just past TTL.
        nanos.set(WsTicketStore.TTL.plus(Duration.ofSeconds(1)).toNanos());
        cache.cleanUp();

        assertThat(store.consume(ticket)).isEmpty();
    }
}
