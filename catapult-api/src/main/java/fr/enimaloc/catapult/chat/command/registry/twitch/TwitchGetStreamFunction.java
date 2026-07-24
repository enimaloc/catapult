package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.chat.command.registry.DurationFormatter;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import fr.enimaloc.catapult.service.TwitchStreamInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TwitchGetStreamFunction implements ServiceFunction {

    private final TwitchChatService twitchChatService;

    @Override
    public String namespace() {
        return "twitch";
    }

    @Override
    public String name() {
        return "getStream";
    }

    @Override
    public List<String> parameterNames() {
        return List.of();
    }

    @Override
    public List<String> returnKeys() {
        return List.of("title", "category", "viewers", "uptime");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        Map<String, Object> result = new LinkedHashMap<>();
        twitchChatService.getStreamInfo(user).ifPresentOrElse(stream -> {
            result.put("title", stream.title());
            result.put("category", stream.category());
            result.put("viewers", stream.viewers());
            result.put("uptime", formatUptime(stream.startedAt()));
        }, () -> {
            result.put("title", "");
            result.put("category", "");
            result.put("viewers", "");
            result.put("uptime", "");
        });
        return result;
    }

    private static String formatUptime(Instant startedAt) {
        return DurationFormatter.format(Duration.between(startedAt, Instant.now()));
    }
}
