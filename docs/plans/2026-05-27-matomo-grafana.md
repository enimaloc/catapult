# Matomo & Grafana Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Matomo web analytics (page views + custom events) and Prometheus/Grafana monitoring (JVM, HTTP, business metrics) to the Catapult Spring Boot application.

**Architecture:** Matomo is frontend-only — a conditional JS snippet in `template.html` reads config from Thymeleaf, and `catapultMatomo.js` fires events on form submits, HTMX requests, and page load. Grafana uses Micrometer's Prometheus registry exposed on the management port (8081), with counters/gauges injected into existing services via `MeterRegistry`.

**Tech Stack:** Spring Boot 4 / Micrometer `micrometer-registry-prometheus` / Thymeleaf `th:inline="javascript"` / Matomo JS API / HTMX event lifecycle

---

## File Map

### Created
| File | Responsibility |
|---|---|
| `src/main/java/fr/enimaloc/catapult/config/MatomoProperties.java` | `@ConfigurationProperties` for Matomo URL, site ID, enabled flag |
| `src/main/resources/static/js/catapultMatomo.js` | JS helper: page-view tracking, form/HTMX event delegation, experiment assignment tracking |
| `docs/prometheus/catapult-scrape.yml` | Example Prometheus scrape config |
| `docs/grafana/catapult-dashboard.json` | Importable Grafana dashboard |
| `src/test/java/fr/enimaloc/catapult/config/MatomoPropertiesTest.java` | Unit tests for config + GlobalModelAdvice integration |
| `src/test/java/fr/enimaloc/catapult/monitoring/PrometheusEndpointTest.java` | Verifies Prometheus registry is active |

### Modified
| File | What changes |
|---|---|
| `build.gradle.kts` | Add `micrometer-registry-prometheus` dependency |
| `src/main/resources/application.properties` | Matomo + Grafana management properties |
| `src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java` | Inject `MatomoProperties`, expose as `matomo` model attribute |
| `src/main/resources/templates/template.html` | Conditional Matomo snippet + experiment tracking spans |
| `src/main/java/fr/enimaloc/catapult/repository/GameBindingRepository.java` | Add `countByIgnoredFalse()` |
| `src/main/java/fr/enimaloc/catapult/service/BindingService.java` | Inject `MeterRegistry`, counter on create/delete, gauge on active count |
| `src/main/java/fr/enimaloc/catapult/service/ConnectionEventService.java` | Counter on `onSteamLinked` |
| `src/main/java/fr/enimaloc/catapult/service/ExperimentService.java` | Counter in `persistAssignment` |
| `src/main/java/fr/enimaloc/catapult/web/ChannelController.java` | Counter on `searchGames` |
| `src/main/resources/templates/fragments/bindings.html` | `data-matomo-*` on delete + edit-save forms |
| `src/main/resources/templates/fragments/connections.html` | `data-matomo-*` on connect link + disconnect form |
| `src/main/resources/templates/fragments/no-game-settings.html` | `data-matomo-*` on settings form |
| `src/main/resources/templates/fragments/incomplete-fallback-settings.html` | `data-matomo-*` on settings form |
| `src/test/java/fr/enimaloc/catapult/web/GlobalModelAdviceTest.java` | Update constructor calls + add Matomo assertions |
| `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java` | Add `@MockitoBean MeterRegistry` |
| `src/test/java/fr/enimaloc/catapult/service/BindingServiceTest.java` | Add counter assertions |
| `docker-compose.yml` | Add `MATOMO_URL`, `MATOMO_SITE_ID`, `ENVIRONMENT` env vars |

---

## Task 1 — Prometheus dependency + management configuration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/fr/enimaloc/catapult/monitoring/PrometheusEndpointTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/enimaloc/catapult/monitoring/PrometheusEndpointTest.java`:

```java
package fr.enimaloc.catapult.monitoring;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PrometheusEndpointTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void meterRegistry_isPrometheusRegistry() {
        assertThat(meterRegistry).isInstanceOf(PrometheusMeterRegistry.class);
    }
}
```

- [ ] **Step 2: Run the test — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.monitoring.PrometheusEndpointTest"
```

Expected: compilation error — `PrometheusMeterRegistry` not on classpath, or test fails because a `SimpleMeterRegistry` is injected.

- [ ] **Step 3: Add Micrometer Prometheus dependency**

In `build.gradle.kts`, add inside `dependencies { ... }` after the existing `spring-boot-starter-actuator` line:

```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
```

- [ ] **Step 4: Add management + metrics properties**

In `src/main/resources/application.properties`, add at the end:

```properties
# ============================================================
# Monitoring — Prometheus / Grafana
# ============================================================
management.server.port=8081
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.prometheus.enabled=true
management.metrics.tags.application=catapult
management.metrics.tags.environment=${ENVIRONMENT:production}
```

- [ ] **Step 5: Run the test — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.monitoring.PrometheusEndpointTest"
```

