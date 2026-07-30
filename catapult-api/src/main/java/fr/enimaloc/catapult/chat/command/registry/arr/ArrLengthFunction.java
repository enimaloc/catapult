package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ArrLengthFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "length";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return String.valueOf(ArrList.coerce(args[0]).size());
    }
}
