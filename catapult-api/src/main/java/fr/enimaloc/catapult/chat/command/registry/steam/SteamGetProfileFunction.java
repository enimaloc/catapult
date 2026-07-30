package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.registry.DtoMapper;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SteamGetProfileFunction implements ServiceFunction {

    /** All-{@code ""} fields when no linked account, a private profile, or no data found. */
    public record Result(String displayName, String onlineStatus, String currentGame) {}

    private static final Result EMPTY = new Result("", "", "");

    private final SteamApiClient steamApiClient;
    private final TokenEncryptionService tokenEncryptionService;

    @Override
    public String namespace() {
        return "steam";
    }

    @Override
    public String name() {
        return "getProfile";
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
        if (user.getSteamId() == null) return DtoMapper.toMap(EMPTY);

        String personalToken = user.getSteamPersonalToken() != null
            ? tokenEncryptionService.decrypt(user.getSteamPersonalToken())
            : null;
        boolean profilePublic = steamApiClient.getProfileStatus(user.getSteamId(), personalToken)
            .join().profilePublic();
        if (!profilePublic) return DtoMapper.toMap(EMPTY);

        Result result = steamApiClient.getPlayerProfile(user.getSteamId(), personalToken).join()
            .map(profile -> new Result(
                profile.displayName() != null ? profile.displayName() : "",
                profile.onlineStatus() != null ? profile.onlineStatus() : "",
                profile.gameName() != null ? profile.gameName() : ""))
            .orElse(EMPTY);
        return DtoMapper.toMap(result);
    }
}
