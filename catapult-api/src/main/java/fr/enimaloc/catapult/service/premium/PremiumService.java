package fr.enimaloc.catapult.service.premium;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.premium.PremiumOfferDto;
import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.config.DegradedMode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.PollCadence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an account has the premium features, and who may grant or revoke them. The end date is
 * a day in {@link #ZONE}, valid until the end of that day; no end date means premium for good.
 */
@Slf4j
@Service
public class PremiumService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    /** Twitch "Special Events", the only category a free account may schedule (once). */
    public static final String SPECIAL_EVENTS_TWITCH_GAME_ID = "509663";

    private final UserAccountRepository users;
    private final ActivityLogService activityLog;
    private final CustomBindingRepository bindings;
    private final Environment env;
    private final int freeCustomBindingLimit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    // Unreadable intervals already logged, shared with the offer reads only
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    @Autowired
    public PremiumService(UserAccountRepository users, ActivityLogService activityLog,
                          CustomBindingRepository bindings, Environment env,
                          @Value("${catapult.premium.free-custom-binding-limit:5}") int freeCustomBindingLimit,
                          ApplicationEventPublisher publisher) {
        this(users, activityLog, bindings, env, freeCustomBindingLimit, publisher, Clock.systemUTC());
    }

    PremiumService(UserAccountRepository users, ActivityLogService activityLog, CustomBindingRepository bindings,
                   Environment env, int freeCustomBindingLimit,
                   ApplicationEventPublisher publisher, Clock clock) {
        this.users = users;
        this.activityLog = activityLog;
        this.bindings = bindings;
        this.env = env;
        this.freeCustomBindingLimit = freeCustomBindingLimit;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Today in {@link #ZONE}, the day an end date is compared with. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public boolean isPremium(UserAccount user) {
        if (!user.isPremium()) {
            return false;
        }
        LocalDate until = user.getPremiumUntil();
        return until == null || !today().isAfter(until);
    }

    /** Premium may schedule anything; a free account only one-off slots on {@link #SPECIAL_EVENTS_TWITCH_GAME_ID}. */
    public boolean canSchedule(UserAccount user, String twitchGameId, CustomBindingSchedule.Repeat repeat) {
        return isPremium(user)
                || (repeat == CustomBindingSchedule.Repeat.NONE && SPECIAL_EVENTS_TWITCH_GAME_ID.equals(twitchGameId));
    }

    public boolean canRepeatSchedules(UserAccount user) {
        return isPremium(user);
    }

    /** The most custom bindings the account may have; empty means unlimited. */
    public OptionalInt customBindingLimit(UserAccount user) {
        return isPremium(user) ? OptionalInt.empty() : OptionalInt.of(freeCustomBindingLimit);
    }

    /** The account's tier, its custom bindings against the limit (counted even while the feature is gated) and the offer. */
    public PremiumStatusDto status(UserAccount user) {
        OptionalInt limit = customBindingLimit(user);
        return new PremiumStatusDto(isPremium(user), user.getPremiumUntil(), (int) bindings.countByUser(user),
                limit.isPresent() ? limit.getAsInt() : null, offer());
    }

    /**
     * What each tier gets. The current mode's intervals are the effective ones (admin overrides
     * included), read with the scheduler's own rule; the other mode's come from its properties
     * file, as nothing overrides them. ({@link PollCadence} depends on this service, hence the
     * shared static parser rather than an injected instance.)
     */
    public PremiumOfferDto offer() {
        boolean degraded = env.matchesProfiles(DegradedMode.PROFILE);
        int free = effective(PollCadence.FREE_KEY);
        int premium = effective(PollCadence.PREMIUM_KEY);
        Properties other = fileProperties(degraded ? "application.properties" : "application-degraded.properties");
        int otherFree = fromFile(other, PollCadence.FREE_KEY);
        int otherPremium = fromFile(other, PollCadence.PREMIUM_KEY);
        return degraded
                ? new PremiumOfferDto(otherFree, otherPremium, free, premium, freeCustomBindingLimit)
                : new PremiumOfferDto(free, premium, otherFree, otherPremium, freeCustomBindingLimit);
    }

    private int effective(String key) {
        return (int) PollCadence.parseSeconds(key, env.getProperty(key), PollCadence.defaultSeconds(key), reported);
    }

    private int fromFile(Properties properties, String key) {
        return (int) PollCadence.parseSeconds(key, properties.getProperty(key), PollCadence.defaultSeconds(key), reported);
    }

    private static Properties fileProperties(String name) {
        try {
            return PropertiesLoaderUtils.loadProperties(new ClassPathResource(name));
        } catch (IOException e) {
            log.warn("Cannot read {} for the premium offer: {}", name, e.getMessage());
            return new Properties();
        }
    }

    @Transactional
    public void grant(UUID userId, LocalDate until, String actor) {
        UserAccount user = existing(userId);
        user.setPremium(true);
        user.setPremiumUntil(until);
        users.save(user);
        activityLog.addEntry(userId, "INFO", "Premium granted by " + actor
                + (until == null ? " with no end date" : " until " + until));
        publisher.publishEvent(new CustomBindingSchedulesChangedEvent(userId));
    }

    @Transactional
    public void revoke(UUID userId, String actor) {
        UserAccount user = existing(userId);
        user.setPremium(false);
        user.setPremiumUntil(null);
        users.save(user);
        activityLog.addEntry(userId, "INFO", "Premium revoked by " + actor);
        publisher.publishEvent(new CustomBindingSchedulesChangedEvent(userId));
    }

    private UserAccount existing(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, "No account " + userId));
    }
}
