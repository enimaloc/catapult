package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** String-compares each element (matching how every other value in this DSL is a string). */
@Component
public class ArrContainsFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "contains";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list", "value");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String value = String.valueOf(args[1]);
        return String.valueOf(ArrList.coerce(args[0]).stream().anyMatch(item -> value.equals(String.valueOf(item))));
    }
}
