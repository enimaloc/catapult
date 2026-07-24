package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchUserFollowedAtFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "userFollowedAt";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("login");
    }

    @Override
    public List<String> requiredScopes() {
        return List.of("moderator:read:followers");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String login = String.valueOf(args[0]);
        return twitchChatService.getFollowedAt(user, login)
            .map(followedAt -> DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(followedAt))
            .orElse("");
    }
}