Expected: PASS — `PrometheusMeterRegistry` is in the Spring context.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/resources/application.properties \
        src/test/java/fr/enimaloc/catapult/monitoring/PrometheusEndpointTest.java
git commit -m "feat: add Micrometer Prometheus registry and management endpoint"
```

---

## Task 2 — BindingService metrics (counter + gauge)

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/repository/GameBindingRepository.java`
- Modify: `src/main/java/fr/enimaloc/catapult/service/BindingService.java`
- Modify: `src/test/java/fr/enimaloc/catapult/service/BindingServiceTest.java`

- [ ] **Step 1: Add `countByIgnoredFalse()` to GameBindingRepository**

Open `src/main/java/fr/enimaloc/catapult/repository/GameBindingRepository.java` and add after the last existing method declaration:

```java
long countByIgnoredFalse();
```

Spring Data JPA derives this query automatically — no implementation needed.

- [ ] **Step 2: Write the failing tests for BindingService metrics**

In `src/test/java/fr/enimaloc/catapult/service/BindingServiceTest.java`:

Add the following imports at the top:
```java
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

Remove `@InjectMocks private BindingService bindingService;` and replace the class-level `@Mock` + `@InjectMocks` setup with a manual construction in `@BeforeEach`. Replace the existing `@BeforeEach void setup()` entirely:

```java
private SimpleMeterRegistry meterRegistry;

@BeforeEach
void setup() {
    meterRegistry = new SimpleMeterRegistry();
    bindingService = new BindingService(gameBindingRepository, igdbService, twitchService, meterRegistry);

    user = new UserAccount();
    bindingId = UUID.randomUUID();

    binding = new GameBinding();
    binding.setUser(user);
    binding.setStatus(GameBinding.Status.AUTO);
    binding.setTwitchGameId("old-game-id");
    binding.setTwitchGameName("Old Game");
    binding.getCcls().add("ViolentGraphic");
    binding.getCcls().add("Gambling");

    when(gameBindingRepository.findById(bindingId)).thenReturn(Optional.of(binding));
    when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(gameBindingRepository.countByIgnoredFalse()).thenReturn(3L);
}
```

Add these two new test methods at the end of the class:

```java
@Test
void deleteBinding_incrementsDeletedCounter() {
    bindingService.deleteBinding(bindingId);

    assertThat(meterRegistry.counter("catapult.bindings.deleted").count()).isEqualTo(1.0);
}

@Test
void gauge_exposesActiveBindingCount() {
    double gaugeValue = meterRegistry.get("catapult.bindings.active").gauge().value();

    assertThat(gaugeValue).isEqualTo(3.0);
}
```

- [ ] **Step 3: Run the tests — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.BindingServiceTest"
```

Expected: compilation error — `BindingService` constructor does not yet accept `MeterRegistry`.

- [ ] **Step 4: Modify BindingService**

In `src/main/java/fr/enimaloc/catapult/service/BindingService.java`:

Add imports:
```java
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
```

Add the field (after the existing final fields):
```java
private final MeterRegistry meterRegistry;
```

Add a `@PostConstruct` method after the field declarations:
```java
@PostConstruct
private void registerGauges() {
    Gauge.builder("catapult.bindings.active", gameBindingRepository, GameBindingRepository::countByIgnoredFalse)
            .description("Non-ignored game bindings")
            .register(meterRegistry);
}
```

In `deleteBinding`, add the counter increment before `deleteById`:
```java
@Transactional
public void deleteBinding(UUID bindingId) {
    meterRegistry.counter("catapult.bindings.deleted").increment();
    gameBindingRepository.deleteById(bindingId);
}
```

- [ ] **Step 5: Run the tests — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.BindingServiceTest"
```

Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/repository/GameBindingRepository.java \
        src/main/java/fr/enimaloc/catapult/service/BindingService.java \
        src/test/java/fr/enimaloc/catapult/service/BindingServiceTest.java
git commit -m "feat: add Prometheus counters and gauge to BindingService"
```

---

## Task 3 — ConnectionEventService metric (platform connect counter)

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/ConnectionEventService.java`

`ConnectionEventService` currently listens to `SteamLinkedEvent`. Adding a `MeterRegistry` counter there covers the Steam connect event. For disconnect, it happens in `ChannelController.disconnectProvider()` which calls `AccountService` — tracking disconnect is left to a future iteration to avoid touching too many files.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/fr/enimaloc/catapult/service/ConnectionEventServiceTest.java`:

