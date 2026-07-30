package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** Replaces every literal (non-regex) occurrence of {@code search} in {@code text} with {@code replacement}. */
@Component
public class StrReplaceFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "replace";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text", "search", "replacement");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return String.valueOf(args[0]).replace(String.valueOf(args[1]), String.valueOf(args[2]));
    }
}
