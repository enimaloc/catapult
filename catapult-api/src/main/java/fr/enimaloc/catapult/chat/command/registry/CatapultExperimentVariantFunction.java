package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CatapultExperimentVariantFunction implements ServiceFunction {

    private final ExperimentService experimentService;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "experimentVariant";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("key");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String key = String.valueOf(args[0]);
        return experimentService.getVariant(user, key)
            .map(ExperimentVariant::getKey)
            .orElse("");
    }
}