```java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionEventServiceTest {

    private SimpleMeterRegistry meterRegistry;
    private ConnectionEventService service;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ConnectionEventService(meterRegistry);
    }

    @Test
    void onSteamLinked_incrementsConnectionCounter() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        service.onSteamLinked(new SteamLinkedEvent(this, user));

        assertThat(meterRegistry.counter("catapult.connections.total", "platform", "STEAM").count())
                .isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run the test — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ConnectionEventServiceTest"
```

Expected: compilation error — `ConnectionEventService` constructor does not accept `MeterRegistry`.

- [ ] **Step 3: Modify ConnectionEventService**

In `src/main/java/fr/enimaloc/catapult/service/ConnectionEventService.java`:

Add imports:
```java
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
```

Add `@RequiredArgsConstructor` to the class (it currently has none) and add the field:
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionEventService {

    private final MeterRegistry meterRegistry;
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
```

In `onSteamLinked`, add the counter increment at the start of the method:
```java
@EventListener
public void onSteamLinked(SteamLinkedEvent event) {
    meterRegistry.counter("catapult.connections.total", "platform", "STEAM").increment();
    // ... rest of existing code unchanged
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ConnectionEventServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/ConnectionEventService.java \
        src/test/java/fr/enimaloc/catapult/service/ConnectionEventServiceTest.java
git commit -m "feat: add Prometheus connection counter to ConnectionEventService"
```

---

## Task 4 — ExperimentService metric + game search metric

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/service/ExperimentService.java`
- Modify: `src/main/java/fr/enimaloc/catapult/web/ChannelController.java`
- Modify: `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java`

### 4a — ExperimentService

- [ ] **Step 1: Write the failing test for ExperimentService**

Create `src/test/java/fr/enimaloc/catapult/service/ExperimentAssignmentMetricsTest.java`:

```java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExperimentAssignmentMetricsTest {

    @Mock private ExperimentRepository experimentRepository;
    @Mock private ExperimentAssignmentRepository assignmentRepository;
    @Mock private ExperimentEventRepository eventRepository;
    @Mock private ExperimentOverrideRepository overrideRepository;

    private SimpleMeterRegistry meterRegistry;
    private ExperimentService service;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ExperimentService(experimentRepository, assignmentRepository,
                eventRepository, overrideRepository, meterRegistry);
    }

    @Test
    void getVariant_newAssignment_incrementsCounter() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        Experiment exp = new Experiment();
        exp.setKey("dark-mode");
        exp.setStatus(Experiment.Status.ACTIVE);
        exp.setRolloutPercentage(100);

        ExperimentVariant control = new ExperimentVariant();
        control.setKey("control");
        control.setControl(true);
        control.setWeight(50);
        control.setInternalId(0);
        control.setExperiment(exp);

        ExperimentVariant variant = new ExperimentVariant();
        variant.setKey("enabled");
        variant.setControl(false);
        variant.setWeight(50);
        variant.setInternalId(1);
        variant.setExperiment(exp);

        exp.getVariants().addAll(List.of(control, variant));

        when(experimentRepository.findByKey("dark-mode")).thenReturn(Optional.of(exp));
        when(assignmentRepository.findByExperimentAndUser(any(), any())).thenReturn(Optional.empty());
        when(overrideRepository.findByExperimentOrderByPriorityAsc(any())).thenReturn(List.of());
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getVariant(user, "dark-mode");

        double total = meterRegistry.find("catapult.experiments.assignments")
                .tag("experiment", "dark-mode")
                .counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
        assertThat(total).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run the test — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ExperimentAssignmentMetricsTest"
```

Expected: compilation error — `ExperimentService` constructor does not accept `MeterRegistry`.

- [ ] **Step 3: Modify ExperimentService**

Add import:
```java
import io.micrometer.core.instrument.MeterRegistry;
```

Add the field after `private final ExperimentOverrideRepository overrideRepository;`:
```java
private final MeterRegistry meterRegistry;
```

In `persistAssignment`, add the counter increment after `assignmentRepository.save(assignment)`:
```java
private ExperimentVariant persistAssignment(ExperimentVariant variant, UserAccount user, Experiment experiment) {
    ExperimentAssignment assignment = new ExperimentAssignment();
    assignment.setExperiment(experiment);
    assignment.setVariant(variant);
    assignment.setUser(user);
    assignmentRepository.save(assignment);
    meterRegistry.counter("catapult.experiments.assignments",
            "experiment", experiment.getKey(), "variant", variant.getKey()).increment();
    log.debug("Assigned user {} to variant '{}' in experiment '{}'",
            user.getId(), variant.getKey(), experiment.getKey());
    return variant;
}
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ExperimentAssignmentMetricsTest"
```

- [ ] **Step 5: Run full test suite to detect regressions in ExperimentService**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.*" --tests "fr.enimaloc.catapult.web.AdminExperimentsControllerTest" --tests "fr.enimaloc.catapult.experiment.*"
```

Expected: all PASS. If a test constructs `ExperimentService` directly, update its constructor call to pass `new SimpleMeterRegistry()`.

### 4b — Game search counter in ChannelController

- [ ] **Step 6: Add `@MockitoBean MeterRegistry` to ChannelControllerTest**

In `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java`, add after the last `@MockitoBean` line:

```java
@MockitoBean io.micrometer.core.instrument.MeterRegistry meterRegistry;
```

- [ ] **Step 7: Run ChannelControllerTest — expect PASS (still)**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.ChannelControllerTest"
```

Expected: PASS (context loads fine with mocked MeterRegistry).

- [ ] **Step 8: Add MeterRegistry to ChannelController**

In `src/main/java/fr/enimaloc/catapult/web/ChannelController.java`:

Add import:
```java
import io.micrometer.core.instrument.MeterRegistry;
```

Add field (after other `final` fields, before any `@Value` fields):
```java
private final MeterRegistry meterRegistry;
```

In `searchGames` method, add counter increment before `return`:
```java
@GetMapping(value = "/channels/{username}/api/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
@ResponseBody
public List<TwitchCategory> searchGames(
        @PathVariable String username,
        @AuthenticationPrincipal CatapultOAuth2User principal,
        @RequestParam String q) {
    resolveAndCheck(username, principal);
    if (q.isBlank()) return List.of();
    meterRegistry.counter("catapult.game.searches").increment();
    return twitchService.searchCategories(principal.getUserAccount(), q);
}
```

- [ ] **Step 9: Run all web tests to confirm no regression**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.*"
```

Expected: all PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/service/ExperimentService.java \
        src/main/java/fr/enimaloc/catapult/web/ChannelController.java \
        src/test/java/fr/enimaloc/catapult/service/ExperimentAssignmentMetricsTest.java \
        src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java
git commit -m "feat: add Prometheus counters to ExperimentService and ChannelController"
```

---

## Task 5 — Infrastructure docs + docker-compose

**Files:**
- Create: `docs/prometheus/catapult-scrape.yml`
- Create: `docs/grafana/catapult-dashboard.json`
- Modify: `docker-compose.yml`

No tests for this task — pure configuration and documentation.

- [ ] **Step 1: Create Prometheus scrape config**

Create `docs/prometheus/catapult-scrape.yml`:

```yaml
# Add this job to your existing Prometheus scrape_configs.
# Catapult exposes metrics on port 8081 (management port, not exposed publicly).
scrape_configs:
  - job_name: 'catapult'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['catapult:8081']
        labels:
          environment: 'production'
```

- [ ] **Step 2: Create Grafana dashboard**

Create `docs/grafana/catapult-dashboard.json`:

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "graphTooltip": 0,
  "panels": [
    {
      "type": "timeseries",
      "title": "JVM Heap Used",
      "id": 1,
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "targets": [
        {
          "expr": "jvm_memory_used_bytes{application=\"catapult\",area=\"heap\"}",
          "legendFormat": "Heap Used"
        },
        {
          "expr": "jvm_memory_max_bytes{application=\"catapult\",area=\"heap\"}",
          "legendFormat": "Heap Max"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "bytes" } }
    },
    {
      "type": "timeseries",
      "title": "HTTP Request Rate",
      "id": 2,
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "targets": [
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"catapult\"}[5m]))",
          "legendFormat": "req/s"
        },
        {
          "expr": "sum(rate(http_server_requests_seconds_count{application=\"catapult\",outcome=\"SERVER_ERROR\"}[5m]))",
          "legendFormat": "5xx/s"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "reqps" } }
    },
    {
      "type": "timeseries",
      "title": "HTTP Latency p95",
      "id": 3,
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "targets": [
        {
          "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application=\"catapult\"}[5m])) by (le, uri))",
          "legendFormat": "p95 {{uri}}"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    },
    {
      "type": "stat",
      "title": "Active Bindings",
      "id": 4,
      "gridPos": { "h": 4, "w": 6, "x": 12, "y": 8 },
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "targets": [
        {
          "expr": "catapult_bindings_active{application=\"catapult\"}",
          "legendFormat": "Active"
        }
      ]
    },
    {
      "type": "timeseries",
      "title": "Business Events",
      "id": 5,
      "gridPos": { "h": 8, "w": 18, "x": 6, "y": 12 },
      "datasource": { "type": "prometheus", "uid": "${datasource}" },
      "targets": [
        {
          "expr": "rate(catapult_bindings_deleted_total{application=\"catapult\"}[5m])",
          "legendFormat": "Bindings deleted/s"
        },
        {
          "expr": "rate(catapult_connections_total_total{application=\"catapult\"}[5m])",
          "legendFormat": "Connections/s ({{platform}})"
        },
        {
          "expr": "rate(catapult_game_searches_total{application=\"catapult\"}[5m])",
          "legendFormat": "Game searches/s"
        },
        {
          "expr": "rate(catapult_experiments_assignments_total{application=\"catapult\"}[5m])",
          "legendFormat": "Exp assignments/s ({{experiment}})"
        }
      ]
    }
  ],
  "templating": {
    "list": [
      {
        "name": "datasource",
        "type": "datasource",
        "query": "prometheus",
        "label": "Prometheus"
      }
    ]
  },
  "title": "Catapult",
  "uid": "catapult-monitoring",
  "schemaVersion": 36,
  "version": 1,
  "tags": ["catapult"]
}
```

- [ ] **Step 3: Add env vars to docker-compose.yml**

In `docker-compose.yml`, in the `app.environment` block, add after the last existing env var:

```yaml
      MATOMO_URL: ${MATOMO_URL:-}
      MATOMO_SITE_ID: ${MATOMO_SITE_ID:-1}
      ENVIRONMENT: ${ENVIRONMENT:-production}
```

Port `8081` is intentionally NOT added to `ports:` — Prometheus scrapes it on the internal Docker network.

- [ ] **Step 4: Commit**

```bash
git add docs/prometheus/catapult-scrape.yml docs/grafana/catapult-dashboard.json docker-compose.yml
git commit -m "feat: add Prometheus scrape config, Grafana dashboard, and docker-compose env vars"
```

---

## Task 6 — MatomoProperties + GlobalModelAdvice

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/config/MatomoProperties.java`
- Modify: `src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java`
- Modify: `src/test/java/fr/enimaloc/catapult/web/GlobalModelAdviceTest.java`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Write failing tests for MatomoProperties in GlobalModelAdvice**

Update `src/test/java/fr/enimaloc/catapult/web/GlobalModelAdviceTest.java`.

First fix all existing constructor calls (they all need a second argument once `MatomoProperties` is added). At the start of each existing test, replace:
```java
GlobalModelAdvice advice = new GlobalModelAdvice(Optional.of(buildProperties));
// and
GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty());
```
with:
```java
GlobalModelAdvice advice = new GlobalModelAdvice(Optional.of(buildProperties), new MatomoProperties());
// and
GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty(), new MatomoProperties());
```

Then add these two new tests at the end of the class:

```java
@Test
void matomoEnabled_isExposedAsModelAttribute() {
    MatomoProperties props = new MatomoProperties();
    props.setEnabled(true);
    props.setUrl("https://matomo.example.com");
    props.setSiteId(42);

    GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty(), props);
    MatomoProperties result = advice.addMatomoConfig();

    assertThat(result.isEnabled()).isTrue();
    assertThat(result.getUrl()).isEqualTo("https://matomo.example.com");
    assertThat(result.getSiteId()).isEqualTo(42);
}

@Test
void matomoDisabled_isExposedAsModelAttributeWithEnabledFalse() {
    GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty(), new MatomoProperties());
    MatomoProperties result = advice.addMatomoConfig();

    assertThat(result.isEnabled()).isFalse();
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.GlobalModelAdviceTest"
```

Expected: compilation error — `MatomoProperties` does not exist yet.

- [ ] **Step 3: Create MatomoProperties**

Create `src/main/java/fr/enimaloc/catapult/config/MatomoProperties.java`:

```java
package fr.enimaloc.catapult.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.matomo")
public class MatomoProperties {
    private boolean enabled = false;
    private String url;
    private int siteId = 1;
}
```

- [ ] **Step 4: Modify GlobalModelAdvice**

Add import:
```java
import fr.enimaloc.catapult.config.MatomoProperties;
```

Add field:
```java
private final MatomoProperties matomoProperties;
```

Add method:
```java
@ModelAttribute("matomo")
public MatomoProperties addMatomoConfig() {
    return matomoProperties;
}
```

- [ ] **Step 5: Add Matomo properties to application.properties**

```properties
# ============================================================
# Analytique — Matomo
# ============================================================
app.matomo.enabled=${MATOMO_ENABLED:false}
app.matomo.url=${MATOMO_URL:}
app.matomo.site-id=${MATOMO_SITE_ID:1}
```

- [ ] **Step 6: Run tests — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.GlobalModelAdviceTest"
```

Expected: all 7 tests PASS (5 existing + 2 new).

- [ ] **Step 7: Run full web test suite**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.*"
```

Expected: all PASS. If any `@WebMvcTest` fails with missing `MatomoProperties` bean, add `@MockitoBean MatomoProperties matomoProperties` to that test class.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/config/MatomoProperties.java \
        src/main/java/fr/enimaloc/catapult/web/GlobalModelAdvice.java \
        src/test/java/fr/enimaloc/catapult/web/GlobalModelAdviceTest.java \
        src/main/resources/application.properties
git commit -m "feat: add MatomoProperties config and expose as global model attribute"
```

---

## Task 7 — catapultMatomo.js

**Files:**
- Create: `src/main/resources/static/js/catapultMatomo.js`

No Java tests for pure JS. Behavior is implicitly validated by the template tests in Task 9.

- [ ] **Step 1: Create catapultMatomo.js**

Create `src/main/resources/static/js/catapultMatomo.js`:

```javascript
(function () {
    'use strict';

    function paq(args) {
        if (window._paq) window._paq.push(args);
    }

    function trackEvent(category, action, name) {
        var args = ['trackEvent', category, action];
        if (name) args.push(name);
        paq(args);
    }

    // Track HTMX-driven navigations as page views
    document.addEventListener('htmx:pushedIntoHistory', function () {
        paq(['setCustomUrl', location.href]);
        paq(['setDocumentTitle', document.title]);
        paq(['trackPageView']);
    });

    // Track events on HTMX elements with data-matomo-* attributes (fires only on success)
    document.addEventListener('htmx:afterRequest', function (evt) {
        if (!evt.detail.successful) return;
        var el = evt.detail.elt;
        var category = el.dataset.matomoCategory;
        if (!category) return;
        trackEvent(category, el.dataset.matomoAction, el.dataset.matomoName);
    });

    // Track events on plain form submits with data-matomo-* on the <form>
    document.addEventListener('submit', function (evt) {
        var form = evt.target;
        var category = form.dataset.matomoCategory;
        if (!category) return;
        trackEvent(category, form.dataset.matomoAction, form.dataset.matomoName);
    });

    // Track events on anchor clicks with data-matomo-* attributes
    document.addEventListener('click', function (evt) {
        var el = evt.target.closest('a[data-matomo-category]');
        if (!el) return;
        trackEvent(el.dataset.matomoCategory, el.dataset.matomoAction, el.dataset.matomoName);
    });

    // Debounced search tracking on inputs with data-matomo-search="true"
    var searchTimer;
    document.addEventListener('input', function (evt) {
        var el = evt.target;
        if (el.dataset.matomoSearch !== 'true') return;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(function () {
            trackEvent('IGDB', 'Search');
        }, 800);
    });

    // Track experiment assignments from server-rendered spans
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-matomo-experiment]').forEach(function (el) {
            trackEvent('Experiments', 'Assigned',
                el.dataset.matomoExperiment + ':' + el.dataset.matomoVariant);
        });
    });
})();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/catapultMatomo.js
git commit -m "feat: add catapultMatomo.js event tracking helper"
```

---

## Task 8 — Matomo snippet + experiment spans in template.html

**Files:**
- Modify: `src/main/resources/templates/template.html`
- Create: `src/test/java/fr/enimaloc/catapult/web/template/MatomoTemplateTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/fr/enimaloc/catapult/web/template/MatomoTemplateTest.java`:

```java
package fr.enimaloc.catapult.web.template;

