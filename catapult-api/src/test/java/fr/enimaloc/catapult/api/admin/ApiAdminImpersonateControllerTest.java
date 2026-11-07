package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.ImpersonateRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.ImpersonationService;
import fr.enimaloc.catapult.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The impersonation route called directly: whom an admin may impersonate, and the roles the minted token carries. */
class ApiAdminImpersonateControllerTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final ImpersonationService impersonation = new ImpersonationService(accounts, jwtService);
    private final ApiAdminImpersonateController controller = new ApiAdminImpersonateController(impersonation);
    private final UUID adminId = UUID.randomUUID();
    private UserAccount target;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(impersonation, "ownerId", "tw-owner");
        target = new UserAccount();
        target.setId(UUID.randomUUID());
        target.setTwitchId("tw-target");
        target.setTwitchUsername("target");
        target.setStatus(UserAccount.Status.ACTIVE);
        when(accounts.findByTwitchUsername("target")).thenReturn(Optional.of(target));
        when(jwtService.generateForUser(any(), anyList())).thenReturn("minted");
    }

    private Jwt jwt(UUID subject) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(subject.toString()).build();
    }

    private void assertRefused(UUID caller, HttpStatus status, String reason) {
        assertThatThrownBy(() -> controller.impersonate(jwt(caller), new ImpersonateRequest("target")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(status);
                    assertThat(e.getReason()).isEqualTo(reason);
                });
    }

    @Test
    void mintsAUserTokenMarkedAsImpersonated() {
        assertThat(controller.impersonate(jwt(adminId), new ImpersonateRequest("target")).token()).isEqualTo("minted");

        verify(jwtService).generateForUser(target, List.of("ROLE_USER", "ROLE_PREVIOUS_ADMINISTRATOR"));
    }

    @Test
    void theOwnerKeepsTheAdminRole() {
        target.setTwitchId("tw-owner");

        controller.impersonate(jwt(adminId), new ImpersonateRequest("target"));

        verify(jwtService).generateForUser(target, List.of("ROLE_USER", "ROLE_PREVIOUS_ADMINISTRATOR", "ROLE_ADMIN"));
    }

    @Test
    void refusesUnknownSystemSelfAndInactiveTargets() {
        when(accounts.findByTwitchUsername("target")).thenReturn(Optional.empty());
        assertRefused(adminId, HttpStatus.NOT_FOUND, "User not found");
        when(accounts.findByTwitchUsername("target")).thenReturn(Optional.of(target));

        assertRefused(target.getId(), HttpStatus.FORBIDDEN, "Cannot impersonate yourself");
        target.setStatus(UserAccount.Status.PENDING_DELETION);
        assertRefused(adminId, HttpStatus.FORBIDDEN, "Target user is not active");
        target.setSystemAccount(true);
        assertRefused(target.getId(), HttpStatus.FORBIDDEN, "Cannot impersonate system account");
        verify(jwtService, never()).generateForUser(any(), anyList());
    }

    @Test
    void withoutJwt_is401BeforeTheLookup() {
        assertThatThrownBy(() -> controller.impersonate(null, new ImpersonateRequest("target")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(accounts, never()).findByTwitchUsername(any());
    }
}
