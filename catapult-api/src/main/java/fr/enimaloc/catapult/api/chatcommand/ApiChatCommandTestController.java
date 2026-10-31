package fr.enimaloc.catapult.api.chatcommand;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandTester;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Backs the "Tester" button in the command editor modal: runs a command in the sandbox
 * (see {@link ChatCommandTester}), behind the same auth/ownership/experiment-gate checks as
 * {@link ApiChatCommandsController}.
 */
@RestController
@RequiredArgsConstructor
public class ApiChatCommandTestController {

    private final ChatCommandTester tester;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @PostMapping(ApiPaths.USER + "/chat-commands/{id}/test")
    public Map<String, Object> test(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                     @RequestBody Map<String, Object> body) {
        return tester.test(userResolver.viewerByTwitchId(jwt), id, body);
    }
}