import fr.enimaloc.catapult.config.MatomoProperties;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.web.GlobalModelAdvice;
import fr.enimaloc.catapult.web.HomeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HomeController.class)
class MatomoTemplateTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CatapultOAuth2UserService oAuth2UserService;

    @Test
    @TestPropertySource(properties = {
        "app.matomo.enabled=true",
        "app.matomo.url=https://matomo.example.com",
        "app.matomo.site-id=42"
    })
    void matomoEnabled_snippetRenderedInPage() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("matomo.php")))
            .andExpect(content().string(containsString("setSiteId")))
            .andExpect(content().string(containsString("catapultMatomo.js")));
    }

    @Test
    void matomoDisabled_noSnippetInPage() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("matomo.php"))));
    }
}
```

- [ ] **Step 2: Run the tests — expect FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.template.MatomoTemplateTest"
```

Expected: FAIL — snippet not present in template yet.

- [ ] **Step 3: Add Matomo snippet to template.html**

In `src/main/resources/templates/template.html`, add this block immediately before `</head>` (after the `th:block th:replace="${additional_head}"` line):

```html
    <!--/*@thymesVar id="matomo" type="fr.enimaloc.catapult.config.MatomoProperties"*/-->
    <th:block th:if="${matomo != null and matomo.enabled and matomo.url != null and !#strings.isEmpty(matomo.url)}">
        <script th:inline="javascript">
            var _paq = window._paq = window._paq || [];
            _paq.push(['trackPageView']);
            _paq.push(['enableLinkTracking']);
            (function() {
                var u = /*[[${matomo.url}]]*/ 'https://matomo.example.com/';
                if (u.charAt(u.length - 1) !== '/') u += '/';
                _paq.push(['setTrackerUrl', u + 'matomo.php']);
                _paq.push(['setSiteId', /*[[${matomo.siteId}]]*/ '1']);
                var d = document, g = d.createElement('script'), s = d.getElementsByTagName('script')[0];
                g.async = true; g.src = u + 'matomo.js';
                s.parentNode.insertBefore(g, s);
            })();
        </script>
        <script th:src="@{/js/catapultMatomo.js}" defer="true"></script>
    </th:block>
```

