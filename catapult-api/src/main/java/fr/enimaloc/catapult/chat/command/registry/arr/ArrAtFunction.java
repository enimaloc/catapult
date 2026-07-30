package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code ""} (not an exception) for a missing/out-of-range/non-numeric index. */
@Component
public class ArrAtFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "at";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list", "index");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        List<Object> list = ArrList.coerce(args[0]);
        int index;
        try {
            index = Integer.parseInt(String.valueOf(args[1]).trim());
        } catch (NumberFormatException e) {
            return "";
        }
        return index < 0 || index >= list.size() ? "" : String.valueOf(list.get(index));
    }
}
