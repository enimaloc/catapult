package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.IrcTwitchChatService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.SystemTwitchAccountService;
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
    private final ChatCommandDefinitionRepository chatCommandDefinitionRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final ObjectProvider<IgdbService> igdbService;
    private final SystemTwitchAccountService systemTwitchAccountService;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("catapult.users.active", userAccountRepository,
                repo -> repo.countByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .description("Nombre d'utilisateurs avec le bot activé et statut ACTIVE")
            .register(registry);

        Gauge.builder("catapult.streams.live", streamStateService,
                StreamStateService::countLive)
            .description("Nombre de streams actuellement live en mémoire")
            .register(registry);

        Gauge.builder("catapult.chat.connections", eventSubChat,
                p -> { EventSubTwitchChatService s = p.getIfAvailable(); return s == null ? 0 : s.connectionCount(); })
            .tag("transport", "eventsub")
            .description("Connexions chat EventSub ouvertes")
            .register(registry);

        Gauge.builder("catapult.chat.connections", ircChat,
                p -> { IrcTwitchChatService s = p.getIfAvailable(); return s == null ? 0 : s.connectionCount(); })
            .tag("transport", "irc")
            .description("Connexions chat IRC ouvertes")
            .register(registry);

        Gauge.builder("catapult.commands.registered", chatCommandDefinitionRepository,
                repo -> repo.count())
            .description("Commandes chat dynamiques enregistrées")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByExpiresAtAfter(Instant.now()))
            .tag("state", "valid")
            .description("Tokens OAuth utilisateurs non expirés")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByExpiresAtBefore(Instant.now()))
            .tag("state", "expired")
            .description("Tokens OAuth utilisateurs expirés (en attente de refresh)")
            .register(registry);

        Gauge.builder("catapult.tokens.oauth", oAuthTokenRepository,
                repo -> repo.countByRefreshTokenIsNull())
            .tag("state", "no_refresh")
            .description("Tokens OAuth utilisateurs sans refresh token (irrécupérables)")
            .register(registry);

        Gauge.builder("catapult.token.expiry.seconds", igdbService,
                p -> { IgdbService s = p.getIfAvailable(); return s == null ? Double.NaN : secondsUntil(s.getTokenExpiresAt()); })
            .tag("service", "igdb")
            .description("Secondes avant expiration du token app IGDB (négatif = expiré)")
            .register(registry);

        Gauge.builder("catapult.token.expiry.seconds", systemTwitchAccountService,
                s -> s.tokenExpiry().map(CatapultGauges::secondsUntil).orElse(Double.NaN))
            .tag("service", "twitch_bot")
            .description("Secondes avant expiration du token du bot système (NaN = bot non lié)")
            .register(registry);
    }

    private static double secondsUntil(Instant instant) {
        return Duration.between(Instant.now(), instant).toSeconds();
    }
}
