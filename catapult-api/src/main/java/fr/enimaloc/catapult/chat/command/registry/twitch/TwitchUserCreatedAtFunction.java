package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchUserCreatedAtFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "userCreatedAt";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("login");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String login = String.valueOf(args[0]);
        return twitchChatService.getUserProfile(user, login)
            .map(p -> p.createdAt() == null ? ""
                : DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(p.createdAt()))
            .orElse("");
    }
}
