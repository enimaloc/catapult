package fr.enimaloc.catapult.chat.command.registry.twitch;

import fr.enimaloc.catapult.chat.command.registry.DtoMapper;
import fr.enimaloc.catapult.chat.command.registry.DurationFormatter;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.TwitchChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TwitchGetStreamFunction implements ServiceFunction {

    /** All-{@code ""} fields when offline — same convention as every other missing-data case. */
    public record Result(String title, String category, String viewers, String uptime) {}

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
        return DtoMapper.keys(Result.class);
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        Result result = twitchChatService.getStreamInfo(user)
            .map(stream -> new Result(stream.title(), stream.category(),
                String.valueOf(stream.viewers()), formatUptime(stream.startedAt())))
            .orElse(new Result("", "", "", ""));
        return DtoMapper.toMap(result);
    }

    private static String formatUptime(Instant startedAt) {
        return DurationFormatter.format(Duration.between(startedAt, Instant.now()));
    }
}
