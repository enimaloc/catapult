package fr.enimaloc.catapult.chat.command.registry.str;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code start}/{@code end} are clamped into range rather than throwing — a streamer-typed
 * command shouldn't be able to crash execution over an out-of-range index, unlike Java's own
 * {@code String#substring} which throws {@link StringIndexOutOfBoundsException}.
 */
@Component
public class StrSubstringFunction implements ServiceFunction {

    @Override
    public String namespace() {
        return "str";
    }

    @Override
    public String name() {
        return "substring";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text", "start", "end");
    }

    @Override
    public List<String> optionalParameterNames() {
        return List.of("end");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String text = String.valueOf(args[0]);
        int len = text.length();
        int start = clamp(parseIntOr(args.length > 1 ? args[1] : "0", 0), len);
        int end = args.length > 2 && !String.valueOf(args[2]).isEmpty()
            ? clamp(parseIntOr(args[2], len), len) : len;
        return start >= end ? "" : text.substring(start, end);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static int parseIntOr(Object value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
