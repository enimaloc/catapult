package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Splits on a literal (non-regex) separator — {@code arr#*} functions consume the result. */
@Component
public class StrSplitFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "split";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text", "separator");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String text = String.valueOf(args[0]);
        String separator = String.valueOf(args[1]);
        if (separator.isEmpty()) {
            return text.isEmpty() ? List.of() : List.of(text);
        }
        return Arrays.asList(text.split(Pattern.quote(separator), -1));
    }
}
