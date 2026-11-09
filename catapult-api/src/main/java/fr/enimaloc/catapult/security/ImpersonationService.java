package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An admin's token for acting as another member: a user token marked as impersonated, admin only when the target is
 * the owner.
 */
@Service
@RequiredArgsConstructor
public class ImpersonationService {

    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;

    @Value("${app.owner-id:}")
    private String ownerId;

    public String impersonate(UUID adminId, String username) {
        UserAccount target = userAccountRepository.findByTwitchUsername(username)
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

        return jwtService.generateForUser(target, roles);
    }
}
