package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchUserDisplayNameFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "userDisplayName";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("login");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String login = String.valueOf(args[0]);
        return twitchChatService.getUserProfile(user, login).map(TwitchUserProfile::displayName).orElse("");
    }
}
