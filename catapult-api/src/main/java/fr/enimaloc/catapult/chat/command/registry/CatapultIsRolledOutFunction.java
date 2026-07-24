package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CatapultIsRolledOutFunction implements ServiceFunction {

    private final ExperimentService experimentService;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "isRolledOut";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("key");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String key = String.valueOf(args[0]);
        // "true"/"false" string, matching the BOOLEAN-typed literal spelling used elsewhere in the DSL.
        return String.valueOf(experimentService.evaluateGate(user, key));
    }
}
