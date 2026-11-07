package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.account.UserRefDto;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A channel's account id by its Twitch username: catapult-web's pages name a channel by its username, catapult-api's
 * channel routes by its account id. Asked with the session's JWT: a missing or invalid token is a 401 and an unknown
 * channel a 404 (a token whose account was deleted is not a 401: the lookup only needs a member or the loopback,
 * so it answers as for any caller). The cache is safe across users only because the lookup is not filtered by
 * access (every member gets the same answer); if it ever filters, the cache must be keyed by caller. A found id is kept
 * {@link #TTL}, by the username as given (the match is exact); nothing else is kept, so an account deleted then
 * re-created under the same name is not addressed by its old id for long, and a refusal is asked again.
 */
@Component
public class ChannelIds {

    static final Duration TTL = Duration.ofSeconds(60);

    private final ApiClient client;
    private final Clock clock;
    private final Map<String, Entry> found = new ConcurrentHashMap<>();

    @Autowired
    public ChannelIds(ApiClient client) {
        this(client, Clock.systemUTC());
    }

    ChannelIds(ApiClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** {@code username}'s account id, or the status catapult-api refused the lookup with. */
    public Lookup resolve(String username) {
        Instant now = clock.instant();
        Entry entry = found.get(username);
        if (entry != null && now.isBefore(entry.expires())) {
            return new Lookup(HttpStatus.OK.value(), entry.id());
        }
        ApiClient.Result<UserRefDto> answer = client.call(HttpMethod.GET, "/api/internal/users?twitchUsername={username}",
                null, UserRefDto.class, username);
        if (!answer.ok()) {
            found.remove(username);
            return new Lookup(answer.status(), null);
        }
        if (answer.body() == null || answer.body().id() == null) {
            found.remove(username);
            return new Lookup(HttpStatus.BAD_GATEWAY.value(), null);
        }
        UUID id = answer.body().id();
        found.put(username, new Entry(id, now.plus(TTL)));
        return new Lookup(HttpStatus.OK.value(), id);
    }

    /** Drops {@code username}'s id: a channel route no longer knew it. */
    public void forget(String username) {
        found.remove(username);
    }

    /** A lookup's answer: the channel's id when {@link #found()}, else the status catapult-api refused it with. */
    public record Lookup(int status, UUID id) {
        public boolean found() {
            return status == HttpStatus.OK.value() && id != null;
        }
    }

    private record Entry(UUID id, Instant expires) {}
}