- [ ] **Step 4: Add experiment tracking spans in template.html**

Immediately after the experiment feedback widget block (line `th:replace="~{fragments/experiment-feedback-widget :: widget(...)}"`), add:

```html
<th:block th:if="${matomo != null and matomo.enabled and activeExperimentAssignments != null}">
    <span th:each="assignment : ${activeExperimentAssignments}"
          th:attr="data-matomo-experiment=${assignment.experiment.key},data-matomo-variant=${assignment.variant.key}"
          style="display:none"></span>
</th:block>
```

- [ ] **Step 5: Run the tests — expect PASS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.template.MatomoTemplateTest"
```

Expected: both tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/template.html \
        src/test/java/fr/enimaloc/catapult/web/template/MatomoTemplateTest.java
git commit -m "feat: add conditional Matomo snippet and experiment tracking to template"
```

---

## Task 9 — data-matomo attributes on bindings.html + connections.html

**Files:**
- Modify: `src/main/resources/templates/fragments/bindings.html`
- Modify: `src/main/resources/templates/fragments/connections.html`

No new tests — behavior is visually validated. Existing template tests must remain green.

- [ ] **Step 1: Add data-matomo to bindings.html delete form**

In `src/main/resources/templates/fragments/bindings.html`, find the delete form (around line 90):

