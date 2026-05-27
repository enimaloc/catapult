# A/B Testing Provider Abstraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce an `ExperimentProvider` interface allowing the app to delegate A/B testing to internal, GitLab, GrowthBook, or Unleash backends — selected via `application.properties`.

**Architecture:** A Spring `ActiveProviderHolder` bean holds the active `ExperimentProvider`. `ExperimentService.getVariant()` delegates to it when the provider is external; the internal logic is preserved unchanged behind `resolveVariantLocally()`. `ExperimentProviderBootstrap` detects provider changes at startup and syncs ACTIVE experiments to the new backend.

**Tech Stack:** Java 21, Spring Boot 4.0.4, Spring RestClient (HTTP calls), Unleash client SDK (`io.getunleash:unleash-client-java:9.2.0`), GrowthBook Java SDK (`io.growthbook.sdk:GrowthBook:1.3.5`), JUnit 5 + Mockito + MockRestServiceServer.

---

## File Map

**Create:**
- `src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentProvider.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentSummary.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProvider.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProvider.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProvider.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProvider.java`
- `src/main/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolder.java`
- `src/main/java/fr/enimaloc/catapult/config/ExperimentProviderProperties.java`
- `src/main/java/fr/enimaloc/catapult/config/ExperimentProviderConfig.java`
- `src/main/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrap.java`
- `src/main/java/fr/enimaloc/catapult/domain/SystemSetting.java`
- `src/main/java/fr/enimaloc/catapult/repository/SystemSettingRepository.java`
- `src/main/resources/db/migration/V25__system_settings.sql`

**Modify:**
- `src/main/java/fr/enimaloc/catapult/service/ExperimentService.java` — rename `getVariant` body to `resolveVariantLocally`, add delegation check
- `src/main/java/fr/enimaloc/catapult/web/AdminExperimentsController.java` — redirect when `adminUrl()` is set, add `/status` endpoint
- `src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java` — expose active provider info to templates
- `src/main/resources/templates/template.html` — add provider banner
- `src/main/resources/application.properties` — add `app.experiment.*` config block
- `src/main/java/fr/enimaloc/catapult/CatapultApplication.java` — add `@EnableAsync`
- `build.gradle.kts` — add Unleash + GrowthBook SDK dependencies

---

### Task 1: DB migration + SystemSetting entity

**Files:**
- Create: `src/main/resources/db/migration/V25__system_settings.sql`
- Create: `src/main/java/fr/enimaloc/catapult/domain/SystemSetting.java`
- Create: `src/main/java/fr/enimaloc/catapult/repository/SystemSettingRepository.java`

- [ ] **Step 1: Write the migration SQL**

```sql
-- src/main/resources/db/migration/V25__system_settings.sql
CREATE TABLE IF NOT EXISTS system_settings (
    key   VARCHAR(255) NOT NULL PRIMARY KEY,
    value TEXT
);
```

- [ ] **Step 2: Create the entity**

```java
// src/main/java/fr/enimaloc/catapult/domain/SystemSetting.java
package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "system_settings")
@Getter
@Setter
public class SystemSetting {

    @Id
    private String key;

    private String value;
}
```

- [ ] **Step 3: Create the repository**

```java
// src/main/java/fr/enimaloc/catapult/repository/SystemSettingRepository.java
package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V25__system_settings.sql \
        src/main/java/fr/enimaloc/catapult/domain/SystemSetting.java \
        src/main/java/fr/enimaloc/catapult/repository/SystemSettingRepository.java
git commit -m "feat: add system_settings table for provider state tracking"
```

---

### Task 2: ExperimentSummary DTO + ExperimentProvider interface

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentSummary.java`
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentProvider.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/ExperimentProviderContractTest.java`

