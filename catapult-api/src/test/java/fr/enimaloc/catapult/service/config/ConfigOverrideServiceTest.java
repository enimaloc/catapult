package fr.enimaloc.catapult.service.config;

import fr.enimaloc.catapult.config.DatabaseOverridePropertySource;
import fr.enimaloc.catapult.domain.ConfigAudit;
import fr.enimaloc.catapult.domain.ConfigOverride;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ConfigOverrideAppliedEvent;
import fr.enimaloc.catapult.repository.ConfigAuditRepository;
import fr.enimaloc.catapult.repository.ConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigOverrideServiceTest {

    @Mock ConfigOverrideRepository overrideRepo;
    @Mock ConfigAuditRepository auditRepo;
    @Mock ContextRefresher refresher;
    @Mock ApplicationEventPublisher eventPublisher;

    DatabaseOverridePropertySource source;
    ConfigCatalogProperties props;
    ConfigOverrideService service;
    UserAccount actor;

    @BeforeEach
    void setUp() {
        source = new DatabaseOverridePropertySource();
        props = new ConfigCatalogProperties();
        props.setExposedPrefixes(List.of("app.", "twitch.", "dtdd."));
        props.setSecretPatterns(List.of(".*\\.secret$"));
        props.setTabooKeys(List.of("app.jwt.secret"));
        actor = new UserAccount();
        actor.setId(UUID.randomUUID());
        service = new ConfigOverrideService(overrideRepo, auditRepo, source, props, refresher, eventPublisher);
    }

    @Test
    void apply_storesOverrideAndAuditAndRefreshes() {
        when(overrideRepo.findByIdModuleAndIdKey("api", "app.foo")).thenReturn(Optional.empty());

        service.apply("app.foo", "42", actor);

        ArgumentCaptor<ConfigOverride> ovCap = ArgumentCaptor.forClass(ConfigOverride.class);
        verify(overrideRepo).save(ovCap.capture());
        assertThat(ovCap.getValue().getKey()).isEqualTo("app.foo");
        assertThat(ovCap.getValue().getModule()).isEqualTo("api");
        assertThat(ovCap.getValue().getValue()).isEqualTo("42");
        assertThat(source.getProperty("app.foo")).isEqualTo("42");

        ArgumentCaptor<ConfigAudit> auCap = ArgumentCaptor.forClass(ConfigAudit.class);
        verify(auditRepo).save(auCap.capture());
        assertThat(auCap.getValue().getNewValue()).isEqualTo("42");
        assertThat(auCap.getValue().getModule()).isEqualTo("api");

        verify(refresher).refresh();
        verify(eventPublisher).publishEvent(any(ConfigOverrideAppliedEvent.class));
    }

    @Test
    void apply_secretKey_redactsAuditValues() {
        when(overrideRepo.findByIdModuleAndIdKey("api", "twitch.client-secret")).thenReturn(Optional.empty());

        service.apply("twitch.client-secret", "supersecret", actor);

        ArgumentCaptor<ConfigAudit> auCap = ArgumentCaptor.forClass(ConfigAudit.class);
        verify(auditRepo).save(auCap.capture());
        assertThat(auCap.getValue().getNewValue()).isEqualTo("***");
        assertThat(auCap.getValue().getPreviousValue()).isEqualTo("***");
    }

    @Test
    void apply_tabooKey_throwsForbidden() {
        assertThatThrownBy(() -> service.apply("app.jwt.secret", "x", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verify(overrideRepo, never()).save(any());
    }

    @Test
    void apply_unexposedKey_throwsNotFound() {
        assertThatThrownBy(() -> service.apply("spring.datasource.url", "x", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void apply_dtddMinConfidence_outOfRange_throwsBadRequest() {
        assertThatThrownBy(() -> service.apply("dtdd.match.min-confidence", "1.5", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void apply_dtddMinConfidence_inRange_accepted() {
        when(overrideRepo.findByIdModuleAndIdKey("api", "dtdd.match.min-confidence")).thenReturn(Optional.empty());
        service.apply("dtdd.match.min-confidence", "0.9", actor);
        verify(overrideRepo).save(any());
    }

    @Test
    void apply_dtddWeight_negative_throwsBadRequest() {
        assertThatThrownBy(() -> service.apply("dtdd.match.weight-name", "-0.1", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void apply_dtddCacheTtl_zero_throwsBadRequest() {
        assertThatThrownBy(() -> service.apply("dtdd.cache.topics-ttl-hours", "0", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void apply_dtddCacheTtl_nonNumeric_throwsBadRequest() {
        assertThatThrownBy(() -> service.apply("dtdd.cache.topics-ttl-hours", "abc", actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void clear_removesOverrideAndAuditsAndRefreshes() {
        ConfigOverride existing = new ConfigOverride();
        existing.setKey("app.foo");
        existing.setValue("old");
        existing.setSecret(false);
        when(overrideRepo.findByIdModuleAndIdKey("api", "app.foo")).thenReturn(Optional.of(existing));
        source.put("app.foo", "old");

        service.clear("app.foo", actor);

        verify(overrideRepo).delete(existing);
        assertThat(source.getProperty("app.foo")).isNull();

        ArgumentCaptor<ConfigAudit> auCap = ArgumentCaptor.forClass(ConfigAudit.class);
        verify(auditRepo).save(auCap.capture());
        assertThat(auCap.getValue().getPreviousValue()).isEqualTo("old");
        assertThat(auCap.getValue().getNewValue()).isNull();
        assertThat(auCap.getValue().getModule()).isEqualTo("api");

        verify(refresher).refresh();
    }

    @Test
    void apply_webModule_storesOverrideWithoutRefreshOrPropertySource() {
        when(overrideRepo.findByIdModuleAndIdKey("web", "web.feature.foo")).thenReturn(Optional.empty());

        service.apply("web", "web.feature.foo", "true", actor);

        ArgumentCaptor<ConfigOverride> ovCap = ArgumentCaptor.forClass(ConfigOverride.class);
        verify(overrideRepo).save(ovCap.capture());
        assertThat(ovCap.getValue().getKey()).isEqualTo("web.feature.foo");
        assertThat(ovCap.getValue().getModule()).isEqualTo("web");
        assertThat(ovCap.getValue().getValue()).isEqualTo("true");

        ArgumentCaptor<ConfigAudit> auCap = ArgumentCaptor.forClass(ConfigAudit.class);
        verify(auditRepo).save(auCap.capture());
        assertThat(auCap.getValue().getModule()).isEqualTo("web");
        assertThat(auCap.getValue().getNewValue()).isEqualTo("true");

        // No api property-source mutation
        assertThat(source.getProperty("web.feature.foo")).isNull();
        // No context refresh for web
        verify(refresher, never()).refresh();
        // Event still published for observability
        verify(eventPublisher).publishEvent(any(ConfigOverrideAppliedEvent.class));
    }

    @Test
    void apply_webModule_skipsTabooAndExposedValidation() {
        // 'spring.datasource.url' is NOT exposed for api, but web is unrestricted here.
        when(overrideRepo.findByIdModuleAndIdKey("web", "spring.datasource.url")).thenReturn(Optional.empty());

        service.apply("web", "spring.datasource.url", "x", actor);

        verify(overrideRepo).save(any(ConfigOverride.class));
        verify(auditRepo).save(any(ConfigAudit.class));
    }

    @Test
    void clear_webModule_removesOverrideWithoutRefresh() {
        ConfigOverride existing = new ConfigOverride();
        existing.setModule("web");
        existing.setKey("web.feature.foo");
        existing.setValue("true");
        existing.setSecret(false);
        when(overrideRepo.findByIdModuleAndIdKey("web", "web.feature.foo")).thenReturn(Optional.of(existing));

        service.clear("web", "web.feature.foo", actor);

        verify(overrideRepo).delete(existing);
        verify(refresher, never()).refresh();
        verify(eventPublisher).publishEvent(any(ConfigOverrideAppliedEvent.class));

        ArgumentCaptor<ConfigAudit> auCap = ArgumentCaptor.forClass(ConfigAudit.class);
        verify(auditRepo).save(auCap.capture());
        assertThat(auCap.getValue().getModule()).isEqualTo("web");
    }
}
