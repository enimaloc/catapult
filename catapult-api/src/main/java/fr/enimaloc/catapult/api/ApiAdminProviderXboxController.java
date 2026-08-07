package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.XboxUserTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/xbox")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class ApiAdminProviderXboxController {

    private static final String PRESENCE_URL = "https://userpresence.xboxlive.com/users/xuid(";

    private final XboxUserTokenService tokenService;
    private final UserAccountRepository userAccountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/presence")
    public RawProviderResponseSupport.RawProviderResponse presence(@RequestParam UUID userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur inconnu"));
        XboxUserTokenService.XstsSession session = tokenService.getToken(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session Xbox indisponible pour cet utilisateur"));

        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(PRESENCE_URL + session.xuid() + ")?level=all"))
                .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                .header("x-xbl-contract-version", "3")
                .retrieve()
                .body(String.class));
    }
}