- [ ] **Step 1: Write a failing test that checks a minimal provider implementation compiles**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/ExperimentProviderContractTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentProviderContractTest {

    static class NoOpProvider implements ExperimentProvider {
        @Override public String type() { return "noop"; }
        @Override public Optional<ExperimentVariant> getVariant(UserAccount u, String key) { return Optional.empty(); }
        @Override public List<ExperimentSummary> listExperiments() { return List.of(); }
        @Override public Optional<String> adminUrl() { return Optional.empty(); }
        @Override public void trackEvent(UserAccount u, String key, String evt) {}
        @Override public void importExperiments(List<Experiment> exps) {}
        @Override public boolean isHealthy() { return true; }
    }

    @Test
    void defaultTrackFeedbackIsNoop() {
        ExperimentProvider provider = new NoOpProvider();
        // must not throw
        provider.trackFeedback(null, "key", 9);
    }

    @Test
    void summaryRecord() {
        ExperimentSummary s = new ExperimentSummary("key", "Name", "ACTIVE", 2);
        assertThat(s.key()).isEqualTo("key");
        assertThat(s.variantCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.ExperimentProviderContractTest" 2>&1 | tail -10
```
Expected: compilation error — `ExperimentProvider` and `ExperimentSummary` not found.

- [ ] **Step 3: Create ExperimentSummary**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentSummary.java
package fr.enimaloc.catapult.experiment.provider;

public record ExperimentSummary(String key, String name, String status, int variantCount) {}
```

- [ ] **Step 4: Create ExperimentProvider interface**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/ExperimentProvider.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;
import java.util.Optional;

public interface ExperimentProvider {

    /** Unique identifier: "internal", "gitlab", "growthbook", "unleash" */
    String type();

    /**
     * Resolves the variant for a user. Never throws — returns empty if unavailable.
     * For external providers, returns a transient (non-persisted) ExperimentVariant.
     */
    Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey);

    List<ExperimentSummary> listExperiments();

    /** URL of the provider's native admin UI. Empty for the internal provider. */
    Optional<String> adminUrl();

    /** Fire-and-forget event forward. Failures are logged but never propagated. */
    void trackEvent(UserAccount user, String experimentKey, String eventKey);

    /** Idempotent import called on provider switch at startup. */
    void importExperiments(List<Experiment> experiments);

    boolean isHealthy();

    default void trackFeedback(UserAccount user, String experimentKey, int npsScore) {}
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.ExperimentProviderContractTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/ \
        src/test/java/fr/enimaloc/catapult/experiment/provider/ExperimentProviderContractTest.java
git commit -m "feat: add ExperimentProvider interface and ExperimentSummary DTO"
```

---

### Task 3: ExperimentProviderProperties + application.properties

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/config/ExperimentProviderProperties.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/fr/enimaloc/catapult/config/ExperimentProviderPropertiesTest.java`

- [ ] **Step 1: Write a failing test**

```java
// src/test/java/fr/enimaloc/catapult/config/ExperimentProviderPropertiesTest.java
package fr.enimaloc.catapult.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentProviderPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(ExperimentProviderProperties.class)
    static class TestConfig {}

    @Test
    void defaultsToInternal() {
        runner.run(ctx -> {
            ExperimentProviderProperties props = ctx.getBean(ExperimentProviderProperties.class);
            assertThat(props.getProvider()).isEqualTo("internal");
            assertThat(props.isFallbackToInternal()).isTrue();
        });
    }

    @Test
    void gitlabPropertiesBind() {
        runner.withPropertyValues(
            "app.experiment.provider=gitlab",
            "app.experiment.gitlab.project-id=123",
            "app.experiment.gitlab.instance-id=abc",
            "app.experiment.gitlab.private-token=tok",
            "app.experiment.gitlab.admin-url=https://gitlab.com/ns/proj/-/feature_flags"
        ).run(ctx -> {
            ExperimentProviderProperties props = ctx.getBean(ExperimentProviderProperties.class);
            assertThat(props.getProvider()).isEqualTo("gitlab");
            assertThat(props.getGitlab().getProjectId()).isEqualTo("123");
            assertThat(props.getGitlab().getAdminUrl()).isEqualTo("https://gitlab.com/ns/proj/-/feature_flags");
        });
    }

    @Test
    void unleashPropertiesBind() {
        runner.withPropertyValues(
            "app.experiment.provider=unleash",
            "app.experiment.unleash.api-url=https://unleash.example.com",
            "app.experiment.unleash.client-key=secret"
        ).run(ctx -> {
            ExperimentProviderProperties props = ctx.getBean(ExperimentProviderProperties.class);
            assertThat(props.getUnleash().getApiUrl()).isEqualTo("https://unleash.example.com");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.config.ExperimentProviderPropertiesTest" 2>&1 | tail -10
```
Expected: compilation error — `ExperimentProviderProperties` not found.

- [ ] **Step 3: Create ExperimentProviderProperties**

```java
// src/main/java/fr/enimaloc/catapult/config/ExperimentProviderProperties.java
package fr.enimaloc.catapult.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.experiment")
@Validated
@Getter
@Setter
public class ExperimentProviderProperties {

    @NotBlank
    private String provider = "internal";
    private boolean fallbackToInternal = true;

    private GitLab gitlab = new GitLab();
    private GrowthBook growthbook = new GrowthBook();
    private Unleash unleash = new Unleash();

    @Getter @Setter
    public static class GitLab {
        private String host = "https://gitlab.com";
        private String projectId;
        private String instanceId;
        private String privateToken;
        private String adminUrl;
    }

    @Getter @Setter
    public static class GrowthBook {
        private String apiHost = "https://app.growthbook.io";
        private String clientKey;
        private String apiKey;
    }

    @Getter @Setter
    public static class Unleash {
        private String apiUrl;
        private String clientKey;
    }
}
```

- [ ] **Step 4: Add to application.properties**

Append the following block to `src/main/resources/application.properties`:

```properties
# ============================================================
# A/B Testing provider (internal | gitlab | growthbook | unleash)
# ============================================================
app.experiment.provider=internal
app.experiment.fallback-to-internal=true

# GitLab Feature Flags (only if app.experiment.provider=gitlab)
# app.experiment.gitlab.host=https://gitlab.com
# app.experiment.gitlab.project-id=${GITLAB_PROJECT_ID}
# app.experiment.gitlab.instance-id=${GITLAB_UNLEASH_INSTANCE_ID}
# app.experiment.gitlab.private-token=${GITLAB_PRIVATE_TOKEN}
# app.experiment.gitlab.admin-url=https://gitlab.com/enimaloc/catapult/-/feature_flags

# GrowthBook (only if app.experiment.provider=growthbook)
# app.experiment.growthbook.api-host=https://app.growthbook.io
# app.experiment.growthbook.client-key=${GROWTHBOOK_CLIENT_KEY}
# app.experiment.growthbook.api-key=${GROWTHBOOK_API_KEY}

# Unleash (only if app.experiment.provider=unleash)
# app.experiment.unleash.api-url=${UNLEASH_API_URL}
# app.experiment.unleash.client-key=${UNLEASH_CLIENT_KEY}
```

- [ ] **Step 5: Register @ConfigurationProperties in CatapultApplication**

Add `@EnableConfigurationProperties(ExperimentProviderProperties.class)` to `CatapultApplication.java`:

```java
// src/main/java/fr/enimaloc/catapult/CatapultApplication.java
package fr.enimaloc.catapult;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(ExperimentProviderProperties.class)
public class CatapultApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatapultApplication.class, args);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.config.ExperimentProviderPropertiesTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/config/ExperimentProviderProperties.java \
        src/main/java/fr/enimaloc/catapult/CatapultApplication.java \
        src/main/resources/application.properties \
        src/test/java/fr/enimaloc/catapult/config/ExperimentProviderPropertiesTest.java
git commit -m "feat: add ExperimentProviderProperties configuration"
```

---

### Task 4: InternalExperimentProvider

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProvider.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProviderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProviderTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalExperimentProviderTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentService experimentService;
    InternalExperimentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InternalExperimentProvider(experimentRepository);
        provider.setExperimentService(experimentService);
    }

    @Test
    void typeIsInternal() {
        assertThat(provider.type()).isEqualTo("internal");
    }

    @Test
    void adminUrlIsEmpty() {
        assertThat(provider.adminUrl()).isEmpty();
    }

    @Test
    void isHealthyAlwaysTrue() {
        assertThat(provider.isHealthy()).isTrue();
    }

    @Test
    void getVariantDelegatesToService() {
        UserAccount user = new UserAccount();
        ExperimentVariant variant = new ExperimentVariant();
        variant.setKey("control");
        when(experimentService.resolveVariantLocally(user, "exp-key")).thenReturn(Optional.of(variant));

        Optional<ExperimentVariant> result = provider.getVariant(user, "exp-key");

        assertThat(result).contains(variant);
        verify(experimentService).resolveVariantLocally(user, "exp-key");
    }

    @Test
    void listExperimentsMapsToDtos() {
        Experiment exp = new Experiment();
        exp.setKey("k");
        exp.setName("N");
        exp.setStatus(Experiment.Status.ACTIVE);
        ExperimentVariant v1 = new ExperimentVariant(); v1.setKey("control");
        ExperimentVariant v2 = new ExperimentVariant(); v2.setKey("b");
        exp.getVariants().addAll(List.of(v1, v2));
        when(experimentRepository.findAll()).thenReturn(List.of(exp));

        List<ExperimentSummary> summaries = provider.listExperiments();

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).key()).isEqualTo("k");
        assertThat(summaries.get(0).status()).isEqualTo("ACTIVE");
        assertThat(summaries.get(0).variantCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.InternalExperimentProviderTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create InternalExperimentProvider**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProvider.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class InternalExperimentProvider implements ExperimentProvider {

    private final ExperimentRepository experimentRepository;

    @Setter
    @Autowired @Lazy
    private ExperimentService experimentService;

    @Override
    public String type() { return "internal"; }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        return experimentService.resolveVariantLocally(user, experimentKey);
    }

    @Override
    public List<ExperimentSummary> listExperiments() {
        return experimentRepository.findAll().stream()
            .map(e -> new ExperimentSummary(e.getKey(), e.getName(), e.getStatus().name(), e.getVariants().size()))
            .toList();
    }

    @Override
    public Optional<String> adminUrl() { return Optional.empty(); }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        // local tracking is handled directly by ExperimentService.track()
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        // experiments are already in the local database
    }

    @Override
    public boolean isHealthy() { return true; }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.InternalExperimentProviderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProvider.java \
        src/test/java/fr/enimaloc/catapult/experiment/provider/InternalExperimentProviderTest.java
git commit -m "feat: add InternalExperimentProvider wrapping existing ExperimentService"
```

---

### Task 5: ActiveProviderHolder + ExperimentProviderConfig

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolder.java`
- Create: `src/main/java/fr/enimaloc/catapult/config/ExperimentProviderConfig.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolderTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class ActiveProviderHolderTest {

    static ExperimentProvider providerOf(String type) {
        return new ExperimentProvider() {
            @Override public String type() { return type; }
            @Override public Optional<ExperimentVariant> getVariant(UserAccount u, String k) { return Optional.empty(); }
            @Override public List<ExperimentSummary> listExperiments() { return List.of(); }
            @Override public Optional<String> adminUrl() { return Optional.empty(); }
            @Override public void trackEvent(UserAccount u, String k, String e) {}
            @Override public void importExperiments(List<Experiment> ex) {}
            @Override public boolean isHealthy() { return true; }
        };
    }

    @Test
    void returnsProviderMatchingConfiguredType() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        props.setProvider("internal");
        ExperimentProvider internal = providerOf("internal");

        ActiveProviderHolder holder = new ActiveProviderHolder(props, List.of(internal));

        assertThat(holder.get()).isSameAs(internal);
    }

    @Test
    void isInternalReturnsTrueWhenInternal() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        props.setProvider("internal");
        ActiveProviderHolder holder = new ActiveProviderHolder(props, List.of(providerOf("internal")));

        assertThat(holder.isInternal()).isTrue();
    }

    @Test
    void isInternalReturnsFalseWhenExternal() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        props.setProvider("gitlab");
        ActiveProviderHolder holder = new ActiveProviderHolder(props, List.of(providerOf("internal"), providerOf("gitlab")));

        assertThat(holder.isInternal()).isFalse();
    }

    @Test
    void throwsWhenProviderTypeNotFound() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        props.setProvider("unknown");
        ActiveProviderHolder holder = new ActiveProviderHolder(props, List.of(providerOf("internal")));

        assertThatThrownBy(holder::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.ActiveProviderHolderTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create ActiveProviderHolder**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolder.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ActiveProviderHolder {

    private final ExperimentProviderProperties properties;
    private final List<ExperimentProvider> providers;

    public ExperimentProvider get() {
        String type = properties.getProvider();
        return providers.stream()
            .filter(p -> p.type().equals(type))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No ExperimentProvider registered for type: " + type));
    }

    public boolean isInternal() {
        return "internal".equals(properties.getProvider());
    }
}
```

- [ ] **Step 4: Create ExperimentProviderConfig** (conditionally creates external provider beans)

```java
// src/main/java/fr/enimaloc/catapult/config/ExperimentProviderConfig.java
package fr.enimaloc.catapult.config;

import fr.enimaloc.catapult.experiment.provider.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExperimentProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.experiment.provider", havingValue = "gitlab")
    public ExperimentProvider gitLabExperimentProvider(ExperimentProviderProperties props) {
        return new GitLabExperimentProvider(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.experiment.provider", havingValue = "growthbook")
    public ExperimentProvider growthBookExperimentProvider(ExperimentProviderProperties props) {
        return new GrowthBookExperimentProvider(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.experiment.provider", havingValue = "unleash")
    public ExperimentProvider unleashExperimentProvider(ExperimentProviderProperties props) {
        return new UnleashExperimentProvider(props);
    }
}
```

Note: `GitLabExperimentProvider`, `GrowthBookExperimentProvider`, and `UnleashExperimentProvider` will be created in Tasks 9–11. The `@ConditionalOnProperty` means only the active provider is instantiated.

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.ActiveProviderHolderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolder.java \
        src/main/java/fr/enimaloc/catapult/config/ExperimentProviderConfig.java \
        src/test/java/fr/enimaloc/catapult/experiment/provider/ActiveProviderHolderTest.java
git commit -m "feat: add ActiveProviderHolder and ExperimentProviderConfig factory"
```

---

### Task 6: Refactor ExperimentService to delegate to external provider

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/ExperimentService.java`
- Test: `src/test/java/fr/enimaloc/catapult/service/ExperimentServiceDelegationTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/fr/enimaloc/catapult/service/ExperimentServiceDelegationTest.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceDelegationTest {

    @Mock ActiveProviderHolder activeProviderHolder;
    @Mock ExperimentProvider externalProvider;
    @InjectMocks ExperimentService experimentService;

    @Test
    void getVariantDelegatesToExternalProviderWhenNotInternal() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        ExperimentVariant variant = new ExperimentVariant();
        variant.setKey("treatment");

        when(activeProviderHolder.isInternal()).thenReturn(false);
        when(activeProviderHolder.get()).thenReturn(externalProvider);
        when(externalProvider.getVariant(user, "my-exp")).thenReturn(Optional.of(variant));

        Optional<ExperimentVariant> result = experimentService.getVariant(user, "my-exp");

        assertThat(result).contains(variant);
        verify(externalProvider).getVariant(user, "my-exp");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.service.ExperimentServiceDelegationTest" 2>&1 | tail -10
```
Expected: compilation error (`activeProviderHolder` not a field).

- [ ] **Step 3: Modify ExperimentService**

Add `activeProviderHolder` field and rename `getVariant` body to `resolveVariantLocally`. Replace the first line of `getVariant` with a delegation check.

The full diff to `ExperimentService.java`:

Replace the existing field block + `getVariant` method:

```java
// Add this field after the existing fields (around line 33):
@Autowired
private ActiveProviderHolder activeProviderHolder;
```

Replace the existing `getVariant` method (lines 42–57) with:

```java
@Transactional
public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
    if (!activeProviderHolder.isInternal()) {
        return activeProviderHolder.get().getVariant(user, experimentKey);
    }
    return resolveVariantLocally(user, experimentKey);
}

@Transactional
Optional<ExperimentVariant> resolveVariantLocally(UserAccount user, String experimentKey) {
    Experiment experiment = experimentRepository.findByKey(experimentKey)
        .filter(e -> e.getStatus() == Experiment.Status.ACTIVE)
        .orElse(null);
    if (experiment == null) return Optional.empty();

    OverrideResult or = firstMatchingOverride(user, experiment);
    if (or.excluded()) return Optional.empty();
    if (or.forcedVariant().isPresent()) return applyForcedVariant(user, experiment, or.forcedVariant().get());

    Optional<ExperimentAssignment> existing = assignmentRepository.findByExperimentAndUser(experiment, user);
    if (existing.isPresent()) return Optional.of(existing.get().getVariant());
    if (!or.skipRules() && !isEligible(user, experiment, experimentKey)) return Optional.empty();

    return Optional.of(persistAssignment(weightedRandom(experiment.getVariants(), user, experimentKey), user, experiment));
}
```

Also add the import at the top of the file:
```java
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.service.ExperimentServiceDelegationTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run all existing tests to confirm no regressions**

```bash
./gradlew :test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/ExperimentService.java \
        src/test/java/fr/enimaloc/catapult/service/ExperimentServiceDelegationTest.java
git commit -m "feat: delegate ExperimentService.getVariant() to active provider"
```

---

### Task 7: ExperimentProviderBootstrap

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrap.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrapTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrapTest.java
package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import fr.enimaloc.catapult.experiment.provider.ExperimentSummary;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentProviderBootstrapTest {

    @Mock ActiveProviderHolder activeProviderHolder;
    @Mock ExperimentProvider externalProvider;
    @Mock ExperimentRepository experimentRepository;
    @Mock SystemSettingRepository settingRepository;
    @Mock ExperimentProviderProperties properties;

    ExperimentProviderBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new ExperimentProviderBootstrap(properties, activeProviderHolder, settingRepository, experimentRepository);
    }

    @Test
    void noSyncWhenProviderUnchanged() throws Exception {
        when(properties.getProvider()).thenReturn("gitlab");
        SystemSetting existing = new SystemSetting();
        existing.setKey("experiment.last-provider");
        existing.setValue("gitlab");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.of(existing));

        bootstrap.run(new DefaultApplicationArguments());

        verify(experimentRepository, never()).findAll();
    }

    @Test
    void syncIsCalledWhenProviderChanges() throws Exception {
        when(properties.getProvider()).thenReturn("gitlab");
        SystemSetting existing = new SystemSetting();
        existing.setKey("experiment.last-provider");
        existing.setValue("internal");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.of(existing));
        when(activeProviderHolder.get()).thenReturn(externalProvider);
        when(activeProviderHolder.isInternal()).thenReturn(false);

        Experiment exp = new Experiment();
        exp.setKey("k");
        exp.setStatus(Experiment.Status.ACTIVE);
        when(experimentRepository.findAll()).thenReturn(List.of(exp));

        bootstrap.run(new DefaultApplicationArguments());

        verify(externalProvider).importExperiments(List.of(exp));
        verify(settingRepository).save(any(SystemSetting.class));
    }

    @Test
    void lastProviderWrittenOnFirstRun() throws Exception {
        when(properties.getProvider()).thenReturn("internal");
        when(settingRepository.findById("experiment.last-provider")).thenReturn(Optional.empty());

        bootstrap.run(new DefaultApplicationArguments());

        verify(settingRepository).save(argThat(s -> "internal".equals(s.getValue())));
        verify(experimentRepository, never()).findAll();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.ExperimentProviderBootstrapTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create ExperimentProviderBootstrap**

```java
// src/main/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrap.java
package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.SystemSetting;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import fr.enimaloc.catapult.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Integer.MAX_VALUE)
public class ExperimentProviderBootstrap implements ApplicationRunner {

