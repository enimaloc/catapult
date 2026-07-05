package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.IrcTwitchChatService;
import fr.enimaloc.catapult.service.StreamStateService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CatapultGauges implements MeterBinder {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final ObjectProvider<EventSubTwitchChatService> eventSubChat;
    private final ObjectProvider<IrcTwitchChatService> ircChat;
    private final ChatCommandDefinitionRepository chatCommandDefinitionRepository;

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
    }
}
