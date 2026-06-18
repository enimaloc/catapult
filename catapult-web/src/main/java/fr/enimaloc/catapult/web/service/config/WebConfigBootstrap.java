package fr.enimaloc.catapult.web.service.config;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.config.WebDatabaseOverridePropertySource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebConfigBootstrap {

    private final ApiClient apiClient;
    private final WebDatabaseOverridePropertySource source;

    @EventListener
    public void onReady(ApplicationReadyEvent ignored) {
        loadAll();
    }

    public void loadAll() {
        List<Map<String, Object>> rows = apiClient.get(
                "/api/admin/config/module/web",
                new ParameterizedTypeReference<>() {});
        if (rows == null) {
            log.warn("Could not load web config overrides from catapult-api (null response); continuing without overrides");
            return;
        }
        int loaded = 0;
        for (Map<String, Object> row : rows) {
            Object key = row.get("key");
            Object value = row.get("value");
            if (key instanceof String k && value instanceof String v) {
                source.put(k, v);
                loaded++;
            }
        }
        log.info("Loaded {} web config overrides from catapult-api", loaded);
    }
}
