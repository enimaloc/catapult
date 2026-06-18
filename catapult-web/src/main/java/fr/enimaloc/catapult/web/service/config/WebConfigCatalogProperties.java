package fr.enimaloc.catapult.web.service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties("app.admin.web-config")
@Getter
@Setter
public class WebConfigCatalogProperties {
    private List<String> exposedPrefixes = List.of();
    private List<String> secretPatterns = List.of();
    private List<String> tabooKeys = List.of();
}
