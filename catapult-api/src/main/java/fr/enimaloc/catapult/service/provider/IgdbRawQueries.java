package fr.enimaloc.catapult.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.common.dto.QueryResult;
import fr.enimaloc.catapult.common.dto.admin.AdminProviderIgdbQueryRequest;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** The admin probe and the dev explorer of the IGDB API: one raw query, answered as IGDB sent it. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IgdbRawQueries {

    private static final String IGDB_API_BASE = "https://api.igdb.com/v4/";

    @Value("${app.igdb.client-id:}")
    private String clientId;

    private final IgdbService igdbService;
    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;
    private final ObjectMapper objectMapper;

    public RawProviderResponseSupport.RawProviderResponse query(AdminProviderIgdbQueryRequest body) {
        String token = igdbService.getAppToken();
        if (token.isBlank()) {
            return new RawProviderResponseSupport.RawProviderResponse(
                    502, null, "IGDB token not available — vérifiez la configuration client-id/secret");
        }

        return rawSupport.fetch(() -> post(body, token));
    }

    public QueryResult explorerQuery(AdminProviderIgdbQueryRequest body) {
        String token = igdbService.getAppToken();
        if (token.isBlank()) {
            return new QueryResult(null, "IGDB token not available — vérifiez la configuration client-id/secret");
        }

        try {
            String json = post(body, token);

            String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(objectMapper.readTree(json));
            return new QueryResult(pretty, null);
        } catch (HttpClientErrorException e) {
            log.warn("[DevIgdbExplorer] HTTP error on {}: {}", body.endpoint(), e.getStatusCode());
            return new QueryResult(null, e.getStatusCode() + "\n" + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[DevIgdbExplorer] Query failed on {}: {}", body.endpoint(), e.getMessage());
            return new QueryResult(null, e.getMessage());
        }
    }

    private String post(AdminProviderIgdbQueryRequest body, String token) {
        return restClient.post()
                .uri(IGDB_API_BASE + body.endpoint())
                .header("Client-ID", clientId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.TEXT_PLAIN)
                .body(body.query())
                .retrieve()
                .body(String.class);
    }
}
