package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Returns a NEW list with its last element removed (same non-mutating convention as {@code
 * arr#push} — see its doc) — an empty list stays empty rather than throwing. To read the removed
 * value itself, call {@code arr#last} before popping.
 */
@Component
public class ArrPopFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "pop";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        List<Object> list = ArrList.coerce(args[0]);
        return list.isEmpty() ? List.of() : new ArrayList<>(list.subList(0, list.size() - 1));
    }
}
