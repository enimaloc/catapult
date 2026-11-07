package fr.enimaloc.catapult.service.provider;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

/** The admin probe of Xbox Live presence: one raw call, answered as the provider sent it. */
@Service
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("xbox.enabled")
public class XboxRawQueries {

    private static final String PRESENCE_URL = "https://userpresence.xboxlive.com/users/xuid(";

    private final XboxUserTokenService tokenService;
    private final UserAccountRepository userAccountRepository;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    /** {@code level} is already validated and lower-cased. */
    public RawProviderResponseSupport.RawProviderResponse presence(UUID userId, String level) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        XboxUserTokenService.XstsSession session = tokenService.getToken(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Xbox session for this user"));

        return rawSupport.fetch(() -> restClient.get()
                .uri(URI.create(PRESENCE_URL + session.xuid() + ")?level=" + level))
                .header("Authorization", "XBL3.0 x=" + session.userHash() + ";" + session.token())
                .header("x-xbl-contract-version", "3")
                .retrieve()
                .body(String.class));
    }
}
