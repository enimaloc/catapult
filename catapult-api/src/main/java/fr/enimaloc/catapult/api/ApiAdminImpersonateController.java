package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/impersonate")
@RequiredArgsConstructor
public class ApiAdminImpersonateController {

    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;

    @Value("${app.owner-id:}")
    private String ownerId;

    @PostMapping
    public ImpersonateResponse impersonate(@AuthenticationPrincipal Jwt jwt,
                                           @RequestBody ImpersonateRequest body) {
        UUID adminId = UUID.fromString(jwt.getSubject());

        UserAccount target = userAccountRepository.findByTwitchUsername(body.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (target.isSystemAccount()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot impersonate system account");
        }
        if (target.getId().equals(adminId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot impersonate yourself");
        }
        if (target.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target user is not active");
        }

        List<String> roles = new ArrayList<>(List.of("ROLE_USER", "ROLE_PREVIOUS_ADMINISTRATOR"));
        if (!ownerId.isBlank() && ownerId.equals(target.getTwitchId())) {
            roles.add("ROLE_ADMIN");
        }

        String token = jwtService.generateForUser(target, roles);
        return new ImpersonateResponse(token);
    }

    public record ImpersonateRequest(String username) {}
    public record ImpersonateResponse(String token) {}
}
