package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.experiment.ApiDefaultVersionExperiment;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The API version of a request that names none, from the {@value ApiDefaultVersionExperiment#KEY} experiment:
 * {@link ApiV2#VERSION_3} for a member in its {@code v3} variant, {@link ApiV2#VERSION} otherwise. A request without
 * an account (the public {@code /api/games} routes) follows the variant that carries the most weight.
 * {@link ApiVersioningConfig} tries it after the header and the query parameter, so an explicit version always wins.
 *
 * <p>The member is the {@link UUID} principal the personal API token filter puts in the security context, which the
 * filter chain has filled by the time the handler mapping resolves the version. Each answer is remembered
 * {@link #TTL} per member, and once for the requests without an account, so the experiment is not read on every
 * request. A failure to read it answers {@code null} (the configured default) and is not remembered.
 *
 * <p>Deliberately not an {@link org.springframework.web.accept.ApiVersionResolver} bean: Spring Boot registers every
 * such bean ahead of the resolvers {@link ApiVersioningConfig} declares, which would let the experiment override an
 * explicit header. {@link ApiVersioningConfig} adds {@link #resolveVersion} last instead.
 */
@Slf4j
@Component
public class ExperimentDefaultVersionResolver {

    static final Duration TTL = Duration.ofSeconds(60);

    // Beyond this many remembered members, the expired answers are dropped on the next write
    private static final int PURGE_THRESHOLD = 1_000;

    private record Remembered(String version, Instant expiresAt) {}

    private final ExperimentService experimentService;
    private final UserAccountRepository userAccountRepository;
    private final Clock clock;
    private final Map<UUID, Remembered> byMember = new ConcurrentHashMap<>();
    private volatile @Nullable Remembered anonymous;

    @Autowired
    public ExperimentDefaultVersionResolver(ExperimentService experimentService,
            UserAccountRepository userAccountRepository) {
        this(experimentService, userAccountRepository, Clock.systemUTC());
    }

    ExperimentDefaultVersionResolver(ExperimentService experimentService, UserAccountRepository userAccountRepository,
            Clock clock) {
        this.experimentService = experimentService;
        this.userAccountRepository = userAccountRepository;
        this.clock = clock;
    }

    /** The default version of {@code request}, or {@code null} for the configured default. */
    public @Nullable String resolveVersion(HttpServletRequest request) {
        try {
            UUID memberId = memberId();
            return memberId == null ? anonymousVersion() : memberVersion(memberId);
        } catch (RuntimeException e) {
            log.warn("Could not read the default API version experiment, serving the configured default", e);
            return null;
        }
    }

    /** Forgets every remembered answer. */
    void clear() {
        byMember.clear();
        anonymous = null;
    }

    private static @Nullable UUID memberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UUID id ? id : null;
    }

    private String memberVersion(UUID memberId) {
        Instant now = clock.instant();
        Remembered remembered = byMember.get(memberId);
        if (remembered != null && now.isBefore(remembered.expiresAt())) {
            return remembered.version();
        }
        Optional<UserAccount> member = userAccountRepository.findById(memberId);
        if (member.isEmpty()) {
            // An account gone since its token was checked: the controller answers 401 whatever the version
            return anonymousVersion();
        }
        String version = versionOf(experimentService.getVariantKey(member.get(), ApiDefaultVersionExperiment.KEY));
        if (byMember.size() >= PURGE_THRESHOLD) {
            byMember.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
        }
        byMember.put(memberId, new Remembered(version, now.plus(TTL)));
        return version;
    }

    private String anonymousVersion() {
        Instant now = clock.instant();
        Remembered remembered = anonymous;
        if (remembered != null && now.isBefore(remembered.expiresAt())) {
            return remembered.version();
        }
        String version = versionOf(experimentService.getHeaviestVariantKey(ApiDefaultVersionExperiment.KEY));
        anonymous = new Remembered(version, now.plus(TTL));
        return version;
    }

    private static String versionOf(Optional<String> variant) {
        return variant.filter(ApiDefaultVersionExperiment.V3_VARIANT::equals).isPresent()
                ? ApiV2.VERSION_3 : ApiV2.VERSION;
    }
}
