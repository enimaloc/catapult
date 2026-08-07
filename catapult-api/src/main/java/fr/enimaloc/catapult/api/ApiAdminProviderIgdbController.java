package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.service.IgdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/igdb")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminProviderIgdbController {

    private static final String IGDB_API_BASE = "https://api.igdb.com/v4/";

    @Value("${app.igdb.client-id:}")
    private String clientId;

    private final IgdbService igdbService;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    public record QueryRequest(String endpoint, String query) {}

    @PostMapping("/query")
    public RawProviderResponseSupport.RawProviderResponse query(@RequestBody QueryRequest body) {
        if (!body.endpoint().matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endpoint: " + body.endpoint());
        }

        String token = igdbService.getAppToken();
        if (token.isBlank()) {
            return new RawProviderResponseSupport.RawProviderResponse(
                    502, null, "IGDB token not available — vérifiez la configuration client-id/secret");
        }

        return rawSupport.fetch(() -> restClient.post()
                .uri(IGDB_API_BASE + body.endpoint())
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN)
                .body(body.query())
                .retrieve()
                .body(String.class));
    }
}
