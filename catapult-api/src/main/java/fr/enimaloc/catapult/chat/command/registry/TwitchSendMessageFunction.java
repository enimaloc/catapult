package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchSendMessageFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "sendMessage";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("text");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        twitchChatService.sendMessage(user, String.valueOf(args[0]));
        return "";
    }
}