```html
<form th:action="@{/channels/{u}/bindings/{id}/delete(u=${channelUsername},id=${binding.id})}" method="post" style="display:inline">
```

Replace with:
```html
<form th:action="@{/channels/{u}/bindings/{id}/delete(u=${channelUsername},id=${binding.id})}" method="post"
      style="display:inline"
      data-matomo-category="Bindings" data-matomo-action="Delete"
      th:attr="data-matomo-name=${binding.sourceName}">
```

- [ ] **Step 2: Add data-matomo to bindings.html edit-save form**

Find the edit save form (around line 97, `th:action="@{/channels/{u}/bindings/{id}"`):

```html
<form th:action="@{/channels/{u}/bindings/{id}(u=${channelUsername},id=${binding.id})}" method="post">
```

Replace with:
```html
<form th:action="@{/channels/{u}/bindings/{id}(u=${channelUsername},id=${binding.id})}" method="post"
      data-matomo-category="Bindings" data-matomo-action="Update"
      th:attr="data-matomo-name=${binding.sourceName}">
```

- [ ] **Step 3: Add data-matomo-search to game search inputs in bindings.html**

Find all `<input ... oninput="gameSearch(event)">` in `bindings.html` (there is one inside the edit row). Add `data-matomo-search="true"` to it:

