package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchTimeoutFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "timeout";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("login", "durationSeconds", "reason");
    }

    @Override
    public List<String> optionalParameterNames() {
        return List.of("reason");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String login = String.valueOf(args[0]);
        int durationSeconds = ((Number) args[1]).intValue();
        String reason = ServiceFunction.optionalArg(args, 2);
        twitchChatService.timeout(user, login, durationSeconds, reason);
        return "";
    }
}
