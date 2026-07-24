package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Read-only — there is deliberately no {@code setFlag}; flags are admin/experiment-targeting only. */
@Component
@RequiredArgsConstructor
public class CatapultFlagFunction implements ServiceFunction {

    private final UserFlagRepository userFlagRepository;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "flag";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("key");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String key = String.valueOf(args[0]);
        return userFlagRepository.findByUserAndFlagKey(user, key)
            .map(UserFlag::getFlagValue)
            .orElse("");
    }
}
