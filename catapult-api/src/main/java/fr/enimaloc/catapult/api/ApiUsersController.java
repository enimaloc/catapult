package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.account.UserRefDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account a Twitch username names, for catapult-web: its pages name a channel by its username, catapult-api's
 * routes by its account id. The match is exact, case included. No rule of its own: the
 * {@code anyRequest} one (a member, or a loopback caller) applies, and an unknown username is a 404.
 */
@RestController
@RequiredArgsConstructor
public class ApiUsersController {

    private final ApiUserResolver userResolver;

    @GetMapping(ApiPaths.INTERNAL + "/users")
    public UserRefDto lookup(@RequestParam String twitchUsername) {
        UserAccount account = userResolver.byTwitchUsername(twitchUsername);
        return new UserRefDto(account.getId(), account.getTwitchUsername());
    }
}
