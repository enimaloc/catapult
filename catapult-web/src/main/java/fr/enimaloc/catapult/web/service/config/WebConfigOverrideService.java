package fr.enimaloc.catapult.web.service.config;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.config.WebDatabaseOverridePropertySource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebConfigOverrideService {

    private final ApiClient apiClient;
    private final WebDatabaseOverridePropertySource source;
    private final WebConfigCatalogProperties props;

    public void apply(String key, String value) {
        validate(key);
        boolean ok = apiClient.put(
                "/api/admin/config/module/web/{key}",
                Map.of("value", value == null ? "" : value),
                key);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "catapult-api refused the override");
        }
        source.put(key, value);
    }

    public void clear(String key) {
        validate(key);
        boolean ok = apiClient.delete("/api/admin/config/module/web/{key}", key);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "catapult-api refused to clear the override");
        }
        source.remove(key);
    }

    private void validate(String key) {
        if (props.getTabooKeys().contains(key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Key is taboo");
        }
        if (props.getExposedPrefixes().stream().noneMatch(key::startsWith)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not exposable");
        }
    }
}
