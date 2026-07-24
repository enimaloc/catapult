package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SteamPlaytimeFunction implements ServiceFunction {

    private final SteamApiClient steamApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    @Override
    public String namespace() {
        return "steam";
    }

    @Override
    public String name() {
        return "playtime";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("appId");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        if (user.getSteamId() == null) return "";
        String appId = String.valueOf(args[0]);
        String personalToken = user.getSteamPersonalToken() != null
            ? tokenEncryptionService.decrypt(user.getSteamPersonalToken())
            : null;
        return steamApiClient.getPlaytime(user.getSteamId(), appId, personalToken).join()
            .map(DurationFormatter::format)
            .orElse("");
    }
}
