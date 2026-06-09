package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.StreamStateService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CatapultGauges implements MeterBinder {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;

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
    }
}