```html
<input type="text"
       th:id="'gameSearch-' + ${binding.id}"
       th:attr="data-search-url=@{/channels/{u}/api/games/search(u=${channelUsername})},data-results-id=|gameResults-${binding.id}|,data-gameid-field=|twitchGameId-${binding.id}|,data-gamename-field=|twitchGameName-${binding.id}|"
       th:value="${binding.twitchGameName}"
       th:placeholder="#{common.search_placeholder}"
       autocomplete="off"
       data-matomo-search="true"
       oninput="gameSearch(event)">
```

- [ ] **Step 4: Add data-matomo to connections.html Steam connect link**

In `src/main/resources/templates/fragments/connections.html`, find:

```html
<a target="_blank" th:href="@{/connect/steam}" class="btn btn-primary" th:text="#{connections.connect}">Connecter</a>
```

Replace with:
```html
<a target="_blank" th:href="@{/connect/steam}" class="btn btn-primary" th:text="#{connections.connect}"
   data-matomo-category="Connections" data-matomo-action="Connect" data-matomo-name="STEAM">Connecter</a>
```

- [ ] **Step 5: Add data-matomo to connections.html Steam disconnect form**

Find:
```html
<form th:action="@{/channels/{u}/settings/disconnect(u=${channelUsername})}" method="post">
```

