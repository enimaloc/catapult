package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class CatapultGauges implements MeterBinder {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final ObjectProvider<EventSubTwitchChatService> eventSubChat;
    private final ObjectProvider<IrcTwitchChatService> ircChat;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final IgdbService igdbService;
    private final SystemTwitchAccountService systemTwitchAccountService;
    private final MinecraftServiceAccountRepository minecraftServiceAccountRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("catapult.users.active", userAccountRepository,
                repo -> repo.countByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .description("Number of users with the bot enabled and ACTIVE status")
            .register(registry);

        Gauge.builder("catapult.streams.live", streamStateService,
                StreamStateService::countLive)
            .description("Number of streams currently live in memory")
            .register(registry);

        Gauge.builder("catapult.chat.connections", eventSubChat,
                p -> { EventSubTwitchChatService s = p.getIfAvailable(); return s == null ? 0 : s.connectionCount(); })
            .tag("transport", "eventsub")
            .description("Open EventSub chat connections")
            .register(registry);

        Gauge.builder("catapult.chat.connections", ircChat,
                p -> { IrcTwitchChatService s = p.getIfAvailable(); return s == null ? 0 : s.connectionCount(); })
            .tag("transport", "irc")
            .description("Open IRC chat connections")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByExpiresAtAfter(Instant.now()))
            .tag("state", "valid")
            .description("Unexpired user OAuth tokens")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByExpiresAtBefore(Instant.now()))
            .tag("state", "expired")
            .description("Expired user OAuth tokens (awaiting refresh)")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByRefreshTokenIsNull())
            .tag("state", "no_refresh")
            .description("User OAuth tokens without a refresh token (unrecoverable)")
            .register(registry);

        Gauge.builder("catapult.token.expiry.seconds", igdbService,
                s -> secondsUntil(s.getTokenExpiresAt()))
            .tag("service", "igdb")
            .description("Seconds before the IGDB app token expires (negative = expired)")
            .register(registry);

        Gauge.builder("catapult.token.expiry.seconds", systemTwitchAccountService,
                s -> s.tokenExpiry().map(CatapultGauges::secondsUntil).orElse(Double.NaN))
            .tag("service", "twitch_bot")
            .description("Seconds before the system bot token expires (NaN = bot not linked)")
            .register(registry);

        Gauge.builder("catapult.minecraft.accounts.total", minecraftServiceAccountRepository,
                MinecraftServiceAccountRepository::countByEnabledTrue)
            .description("Number of enabled Minecraft service accounts")
            .register(registry);
    }

    private static double secondsUntil(Instant instant) {
        return Duration.between(Instant.now(), instant).toSeconds();
    }
}