    private static final String LAST_PROVIDER_KEY = "experiment.last-provider";

    private final ExperimentProviderProperties properties;
    private final ActiveProviderHolder activeProviderHolder;
    private final SystemSettingRepository settingRepository;
    private final ExperimentRepository experimentRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String currentType = properties.getProvider();
        String lastType = settingRepository.findById(LAST_PROVIDER_KEY)
            .map(SystemSetting::getValue)
            .orElse(null);

        if (currentType.equals(lastType)) {
            log.info("[Provider] Active experiment provider: {}", currentType);
            return;
        }

        if (lastType != null && !activeProviderHolder.isInternal()) {
            log.info("[Provider] Provider changed {} → {}, syncing experiments...", lastType, currentType);
            syncActiveExperiments();
        }

        SystemSetting setting = settingRepository.findById(LAST_PROVIDER_KEY).orElse(new SystemSetting());
        setting.setKey(LAST_PROVIDER_KEY);
        setting.setValue(currentType);
        settingRepository.save(setting);
        log.info("[Provider] Experiment provider registered: {}", currentType);
    }

    private void syncActiveExperiments() {
        ExperimentProvider provider = activeProviderHolder.get();
        List<Experiment> toSync = experimentRepository.findAll().stream()
            .filter(e -> e.getStatus() == Experiment.Status.ACTIVE || e.getStatus() == Experiment.Status.PAUSED)
            .toList();

        int errors = 0;
        for (Experiment exp : toSync) {
            try {
                provider.importExperiments(List.of(exp));
            } catch (Exception e) {
                log.warn("[Provider] Failed to import experiment '{}': {}", exp.getKey(), e.getMessage());
                errors++;
            }
        }
        log.info("[Provider] Sync complete: {}/{} experiments imported", toSync.size() - errors, toSync.size());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.ExperimentProviderBootstrapTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrap.java \
        src/test/java/fr/enimaloc/catapult/experiment/ExperimentProviderBootstrapTest.java
git commit -m "feat: add ExperimentProviderBootstrap for startup sync on provider change"
```

---

### Task 8: Add SDK dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add Unleash and GrowthBook SDK dependencies**

In `build.gradle.kts`, append to the `dependencies` block:

```kotlin
// A/B testing external providers
implementation("io.getunleash:unleash-client-java:9.2.0")
implementation("io.growthbook.sdk:GrowthBook:1.3.5")  // verify latest on https://central.sonatype.com/artifact/io.growthbook.sdk/GrowthBook
```

- [ ] **Step 2: Verify dependencies resolve**

```bash
./gradlew dependencies --configuration compileClasspath 2>&1 | grep -E "unleash|growthbook"
```
Expected: both libraries listed.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "chore: add Unleash and GrowthBook SDK dependencies"
```

---

### Task 9: GitLabExperimentProvider

Uses the Unleash SDK pointed at GitLab's Unleash-compatible endpoint.

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProvider.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProviderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProviderTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.FakeUnleash;
import io.getunleash.variant.Variant;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabExperimentProviderTest {

    private ExperimentProviderProperties buildProps(String adminUrl) {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        props.setProvider("gitlab");
        ExperimentProviderProperties.GitLab gl = new ExperimentProviderProperties.GitLab();
        gl.setProjectId("123");
        gl.setInstanceId("tok");
        gl.setAdminUrl(adminUrl);
        props.setGitlab(gl);
        return props;
    }

    @Test
    void typeIsGitlab() {
        FakeUnleash fakeUnleash = new FakeUnleash();
        GitLabExperimentProvider provider = new GitLabExperimentProvider(buildProps("https://gitlab.com/ns/proj/-/feature_flags"), fakeUnleash);
        assertThat(provider.type()).isEqualTo("gitlab");
    }

    @Test
    void adminUrlFromConfig() {
        FakeUnleash fakeUnleash = new FakeUnleash();
        GitLabExperimentProvider provider = new GitLabExperimentProvider(
            buildProps("https://gitlab.com/ns/proj/-/feature_flags"), fakeUnleash);
        assertThat(provider.adminUrl()).contains("https://gitlab.com/ns/proj/-/feature_flags");
    }

    @Test
    void getVariantReturnsEmptyWhenFeatureDisabled() {
        FakeUnleash fakeUnleash = new FakeUnleash();
        GitLabExperimentProvider provider = new GitLabExperimentProvider(buildProps("https://x"), fakeUnleash);

        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        assertThat(provider.getVariant(user, "disabled-flag")).isEmpty();
    }

    @Test
    void getVariantReturnsVariantWhenEnabled() {
        FakeUnleash fakeUnleash = new FakeUnleash();
        fakeUnleash.enable("my-flag");
        fakeUnleash.setVariant("my-flag", new Variant("treatment", null, true));
        GitLabExperimentProvider provider = new GitLabExperimentProvider(buildProps("https://x"), fakeUnleash);

        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Optional<fr.enimaloc.catapult.domain.ExperimentVariant> result = provider.getVariant(user, "my-flag");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo("treatment");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.GitLabExperimentProviderTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create GitLabExperimentProvider**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProvider.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.DefaultUnleash;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.util.UnleashConfig;
import io.getunleash.variant.Variant;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public class GitLabExperimentProvider implements ExperimentProvider {

    private final ExperimentProviderProperties.GitLab config;
    private final Unleash unleash;

    public GitLabExperimentProvider(ExperimentProviderProperties props) {
        this.config = props.getGitlab();
        UnleashConfig unleashConfig = UnleashConfig.newBuilder()
            .appName("catapult")
            .instanceId(config.getInstanceId())
            .unleashAPI(config.getHost() + "/api/v4/feature_flags/unleash/" + config.getProjectId())
            .build();
        this.unleash = new DefaultUnleash(unleashConfig);
    }

    /** Constructor for testing with a pre-configured Unleash instance. */
    GitLabExperimentProvider(ExperimentProviderProperties props, Unleash unleash) {
        this.config = props.getGitlab();
        this.unleash = unleash;
    }

    @Override
    public String type() { return "gitlab"; }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        try {
            UnleashContext ctx = UnleashContext.newBuilder()
                .userId(user.getId().toString())
                .build();
            Variant variant = unleash.getVariant(experimentKey, ctx);
            if (!variant.isEnabled()) return Optional.empty();
            ExperimentVariant ev = new ExperimentVariant();
            ev.setKey(variant.getName());
            ev.setName(variant.getName());
            ev.setControl("control".equals(variant.getName()));
            return Optional.of(ev);
        } catch (Exception e) {
            log.warn("[GitLab] Failed to resolve variant for '{}': {}", experimentKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ExperimentSummary> listExperiments() {
        return List.of(); // GitLab UI is the source of truth
    }

    @Override
    public Optional<String> adminUrl() {
        return Optional.ofNullable(config.getAdminUrl());
    }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        // GitLab uses Unleash metrics — the SDK handles this automatically
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        log.info("[GitLab] importExperiments: {} experiments — create them in GitLab Feature Flags manually or via the GitLab API.", experiments.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            unleash.isEnabled("__health_check__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.GitLabExperimentProviderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProvider.java \
        src/test/java/fr/enimaloc/catapult/experiment/provider/GitLabExperimentProviderTest.java
git commit -m "feat: add GitLabExperimentProvider using Unleash SDK"
```

---

### Task 10: GrowthBookExperimentProvider

Uses the GrowthBook Java SDK with a REST call to load feature definitions.

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProvider.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProviderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProviderTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GrowthBookExperimentProviderTest {

    private ExperimentProviderProperties buildProps() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        ExperimentProviderProperties.GrowthBook gb = new ExperimentProviderProperties.GrowthBook();
        gb.setApiHost("https://app.growthbook.io");
        gb.setClientKey("sdk-test");
        gb.setApiKey("secret");
        props.setGrowthbook(gb);
        return props;
    }

    @Test
    void typeIsGrowthbook() {
        RestClient.Builder builder = RestClient.builder();
        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        assertThat(provider.type()).isEqualTo("growthbook");
    }

    @Test
    void adminUrlFromConfig() {
        RestClient.Builder builder = RestClient.builder();
        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        assertThat(provider.adminUrl()).contains("https://app.growthbook.io");
    }

    @Test
    void getVariantReturnsEmptyOnEmptyFeatureSet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://app.growthbook.io/api/features/sdk-test"))
            .andRespond(withSuccess("{\"status\":200,\"features\":{}}", MediaType.APPLICATION_JSON));

        GrowthBookExperimentProvider provider = new GrowthBookExperimentProvider(buildProps(), builder.build());
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Optional<fr.enimaloc.catapult.domain.ExperimentVariant> result = provider.getVariant(user, "missing-exp");
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.GrowthBookExperimentProviderTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create GrowthBookExperimentProvider**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProvider.java
package fr.enimaloc.catapult.experiment.provider;

import com.sdk.growthbook.GBContext;
import com.sdk.growthbook.GrowthBook;
import com.sdk.growthbook.model.GBExperiment;
import com.sdk.growthbook.model.GBExperimentResult;
import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class GrowthBookExperimentProvider implements ExperimentProvider {

    private final ExperimentProviderProperties.GrowthBook config;
    private final RestClient restClient;

    public GrowthBookExperimentProvider(ExperimentProviderProperties props) {
        this.config = props.getGrowthbook();
        this.restClient = RestClient.builder()
            .baseUrl(config.getApiHost())
            .defaultHeader("Authorization", "Bearer " + config.getApiKey())
            .build();
    }

    GrowthBookExperimentProvider(ExperimentProviderProperties props, RestClient restClient) {
        this.config = props.getGrowthbook();
        this.restClient = restClient;
    }

    @Override
    public String type() { return "growthbook"; }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        try {
            String featuresJson = restClient.get()
                .uri("/api/features/{key}", config.getClientKey())
                .retrieve()
                .body(String.class);

            Map<String, Object> attributes = buildAttributes(user);
            GBContext context = GBContext.builder()
                .featuresJson(featuresJson)
                .attributes(attributes)
                .build();
            GrowthBook gb = new GrowthBook(context);

            GBExperiment<String> exp = new GBExperiment<>(experimentKey);
            GBExperimentResult<String> result = gb.run(exp);

            if (!result.getInExperiment()) return Optional.empty();

            String variantValue = result.getValue();
            ExperimentVariant ev = new ExperimentVariant();
            ev.setKey(variantValue != null ? variantValue : "control");
            ev.setName(ev.getKey());
            ev.setControl("control".equals(ev.getKey()));
            return Optional.of(ev);
        } catch (Exception e) {
            log.warn("[GrowthBook] Failed to resolve variant for '{}': {}", experimentKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ExperimentSummary> listExperiments() {
        return List.of();
    }

    @Override
    public Optional<String> adminUrl() {
        return Optional.ofNullable(config.getApiHost());
    }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        log.debug("[GrowthBook] trackEvent: user={} experiment={} event={}", user.getId(), experimentKey, eventKey);
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        log.info("[GrowthBook] importExperiments: {} experiments — create them in GrowthBook manually.", experiments.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            restClient.get().uri("/api/features/{key}", config.getClientKey()).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> buildAttributes(UserAccount user) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("id", user.getId().toString());
        attrs.put("account_age_days",
            (System.currentTimeMillis() - user.getCreatedAt().toEpochMilli()) / 86_400_000.0);
        attrs.put("has_steam", user.getSteamId() != null ? 1 : 0);
        attrs.put("has_xbox", 0);
        attrs.put("has_battlenet", 0);
        return attrs;
    }
}
```

**Note:** The GrowthBook Java SDK import paths (`com.sdk.growthbook.*`) depend on the actual SDK version. Verify the correct package names from the SDK at https://central.sonatype.com/artifact/io.growthbook.sdk/GrowthBook. If the API differs, adjust accordingly.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.GrowthBookExperimentProviderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProvider.java \
        src/test/java/fr/enimaloc/catapult/experiment/provider/GrowthBookExperimentProviderTest.java
git commit -m "feat: add GrowthBookExperimentProvider"
```

---

### Task 11: UnleashExperimentProvider

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProvider.java`
- Test: `src/test/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProviderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProviderTest.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.FakeUnleash;
import io.getunleash.variant.Variant;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnleashExperimentProviderTest {

    private ExperimentProviderProperties buildProps() {
        ExperimentProviderProperties props = new ExperimentProviderProperties();
        ExperimentProviderProperties.Unleash ul = new ExperimentProviderProperties.Unleash();
        ul.setApiUrl("https://unleash.example.com");
        ul.setClientKey("secret");
        props.setUnleash(ul);
        return props;
    }

    @Test
    void typeIsUnleash() {
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), new FakeUnleash());
        assertThat(provider.type()).isEqualTo("unleash");
    }

    @Test
    void adminUrlFromConfig() {
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), new FakeUnleash());
        assertThat(provider.adminUrl()).contains("https://unleash.example.com");
    }

    @Test
    void getVariantReturnsEmptyWhenDisabled() {
        FakeUnleash fake = new FakeUnleash();
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), fake);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        assertThat(provider.getVariant(user, "disabled-toggle")).isEmpty();
    }

    @Test
    void getVariantReturnsMappedVariant() {
        FakeUnleash fake = new FakeUnleash();
        fake.enable("my-toggle");
        fake.setVariant("my-toggle", new Variant("b", null, true));
        UnleashExperimentProvider provider = new UnleashExperimentProvider(buildProps(), fake);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Optional<fr.enimaloc.catapult.domain.ExperimentVariant> result = provider.getVariant(user, "my-toggle");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo("b");
        assertThat(result.get().isControl()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.UnleashExperimentProviderTest" 2>&1 | tail -10
```
Expected: compilation error.

- [ ] **Step 3: Create UnleashExperimentProvider**

```java
// src/main/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProvider.java
package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import fr.enimaloc.catapult.domain.Experiment;
import fr.enimaloc.catapult.domain.ExperimentVariant;
import fr.enimaloc.catapult.domain.UserAccount;
import io.getunleash.DefaultUnleash;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.util.UnleashConfig;
import io.getunleash.variant.Variant;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
public class UnleashExperimentProvider implements ExperimentProvider {

    private final ExperimentProviderProperties.Unleash config;
    private final Unleash unleash;

    public UnleashExperimentProvider(ExperimentProviderProperties props) {
        this.config = props.getUnleash();
        UnleashConfig unleashConfig = UnleashConfig.newBuilder()
            .appName("catapult")
            .unleashAPI(config.getApiUrl())
            .customHttpHeader("Authorization", config.getClientKey())
            .build();
        this.unleash = new DefaultUnleash(unleashConfig);
    }

    UnleashExperimentProvider(ExperimentProviderProperties props, Unleash unleash) {
        this.config = props.getUnleash();
        this.unleash = unleash;
    }

    @Override
    public String type() { return "unleash"; }

    @Override
    public Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey) {
        try {
            UnleashContext ctx = UnleashContext.newBuilder()
                .userId(user.getId().toString())
                .build();
            Variant variant = unleash.getVariant(experimentKey, ctx);
            if (!variant.isEnabled()) return Optional.empty();
            ExperimentVariant ev = new ExperimentVariant();
            ev.setKey(variant.getName());
            ev.setName(variant.getName());
            ev.setControl("control".equals(variant.getName()));
            return Optional.of(ev);
        } catch (Exception e) {
            log.warn("[Unleash] Failed to resolve variant for '{}': {}", experimentKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ExperimentSummary> listExperiments() { return List.of(); }

    @Override
    public Optional<String> adminUrl() {
        return Optional.ofNullable(config.getApiUrl());
    }

    @Override
    public void trackEvent(UserAccount user, String experimentKey, String eventKey) {
        // Unleash SDK handles metrics automatically
    }

    @Override
    public void importExperiments(List<Experiment> experiments) {
        log.info("[Unleash] importExperiments: {} experiments — create them in Unleash manually.", experiments.size());
    }

    @Override
    public boolean isHealthy() {
        try {
            unleash.isEnabled("__health_check__");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.experiment.provider.UnleashExperimentProviderTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProvider.java \
        src/test/java/fr/enimaloc/catapult/experiment/provider/UnleashExperimentProviderTest.java
git commit -m "feat: add UnleashExperimentProvider"
```

---

### Task 12: Async event mirroring in ExperimentService.track()

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/ExperimentService.java`
- Test: `src/test/java/fr/enimaloc/catapult/service/ExperimentServiceTrackMirrorTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/fr/enimaloc/catapult/service/ExperimentServiceTrackMirrorTest.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import fr.enimaloc.catapult.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTrackMirrorTest {

    @Mock ExperimentRepository experimentRepository;
    @Mock ExperimentAssignmentRepository assignmentRepository;
    @Mock ExperimentEventRepository eventRepository;
    @Mock ExperimentOverrideRepository overrideRepository;
    @Mock ActiveProviderHolder activeProviderHolder;
    @Mock ExperimentProvider externalProvider;
    @InjectMocks ExperimentService experimentService;

    @Test
    void trackForwardsToProviderWhenNotInternal() {
        Experiment exp = new Experiment();
        exp.setKey("exp");
        exp.setStatus(Experiment.Status.ACTIVE);
        ExperimentVariant variant = new ExperimentVariant();
        variant.setKey("control");
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setVariant(variant);

        when(experimentRepository.findByKey("exp")).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, null)).thenReturn(Optional.of(assignment));
        when(activeProviderHolder.isInternal()).thenReturn(false);
        when(activeProviderHolder.get()).thenReturn(externalProvider);

        experimentService.track(null, "exp", "click");

        verify(eventRepository).save(any(ExperimentEvent.class));
        verify(externalProvider).trackEvent(null, "exp", "click");
    }

    @Test
    void trackSkipsProviderForwardWhenInternal() {
        Experiment exp = new Experiment();
        exp.setKey("exp");
        exp.setStatus(Experiment.Status.ACTIVE);
        ExperimentVariant variant = new ExperimentVariant();
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setVariant(variant);

        when(experimentRepository.findByKey("exp")).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(exp, null)).thenReturn(Optional.of(assignment));
        when(activeProviderHolder.isInternal()).thenReturn(true);

        experimentService.track(null, "exp", "click");

        verify(eventRepository).save(any(ExperimentEvent.class));
        verifyNoInteractions(externalProvider);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.service.ExperimentServiceTrackMirrorTest" 2>&1 | tail -10
```
Expected: test fails (forward call not in track() yet).

- [ ] **Step 3: Modify ExperimentService.track()**

Replace the existing `track` method body in `ExperimentService.java`:

```java
@Transactional
public void track(UserAccount user, String experimentKey, String eventKey) {
    Optional<Experiment> opt = experimentRepository.findByKey(experimentKey);
    if (opt.isEmpty()) return;
    Experiment experiment = opt.get();
    if (experiment.getStatus() != Experiment.Status.ACTIVE) return;

    assignmentRepository.findByExperimentAndUser(experiment, user).ifPresent(assignment -> {
        ExperimentEvent event = new ExperimentEvent();
        event.setExperiment(experiment);
        event.setVariant(assignment.getVariant());
        event.setUser(user);
        event.setEventKey(eventKey);
        eventRepository.save(event);
    });

    if (!activeProviderHolder.isInternal()) {
        forwardTrackEvent(user, experimentKey, eventKey);
    }
}

@org.springframework.scheduling.annotation.Async
protected void forwardTrackEvent(UserAccount user, String experimentKey, String eventKey) {
    try {
        activeProviderHolder.get().trackEvent(user, experimentKey, eventKey);
    } catch (Exception e) {
        log.warn("[Provider] Failed to forward track event '{}' for experiment '{}': {}", eventKey, experimentKey, e.getMessage());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.service.ExperimentServiceTrackMirrorTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/ExperimentService.java \
        src/test/java/fr/enimaloc/catapult/service/ExperimentServiceTrackMirrorTest.java
git commit -m "feat: mirror track() events to active provider asynchronously"
```

---

### Task 13: AdminExperimentsController redirect + status endpoint

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/web/AdminExperimentsController.java`
- Modify: `src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java`
- Test: `src/test/java/fr/enimaloc/catapult/web/AdminExperimentsProviderRedirectTest.java`

- [ ] **Step 1: Write failing test**

```java
// src/test/java/fr/enimaloc/catapult/web/AdminExperimentsProviderRedirectTest.java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminExperimentsProviderRedirectTest {

    @Mock ActiveProviderHolder activeProviderHolder;
    @Mock ExperimentProvider externalProvider;
    @InjectMocks AdminExperimentsController controller;

    @Test
    void listRedirectsToProviderAdminUrlWhenExternal() {
        when(activeProviderHolder.get()).thenReturn(externalProvider);
        when(externalProvider.adminUrl()).thenReturn(Optional.of("https://gitlab.com/ns/proj/-/feature_flags"));

        String result = controller.list(null, new ExtendedModelMap());

        assertThat(result).isEqualTo("redirect:https://gitlab.com/ns/proj/-/feature_flags");
    }

    @Test
    void listShowsInternalViewWhenNoAdminUrl() {
        when(activeProviderHolder.get()).thenReturn(externalProvider);
        when(externalProvider.adminUrl()).thenReturn(Optional.empty());

        String result = controller.list(null, new ExtendedModelMap());

        assertThat(result).isEqualTo("admin/experiments");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.web.AdminExperimentsProviderRedirectTest" 2>&1 | tail -10
```
Expected: compilation error (no `activeProviderHolder` in controller).

- [ ] **Step 3: Modify AdminExperimentsController**

Add `activeProviderHolder` to the fields and update `list()`. The field list currently ends at line 39 (`ApplicationEventPublisher`). Add:

```java
private final ActiveProviderHolder activeProviderHolder;
```

Replace the existing `list()` method:

```java
@GetMapping
public String list(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
    return activeProviderHolder.get().adminUrl()
        .map(url -> "redirect:" + url)
        .orElseGet(() -> {
            model.addAttribute("experiments", experimentRepository.findAll());
            return "admin/experiments";
        });
}
```

Add a new status endpoint (JSON, no authentication beyond Spring Security's existing config):

```java
@GetMapping("/status")
@ResponseBody
public Map<String, Object> status() {
    ExperimentProvider provider = activeProviderHolder.get();
    return Map.of(
        "provider", provider.type(),
        "healthy", provider.isHealthy()
    );
}
```

Also add the required imports:
```java
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
import org.springframework.web.bind.annotation.ResponseBody;
```

- [ ] **Step 4: Add provider info to GlobalModelAdvice**

Add `activeProviderHolder` field and a new `@ModelAttribute` method to `GlobalModelAdvice.java`:

Add field (after existing fields):
```java
private final ActiveProviderHolder activeProviderHolder;
```

Add method:
```java
@ModelAttribute
public void addExperimentProviderInfo(Model model) {
    ExperimentProvider provider = activeProviderHolder.get();
    provider.adminUrl().ifPresent(url -> {
        model.addAttribute("experimentProviderType", provider.type());
        model.addAttribute("experimentProviderAdminUrl", url);
    });
}
```

Add imports:
```java
import fr.enimaloc.catapult.experiment.provider.ActiveProviderHolder;
import fr.enimaloc.catapult.experiment.provider.ExperimentProvider;
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :test --tests "fr.enimaloc.catapult.web.AdminExperimentsProviderRedirectTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/web/AdminExperimentsController.java \
        src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java \
        src/test/java/fr/enimaloc/catapult/web/AdminExperimentsProviderRedirectTest.java
git commit -m "feat: redirect admin experiments to provider UI when external"
```

---

### Task 14: Provider banner in template

**Files:**
- Modify: `src/main/resources/templates/template.html`
- Modify: `src/main/resources/lang/messages.properties`
- Modify: `src/main/resources/lang/messages_fr.properties`

- [ ] **Step 1: Add i18n keys to messages.properties**

Append to `src/main/resources/lang/messages.properties`:

```properties
# ---- experiment provider -------------------------------------------------------
experiment.provider.banner=Experiments managed via {0}
experiment.provider.banner.open=Open ↗
```

Append to `src/main/resources/lang/messages_fr.properties`:

```properties
# ---- experiment provider -------------------------------------------------------
experiment.provider.banner=Expériences gérées via {0}
experiment.provider.banner.open=Ouvrir ↗
```

- [ ] **Step 2: Add provider banner to template.html**

The existing banners live between lines 26 and 44. Add a new banner immediately after the impersonation banner (after line 44, before `<nav>`):

```html
<div th:if="${experimentProviderAdminUrl != null}"
     class="banner banner-info"
     sec:authorize="hasRole('ADMIN')">
    <span th:text="#{experiment.provider.banner(${experimentProviderType})}">Experiments managed via external</span>
    <a th:href="${experimentProviderAdminUrl}" target="_blank" rel="noopener"
       th:text="#{experiment.provider.banner.open}">Open ↗</a>
</div>
```

- [ ] **Step 3: Run all tests to confirm no regressions**

```bash
./gradlew :test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/template.html \
        src/main/resources/lang/messages.properties \
        src/main/resources/lang/messages_fr.properties
git commit -m "feat: show active experiment provider banner in admin UI"
```

---

## Self-Review

**Spec coverage check:**
- ✅ ExperimentProvider interface (Task 2)
- ✅ ExperimentSummary DTO (Task 2)
- ✅ InternalExperimentProvider wrapping ExperimentService (Task 4)
- ✅ GitLabExperimentProvider using Unleash SDK (Task 9)
- ✅ GrowthBookExperimentProvider (Task 10)
- ✅ UnleashExperimentProvider (Task 11)
- ✅ ExperimentProviderProperties @ConfigurationProperties (Task 3)
- ✅ ExperimentProviderBootstrap sync at startup on provider change (Task 7)
- ✅ ActiveProviderHolder (Task 5)
- ✅ ExperimentService delegation via `resolveVariantLocally` (Task 6)
- ✅ Async event mirroring in track() (Task 12)
- ✅ AdminExperimentsController redirect when adminUrl present (Task 13)
- ✅ /admin/experiments/status endpoint (Task 13)
- ✅ Provider banner in template (Task 14)
- ✅ V25 migration for system_settings (Task 1)
- ✅ @EnableAsync on CatapultApplication (Task 3, step 5)
- ✅ SDK dependencies in build.gradle.kts (Task 8)

**Known limitation (documented):** `ExperimentService.isRolledOut()` and `getActiveAssignments()` use local DB assignments, which won't reflect external provider state. The `exp:if-rolled-out` Thymeleaf attribute may show incorrect results when using an external provider. This is acceptable for a first version.

**Type consistency:** `resolveVariantLocally` is defined and called consistently in Tasks 4 and 6. `ActiveProviderHolder.isInternal()` is used consistently in Tasks 5, 6, 7, and 12.
