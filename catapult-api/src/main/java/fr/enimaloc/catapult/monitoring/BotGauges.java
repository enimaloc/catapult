package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaSessionManager;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * The Battle.net and Ankama bot pools, by {@code platform}: enabled bots by login state ({@code catapult.bots.accounts}),
 * sessions ready ({@code catapult.bots.sessions.ready}, 0 when the platform is off) and streamer links by status
 * ({@code catapult.bots.links}). Read on each scrape: a few count queries. Strong references: the count lambdas have
 * nothing else holding them, and a gauge only keeps its object weakly by default (it would read NaN once collected).
 */
@Component
@RequiredArgsConstructor
public class BotGauges implements MeterBinder {

    private static final String BATTLENET = "battlenet";
    private static final String ANKAMA = "ankama";

    private final BattleNetServiceAccountRepository battleNetAccounts;
    private final BattleNetFriendLinkRepository battleNetLinks;
    private final AnkamaServiceAccountRepository ankamaAccounts;
    private final AnkamaFriendLinkRepository ankamaLinks;
    private final ObjectProvider<BattleNetSessionManager> battleNetSessions;
    private final ObjectProvider<AnkamaSessionManager> ankamaSessions;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (BattleNetServiceAccount.AuthState state : BattleNetServiceAccount.AuthState.values()) {
            accounts(registry, BATTLENET, state.name(),
                    () -> battleNetAccounts.countByEnabledTrueAndAuthState(state));
        }
        for (AnkamaServiceAccount.AuthState state : AnkamaServiceAccount.AuthState.values()) {
            accounts(registry, ANKAMA, state.name(), () -> ankamaAccounts.countByEnabledTrueAndAuthState(state));
        }
        for (BattleNetFriendLink.Status status : BattleNetFriendLink.Status.values()) {
            links(registry, BATTLENET, status.name(), () -> battleNetLinks.countByStatus(status));
        }
        for (AnkamaFriendLink.Status status : AnkamaFriendLink.Status.values()) {
            links(registry, ANKAMA, status.name(), () -> ankamaLinks.countByStatus(status));
        }
        Gauge.builder("catapult.bots.sessions.ready", battleNetSessions,
                        p -> { BattleNetSessionManager m = p.getIfAvailable(); return m == null ? 0 : m.readySessionCount(); })
                .tag("platform", BATTLENET)
                .description("Bot sessions logged in right now")
                .strongReference(true)
                .register(registry);
        Gauge.builder("catapult.bots.sessions.ready", ankamaSessions,
                        p -> { AnkamaSessionManager m = p.getIfAvailable(); return m == null ? 0 : m.readySessionCount(); })
                .tag("platform", ANKAMA)
                .description("Bot sessions logged in right now")
                .strongReference(true)
                .register(registry);
    }

    private static void accounts(MeterRegistry registry, String platform, String state, LongSupplier count) {
        Gauge.builder("catapult.bots.accounts", count, LongSupplier::getAsLong)
                .tag("platform", platform)
                .tag("auth_state", state)
                .description("Enabled bot accounts by login state")
                .strongReference(true)
                .register(registry);
    }

    private static void links(MeterRegistry registry, String platform, String status, LongSupplier count) {
        Gauge.builder("catapult.bots.links", count, LongSupplier::getAsLong)
                .tag("platform", platform)
                .tag("status", status)
                .description("Streamer links to the bots by status")
                .strongReference(true)
                .register(registry);
    }
}
