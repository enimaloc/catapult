package fr.enimaloc.catapult.chat.command.registry.tw;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.GameContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Checks for one specific trigger warning by id (e.g. {@code violence_graphic}), unlike {@link
 * TwActiveFunction} which reports every active one at once — lets a command branch on a single
 * TW without string-matching the joined label list.
 */
@Component
@RequiredArgsConstructor
public class TwHasFunction implements ServiceFunction {

    private final GameContextService gameContextService;

    @Override
    public String namespace() {
        return "tw";
    }

    @Override
    public String name() {
        return "has";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("name");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String name = String.valueOf(args[0]);
        return gameContextService.get(user)
            .map(ctx -> ctx.activeTws() != null && ctx.activeTws().contains(name))
            .orElse(false);
    }
}
