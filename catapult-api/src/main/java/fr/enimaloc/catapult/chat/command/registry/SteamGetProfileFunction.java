package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SteamGetProfileFunction implements ServiceFunction {

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
    public Object invoke(UserAccount user, Object[] args) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (user.getSteamId() == null) {
            result.put("displayName", "");
            result.put("onlineStatus", "");
            result.put("currentGame", "");
            return result;
        }
        String personalToken = user.getSteamPersonalToken() != null
            ? tokenEncryptionService.decrypt(user.getSteamPersonalToken())
            : null;
        boolean profilePublic = steamApiClient.getProfileStatus(user.getSteamId(), personalToken)
            .join().profilePublic();
        if (!profilePublic) {
            result.put("displayName", "");
            result.put("onlineStatus", "");
            result.put("currentGame", "");
            return result;
        }
        steamApiClient.getPlayerProfile(user.getSteamId(), personalToken).join()
            .ifPresentOrElse(profile -> {
                result.put("displayName", profile.displayName() != null ? profile.displayName() : "");
                result.put("onlineStatus", profile.onlineStatus() != null ? profile.onlineStatus() : "");
                result.put("currentGame", profile.gameName() != null ? profile.gameName() : "");
            }, () -> {
                result.put("displayName", "");
                result.put("onlineStatus", "");
                result.put("currentGame", "");
            });
        return result;
    }
}
