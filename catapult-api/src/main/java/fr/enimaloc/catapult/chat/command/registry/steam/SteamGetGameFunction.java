package fr.enimaloc.catapult.chat.command.registry.steam;

import fr.enimaloc.catapult.chat.command.js.ChatCommandServiceGateway;
import fr.enimaloc.catapult.chat.command.registry.ServiceFunction;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SteamGetGameFunction implements ServiceFunction {

    private final ChatCommandServiceGateway gateway;

    @Override
    public String namespace() {
        return "steam";
    }

    @Override
    public String name() {
        return "getGame";
    }

    @Override
    public List<String> parameterNames() {
        return List.of("appId", "locale");
    }

    @Override
    public List<String> optionalParameterNames() {
        return List.of("locale");
    }

    @Override
    public List<String> returnKeys() {
        return List.of(
                "type", "name", "steam_appid", "required_age", "dlc", "detailed_description",
                "about_the_game", "short_description", "supported_languages", "header_image", "capsule_image",
                "capsule_imagev5", "website", "pc_requirements", "mac_requirements", "linux_requirements", "developers",
                "publishers", "packages", "package_groups", "platforms", "categories", "genres", "screenshots",
                "movies", "recommendations", "achievements", "release_date", "support_info", "background",
                "background_raw", "content_descriptors", "ratings"
        );
    }

    @Override
    public Object invoke(UserAccount user, Object[] args) {
        String appId = String.valueOf(args[0]);
        String locale = ServiceFunction.optionalArg(args, 1, null);
        // Empty map (not null) when not found — same "always return an object, empty on
        // failure" convention as every other object-returning function, so a chained property
        // access (steam#getGame(appId).name) degrades to undefined instead of a TypeError crash.
        return gateway.steamGame(appId, locale).orElse(Map.of());
    }
}
