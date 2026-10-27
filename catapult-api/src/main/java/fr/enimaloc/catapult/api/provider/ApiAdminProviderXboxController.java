package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.service.provider.XboxRawQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/xbox")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class ApiAdminProviderXboxController {

    private static final Set<String> VALID_LEVELS = Set.of("user", "device", "title", "all");

    private final XboxRawQueries queries;

    @GetMapping("/presence")
    public RawProviderResponseSupport.RawProviderResponse presence(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "all") String level) {
        String normalizedLevel = level.toLowerCase();
        if (!VALID_LEVELS.contains(normalizedLevel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Xbox level: " + level);
        }
        return queries.presence(userId, normalizedLevel);
    }
}
