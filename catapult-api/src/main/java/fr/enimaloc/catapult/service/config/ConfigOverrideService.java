package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.ConfigAudit;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.domain.ConfigOverrideId;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ConfigOverrideAppliedEvent;
import fr.enimaloc.catapult.repository.ConfigAuditRepository;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConfigOverrideService {

    public static final String MODULE_API = "api";
    public static final String MODULE_WEB = "web";

    private final ConfigOverrideRepository overrideRepo;
    private final ConfigAuditRepository auditRepo;
    private final DatabaseOverridePropertySource source;
    private final ConfigCatalogProperties props;
    private final ContextRefresher refresher;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void apply(String key, String newValue, UserAccount actor) {
        apply(MODULE_API, key, newValue, actor);
    }

    @Transactional
    public void apply(String module, String key, String newValue, UserAccount actor) {
        String mod = normalizeModule(module);
        if (MODULE_API.equals(mod)) {
            validate(key);
            validateValue(key, newValue);
        }
        boolean secret = isSecret(key);

        Optional<ConfigOverride> existing = overrideRepo.findByIdModuleAndIdKey(mod, key);
        String previousValue = existing.map(ConfigOverride::getValue).orElse(null);

        ConfigOverride ov = existing.orElseGet(ConfigOverride::new);
        ov.setId(new ConfigOverrideId(mod, key));
        ov.setValue(newValue);
        ov.setSecret(secret);
        ov.setUpdatedAt(Instant.now());
        ov.setUpdatedBy(actor.getId());
        overrideRepo.save(ov);

        ConfigAudit audit = new ConfigAudit();
        audit.setModule(mod);
        audit.setKey(key);
        audit.setPreviousValue(secret ? "***" : previousValue);
        audit.setNewValue(secret ? "***" : newValue);
        audit.setChangedAt(Instant.now());
        audit.setChangedBy(actor.getId());
        auditRepo.save(audit);

        if (MODULE_API.equals(mod)) {
            source.put(key, newValue);
            refresher.refresh();
        }
        eventPublisher.publishEvent(new ConfigOverrideAppliedEvent(this, key, newValue));
    }

    @Transactional
    public void clear(String key, UserAccount actor) {
        clear(MODULE_API, key, actor);
    }

    @Transactional
    public void clear(String module, String key, UserAccount actor) {
        String mod = normalizeModule(module);
        if (MODULE_API.equals(mod)) {
            validate(key);
        }
        Optional<ConfigOverride> existing = overrideRepo.findByIdModuleAndIdKey(mod, key);
        if (existing.isEmpty()) {
            return;
        }
        ConfigOverride ov = existing.get();
        boolean secret = ov.isSecret();
        overrideRepo.delete(ov);

        ConfigAudit audit = new ConfigAudit();
        audit.setModule(mod);
        audit.setKey(key);
        audit.setPreviousValue(secret ? "***" : ov.getValue());
        audit.setNewValue(null);
        audit.setChangedAt(Instant.now());
        audit.setChangedBy(actor.getId());
        auditRepo.save(audit);

        if (MODULE_API.equals(mod)) {
            source.remove(key);
            refresher.refresh();
        }
        eventPublisher.publishEvent(new ConfigOverrideAppliedEvent(this, key, null));
    }

    private String normalizeModule(String module) {
        return module == null || module.isBlank() ? MODULE_API : module;
    }

    private void validate(String key) {
        if (props.getTabooKeys().contains(key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Key is taboo");
        }
        if (props.getExposedPrefixes().stream().noneMatch(key::startsWith)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not exposable");
        }
    }

    void validateValue(String key, String value) {
        if (value == null) return;
        if (key.equals("dtdd.match.min-confidence") || key.equals("dtdd.match.min-candidate-score")) {
            double d = parseDouble(key, value);
            if (d < 0.0 || d > 1.0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be in [0.0, 1.0]");
            }
        } else if (key.startsWith("dtdd.match.weight-")) {
            double d = parseDouble(key, value);
            if (d < 0.0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be >= 0");
            }
        } else if (key.startsWith("dtdd.cache.") && (key.contains("ttl-hours") || key.contains("ttl-days"))) {
            long n = parseLong(key, value);
            if (n <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be > 0");
            }
        } else if (key.equals("tw.backfill.batch-size") || key.equals("tw.backfill.throttle-per-second")) {
            long n = parseLong(key, value);
            if (n <= 0 || n > Integer.MAX_VALUE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a positive integer");
            }
        }
    }

    private static double parseDouble(String key, String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number");
        }
    }

    private static long parseLong(String key, String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be an integer");
        }
    }

    private boolean isSecret(String key) {
        // Same kebab->dot normalization as ConfigCatalogService for relaxed-binding consistency.
        String normalized = key.replace('-', '.');
        return props.getSecretPatterns().stream()
                .map(Pattern::compile)
                .anyMatch(p -> p.matcher(normalized).matches());
    }
}
