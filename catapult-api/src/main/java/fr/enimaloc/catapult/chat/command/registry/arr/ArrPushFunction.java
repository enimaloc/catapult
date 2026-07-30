package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Returns a NEW list with {@code value} appended — every {@code arr#*} function in this namespace
 * is non-mutating (functional style: reassign the result back to a variable), consistent with the
 * DSL having no concept of in-place mutation anywhere else (assignment always replaces a
 * variable's whole value). {@code list} may be {@code ""} to start a new array from scratch.
 */
@Component
public class ArrPushFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "push";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list", "value");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        List<Object> result = new ArrayList<>(ArrList.coerce(args[0]));
        result.add(args[1]);
        return result;
    }
}
