package fr.enimaloc.catapult.chat.command.registry.arr;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ArrJoinFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "arr";
    }

    @Override
    public String name() {
        return "join";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("list", "separator");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        return ArrList.coerce(args[0]).stream()
            .map(String::valueOf)
            .collect(Collectors.joining(String.valueOf(args[1])));
    }
}
