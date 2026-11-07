package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.provider.MinecraftRawQueries;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/minecraft")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiAdminProviderMinecraftController {

    private final MinecraftRawQueries queries;

    @GetMapping("/profile-lookup")
    public RawProviderResponseSupport.RawProviderResponse profileLookup(@RequestParam String name) {
        return queries.profileLookup(name);
    }

    @GetMapping("/friends")
    public RawProviderResponseSupport.RawProviderResponse friends(@RequestParam UUID accountId) {
        return queries.friends(accountId);
    }

    @GetMapping("/profile")
    public RawProviderResponseSupport.RawProviderResponse profile(@RequestParam UUID accountId) {
        return queries.profile(accountId);
    }

    @GetMapping("/presence")
    public RawProviderResponseSupport.RawProviderResponse presence(
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "ONLINE") String status,
            @RequestParam(required = false) String activityId) {
        return queries.presence(accountId, status, activityId);
    }
}
