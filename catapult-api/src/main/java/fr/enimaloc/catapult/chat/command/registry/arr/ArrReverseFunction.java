package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-mutating, same convention as {@code arr#push}/{@code arr#pop} — returns a NEW reversed list. */
@Component
public class ArrReverseFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "reverse";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        List<Object> result = new ArrayList<>(ArrList.coerce(args[0]));
        Collections.reverse(result);
        return result;
    }
}
