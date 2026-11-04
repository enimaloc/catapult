package fr.enimaloc.catapult.service.premium;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Whether an account has the premium features, and who may grant or revoke them. The end date is
 * a day in {@link #ZONE}, valid until the end of that day; no end date means premium for good.
 */
@Service
public class PremiumService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final UserAccountRepository users;
    private final ActivityLogService activityLog;
    private final int freeCustomBindingLimit;
    private final Clock clock;

    @Autowired
    public PremiumService(UserAccountRepository users, ActivityLogService activityLog,
                          @Value("${catapult.premium.free-custom-binding-limit:5}") int freeCustomBindingLimit) {
        this(users, activityLog, freeCustomBindingLimit, Clock.systemUTC());
    }

    PremiumService(UserAccountRepository users, ActivityLogService activityLog, int freeCustomBindingLimit,
                   Clock clock) {
        this.users = users;
        this.activityLog = activityLog;
        this.freeCustomBindingLimit = freeCustomBindingLimit;
        this.clock = clock;
    }

    public boolean isPremium(UserAccount user) {
        if (!user.isPremium()) {
            return false;
        }
        LocalDate until = user.getPremiumUntil();
        return until == null || !LocalDate.now(clock.withZone(ZONE)).isAfter(until);
    }

    /** The most custom bindings the account may have; empty means unlimited. */
    public OptionalInt customBindingLimit(UserAccount user) {
        return isPremium(user) ? OptionalInt.empty() : OptionalInt.of(freeCustomBindingLimit);
    }

    @Transactional
    public void grant(UUID userId, LocalDate until, String actor) {
        UserAccount user = existing(userId);
        user.setPremium(true);
        user.setPremiumUntil(until);
        users.save(user);
        activityLog.addEntry(userId, "INFO", "Premium granted by " + actor
                + (until == null ? " with no end date" : " until " + until));
    }

    @Transactional
    public void revoke(UUID userId, String actor) {
        UserAccount user = existing(userId);
        user.setPremium(false);
        user.setPremiumUntil(null);
        users.save(user);
        activityLog.addEntry(userId, "INFO", "Premium revoked by " + actor);
    }

    private UserAccount existing(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiProblem(ErrorCode.NOT_FOUND, "No account " + userId));
    }
}
