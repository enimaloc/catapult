package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** Returns the DSL's boolean-literal spelling ("true"/"false"), usable directly in an {@code if}. */
@Component
public class StrIncludesFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "includes";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text", "search");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return String.valueOf(String.valueOf(args[0]).contains(String.valueOf(args[1])));
    }
}
