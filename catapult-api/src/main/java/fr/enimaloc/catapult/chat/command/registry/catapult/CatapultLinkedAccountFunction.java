package fr.enimaloc.catapult.chat.command.registry.catapult;

import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CatapultLinkedAccountFunction implements ServiceFunction {

    private final MinecraftFriendLinkRepository minecraftFriendLinkRepository;
    private final OAuthTokenRepository oAuthTokenRepository;

    @Override
    public String namespace() {
        return "catapult";
    }

    @Override
    public String name() {
        return "linkedAccount";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("provider");
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String provider = String.valueOf(args[0]).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "steam" -> user.getSteamId() != null ? user.getSteamId() : "";
            case "minecraft" -> minecraftFriendLinkRepository.findWithServiceAccountByUser(user)
                .filter(link -> link.getStatus() == MinecraftFriendLink.Status.ACCEPTED)
                .map(MinecraftFriendLink::getMinecraftName)
                .orElse("");
            // No gamertag/identifier is stored for Xbox anywhere today (only the OAuth token
            // itself, see XboxGameGetter/ApiChannelDataController's own linked-presence checks)
            // — resolving one would mean a new Xbox API call, out of scope here. "linked" is a
            // stand-in non-empty value satisfying the "empty means not linked" contract.
            case "xbox" -> oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX)
                .isPresent() ? "linked" : "";
            default -> "";
        };
    }
}
