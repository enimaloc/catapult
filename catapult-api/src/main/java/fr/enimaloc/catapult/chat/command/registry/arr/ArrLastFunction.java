package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code ""} for an empty list. */
@Component
public class ArrLastFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "last";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        List<Object> list = ArrList.coerce(args[0]);
        return list.isEmpty() ? "" : String.valueOf(list.get(list.size() - 1));
    }
}