Replace with:
```html
<form th:action="@{/channels/{u}/settings/disconnect(u=${channelUsername})}" method="post"
      data-matomo-category="Connections" data-matomo-action="Disconnect" data-matomo-name="STEAM">
```

- [ ] **Step 6: Run full test suite**

```bash
./gradlew test
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/templates/fragments/bindings.html \
        src/main/resources/templates/fragments/connections.html
git commit -m "feat: add Matomo event attributes to bindings and connections fragments"
```

---

## Task 10 — data-matomo attributes on settings fragments

**Files:**
- Modify: `src/main/resources/templates/fragments/no-game-settings.html`
- Modify: `src/main/resources/templates/fragments/incomplete-fallback-settings.html`

- [ ] **Step 1: Add data-matomo to no-game-settings form**

In `src/main/resources/templates/fragments/no-game-settings.html`, find:

```html
<form th:action="@{/channels/{u}/settings/no-game(u=${channelUsername})}" method="post">
```

Replace with:
```html
<form th:action="@{/channels/{u}/settings/no-game(u=${channelUsername})}" method="post"
      data-matomo-category="Settings" data-matomo-action="Update" data-matomo-name="NoGame">
```

Also add `data-matomo-search="true"` to the game search input in this fragment:

```html
<input type="text"
       id="gameSearch-noGame"
       th:attr="data-search-url=@{/channels/{u}/api/games/search(u=${channelUsername})}"
       data-results-id="gameResults-noGame"
       data-gameid-field="twitchGameId-noGame"
       data-gamename-field="twitchGameName-noGame"
       th:value="${noGameSettings.noGameTwitchGameName}"
       th:placeholder="#{common.search_placeholder}"
       autocomplete="off"
       data-matomo-search="true"
       oninput="gameSearch(event)">
```

- [ ] **Step 2: Add data-matomo to incomplete-fallback-settings form**

In `src/main/resources/templates/fragments/incomplete-fallback-settings.html`, find:

```html
<form th:action="@{/channels/{u}/settings/incomplete-fallback(u=${channelUsername})}" method="post">
```

Replace with:
```html
<form th:action="@{/channels/{u}/settings/incomplete-fallback(u=${channelUsername})}" method="post"
      data-matomo-category="Settings" data-matomo-action="Update" data-matomo-name="IncompleteFallback">
```

Also add `data-matomo-search="true"` to the game search input:

```html
<input type="text"
       id="gameSearch-incompleteGame"
       th:attr="data-search-url=@{/channels/{u}/api/games/search(u=${channelUsername})}"
       data-results-id="gameResults-incompleteGame"
       data-gameid-field="twitchGameId-incompleteGame"
       data-gamename-field="twitchGameName-incompleteGame"
       th:value="${incompleteFallbackSettings.incompleteFallbackTwitchGameName}"
       th:placeholder="#{common.search_placeholder}"
       autocomplete="off"
       data-matomo-search="true"
       oninput="gameSearch(event)">
```

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/fragments/no-game-settings.html \
        src/main/resources/templates/fragments/incomplete-fallback-settings.html
git commit -m "feat: add Matomo event attributes to settings fragments"
```

---

## Self-review

**Spec coverage check:**
- ✅ Prometheus dependency + management endpoint (Task 1)
- ✅ `catapult.bindings.active` gauge + `catapult.bindings.deleted` counter (Task 2)
- ✅ `catapult.game.searches` counter (Task 4b)
- ✅ `catapult.connections.total` counter with `platform` tag (Task 3)
- ✅ `catapult.experiments.assignments` counter with `experiment`+`variant` tags (Task 4a)
- ✅ `management.server.port=8081` not in docker-compose ports (Task 5)
- ✅ `MatomoProperties` + GlobalModelAdvice (Task 6)
- ✅ `catapultMatomo.js` with all event types (Task 7)
- ✅ Conditional Matomo snippet in `template.html` (Task 8)
- ✅ Experiment assignment spans (Task 8)
- ✅ Bindings events (Task 9)
- ✅ Connections events (Task 9)
- ✅ Settings events + game search tracking (Task 10)
- ✅ Prometheus scrape config + Grafana dashboard (Task 5)

**Gap noted:** The spec listed `catapult.bindings.created` but this plan only adds `catapult.bindings.deleted` (because `createWithIgdbResolution` is a private method called from polling, not direct user action). If per-binding creation tracking is needed, add a counter call in `BindingService.createWithIgdbResolution()` with `tag("game", detectedGame.getSourceName())`.

**Type consistency:** All metric names use dots in Java (`catapult.game.searches`) which Micrometer converts to underscores in Prometheus (`catapult_game_searches_total`). Grafana dashboard uses the Prometheus names consistently.
