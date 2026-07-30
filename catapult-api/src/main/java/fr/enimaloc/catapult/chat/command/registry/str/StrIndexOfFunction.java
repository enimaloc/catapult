package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** -1 (as a string, matching JS/Java convention) when {@code search} isn't found. */
@Component
public class StrIndexOfFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "indexOf";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text", "search");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return String.valueOf(String.valueOf(args[0]).indexOf(String.valueOf(args[1])));
    }
}
