package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class StrUpperFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "upper";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return String.valueOf(args[0]).toUpperCase(Locale.ROOT);
    }
}
