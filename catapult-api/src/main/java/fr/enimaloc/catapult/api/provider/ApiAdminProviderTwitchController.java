package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.service.provider.TwitchRawQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/twitch")
@RequiredArgsConstructor
public class ApiAdminProviderTwitchController {

    private final TwitchRawQueries queries;

    @GetMapping("/games")
    public RawProviderResponseSupport.RawProviderResponse games(@RequestParam String name) {
        return queries.games(name);
    }

    @GetMapping("/users")
    public RawProviderResponseSupport.RawProviderResponse users(
            @RequestParam UUID userId,
            @RequestParam(required = false) String login) {
        return queries.users(userId, login);
    }

    @GetMapping("/moderated-channels")
    public RawProviderResponseSupport.RawProviderResponse moderatedChannels(@RequestParam UUID userId) {
        return queries.moderatedChannels(userId);
    }
}
