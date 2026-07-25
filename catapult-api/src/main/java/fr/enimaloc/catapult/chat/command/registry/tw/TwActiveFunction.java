package fr.enimaloc.catapult.chat.command.registry.tw;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Comma-joined labels of the trigger warnings currently active for the streamer's detected
 * game — same content/ordering as the legacy {@code tw#active} context path, exposed as a
 * service call instead. {@code ""} when none are active (or no game is detected).
 */
@Component
@RequiredArgsConstructor
public class TwActiveFunction implements ServiceFunction {

    private final GameContextService gameContextService;

    @Override
    public String namespace() {
        return "tw";
    }

    @Override
    public String name() {
        return "active";
    }

    @Override
    public List<String> parameterNames() {
        return List.of();
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return gameContextService.get(user)
            .map(ctx -> ctx.activeTws() == null ? List.<String>of()
                : ctx.activeTws().stream()
                    .map(id -> ctx.twLabels() == null ? null : ctx.twLabels().get(id))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder())
                    .toList())
            .filter(labels -> !labels.isEmpty())
            .map(labels -> String.join(", ", labels))
            .orElse("");
    }
}
