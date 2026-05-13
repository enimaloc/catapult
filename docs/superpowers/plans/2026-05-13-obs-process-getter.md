# OBS Process Getter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a local OBS Python script to push running process names to catapult, which maps them to Twitch game categories via a UI-managed table, enabling automatic game detection without Steam/Xbox APIs.

**Architecture:** An OBS Python script on the user's PC periodically POSTs the list of running process names to `POST /api/obs/processes`, authenticated by a per-user API key stored in `user_account`. An in-memory `ObsProcessCache` stores the latest process set per user. A new `ObsGameGetter` queries `ProcessBinding` (DB table mapping process names to Twitch games) against the cache. The setup UI shows the installation guide, the user's API key, and a CRUD table to manage process-to-game mappings.

**Tech Stack:** Java 17, Spring Boot 4.x, Spring Security (custom `OncePerRequestFilter`), Spring Data JPA, Flyway, Thymeleaf + HTMX, JUnit 5 + Mockito

---

## File Map

**Create:**
- `src/main/resources/db/migration/V10__obs_process_binding.sql`
- `src/main/resources/db/migration/V11__add_api_key_to_user_account.sql`
- `src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java`
- `src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java`
- `src/main/java/fr/enimaloc/catapult/service/ObsProcessCache.java`
- `src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java`
- `src/main/java/fr/enimaloc/catapult/security/ApiKeyAuthFilter.java`
- `src/main/java/fr/enimaloc/catapult/web/ObsApiController.java`
- `src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java`
- `src/main/resources/templates/fragments/obs-setup.html`
- `src/test/java/fr/enimaloc/catapult/service/ObsProcessCacheTest.java`
- `src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java`
- `src/test/java/fr/enimaloc/catapult/web/ObsApiControllerTest.java`

**Modify:**
- `src/main/java/fr/enimaloc/catapult/domain/UserAccount.java` — add `apiKey` column
- `src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java` — add `findByApiKey`
- `src/main/java/fr/enimaloc/catapult/domain/GameBinding.java` — add `SourceType.OBS`
- `src/main/java/fr/enimaloc/catapult/domain/GetterConfig.java` — add `Provider.OBS`
- `src/main/java/fr/enimaloc/catapult/getter/GameGetterChain.java` — wire `ObsGameGetter`
- `src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java` — register filter, permit API endpoint
- `src/main/resources/templates/app.html` — include obs-setup fragment

---

## Task 1: DB migrations

**Files:**
- Create: `src/main/resources/db/migration/V10__obs_process_binding.sql`
- Create: `src/main/resources/db/migration/V11__add_api_key_to_user_account.sql`

- [ ] **Step 1: Create V10 migration**

```sql
-- src/main/resources/db/migration/V10__obs_process_binding.sql
CREATE TABLE process_binding (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    process_name    VARCHAR(255) NOT NULL,
    twitch_game_id  VARCHAR(50),
    twitch_game_name VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (user_id, process_name)
);

CREATE INDEX idx_process_binding_user ON process_binding(user_id);
```

- [ ] **Step 2: Create V11 migration**

```sql
-- src/main/resources/db/migration/V11__add_api_key_to_user_account.sql
ALTER TABLE user_account
    ADD COLUMN api_key VARCHAR(64) UNIQUE;

CREATE INDEX idx_user_account_api_key ON user_account(api_key);
```

- [ ] **Step 3: Verify migrations apply**

```bash
./gradlew flywayMigrate
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V10__obs_process_binding.sql \
        src/main/resources/db/migration/V11__add_api_key_to_user_account.sql
git commit -m "feat: add process_binding table and api_key column on user_account"
```

---

## Task 2: ProcessBinding entity + repository

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java`
- Create: `src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java`

No tests needed — these are pure JPA declarations, tested implicitly by integration.

- [ ] **Step 1: Create ProcessBinding entity**

```java
// src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java
package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "process_binding",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "process_name"})
)
@Getter
@Setter
public class ProcessBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "process_name", nullable = false)
    private String processName;

    @Column(name = "twitch_game_id")
    private String twitchGameId;

    @Column(name = "twitch_game_name")
    private String twitchGameName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 2: Create ProcessBindingRepository**

```java
// src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java
package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessBindingRepository extends JpaRepository<ProcessBinding, UUID> {

    List<ProcessBinding> findByUserOrderByProcessNameAsc(UserAccount user);

    Optional<ProcessBinding> findFirstByUserAndProcessNameIn(UserAccount user, Collection<String> processNames);
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java \
        src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java
git commit -m "feat: add ProcessBinding entity and repository"
```

---

## Task 3: UserAccount API key + ObsProcessCache

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/domain/UserAccount.java`
- Modify: `src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java`
- Create: `src/main/java/fr/enimaloc/catapult/service/ObsProcessCache.java`
- Create: `src/test/java/fr/enimaloc/catapult/service/ObsProcessCacheTest.java`

- [ ] **Step 1: Write failing test for ObsProcessCache**

```java
// src/test/java/fr/enimaloc/catapult/service/ObsProcessCacheTest.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObsProcessCacheTest {

    private ObsProcessCache cache;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        cache = new ObsProcessCache();
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getProcesses_emptyByDefault() {
        assertThat(cache.getProcesses(user)).isEmpty();
    }

    @Test
    void update_storesProcesses() {
        cache.update(user, Set.of("hl2.exe", "steam.exe"));
        assertThat(cache.getProcesses(user)).containsExactlyInAnyOrder("hl2.exe", "steam.exe");
    }

    @Test
    void update_replacesExistingProcesses() {
        cache.update(user, Set.of("old.exe"));
        cache.update(user, Set.of("new.exe"));
        assertThat(cache.getProcesses(user)).containsExactly("new.exe");
    }

    @Test
    void clear_removesEntry() {
        cache.update(user, Set.of("game.exe"));
        cache.clear(user);
        assertThat(cache.getProcesses(user)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ObsProcessCacheTest"
```

Expected: compilation error — `ObsProcessCache` does not exist.

- [ ] **Step 3: Add apiKey field to UserAccount**

In `src/main/java/fr/enimaloc/catapult/domain/UserAccount.java`, add after the `deletionRequestedAt` field:

```java
@Column(name = "api_key", unique = true)
private String apiKey;
```

- [ ] **Step 4: Add findByApiKey to UserAccountRepository**

In `src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java`, add:

```java
Optional<UserAccount> findByApiKey(String apiKey);
```

- [ ] **Step 5: Implement ObsProcessCache**

```java
// src/main/java/fr/enimaloc/catapult/service/ObsProcessCache.java
package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ObsProcessCache {

    private final Map<UUID, Set<String>> cache = new ConcurrentHashMap<>();

    public Set<String> getProcesses(UserAccount user) {
        return cache.getOrDefault(user.getId(), Set.of());
    }

    public void update(UserAccount user, Set<String> processes) {
        cache.put(user.getId(), Set.copyOf(processes));
    }

    public void clear(UserAccount user) {
        cache.remove(user.getId());
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.service.ObsProcessCacheTest"
```

Expected: all 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/UserAccount.java \
        src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java \
        src/main/java/fr/enimaloc/catapult/service/ObsProcessCache.java \
        src/test/java/fr/enimaloc/catapult/service/ObsProcessCacheTest.java
git commit -m "feat: add api_key to UserAccount and ObsProcessCache"
```

---

## Task 4: OBS types + ObsGameGetter + GameGetterChain wiring

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/domain/GameBinding.java`
- Modify: `src/main/java/fr/enimaloc/catapult/domain/GetterConfig.java`
- Create: `src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java`
- Modify: `src/main/java/fr/enimaloc/catapult/getter/GameGetterChain.java`
- Create: `src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java`

- [ ] **Step 1: Write failing tests for ObsGameGetter**

```java
// src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java
package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.ObsProcessCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObsGameGetterTest {

    @Mock private ObsProcessCache obsProcessCache;
    @Mock private ProcessBindingRepository processBindingRepository;

    @InjectMocks private ObsGameGetter getter;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getCurrentGame_noProcesses_returnsEmpty() {
        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_processesWithNoBinding_returnsEmpty() {
        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of("unknown.exe"));
        when(processBindingRepository.findFirstByUserAndProcessNameIn(user, Set.of("unknown.exe")))
                .thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_matchingBinding_returnsDetectedGame() {
        ProcessBinding binding = new ProcessBinding();
        binding.setProcessName("hl2.exe");
        binding.setTwitchGameId("70");
        binding.setTwitchGameName("Half-Life 2");

        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of("hl2.exe", "steam.exe"));
        when(processBindingRepository.findFirstByUserAndProcessNameIn(user, Set.of("hl2.exe", "steam.exe")))
                .thenReturn(Optional.of(binding));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("hl2.exe");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "fr.enimaloc.catapult.getter.ObsGameGetterTest"
```

Expected: compilation error — `ObsGameGetter` does not exist.

- [ ] **Step 3: Add SourceType.OBS to GameBinding**

In `src/main/java/fr/enimaloc/catapult/domain/GameBinding.java`, replace:

```java
    public enum SourceType {
        STEAM, XBOX, BATTLENET, MANUAL
    }
```

With:

```java
    public enum SourceType {
        STEAM, XBOX, BATTLENET, MANUAL, OBS
    }
```

- [ ] **Step 4: Add Provider.OBS to GetterConfig**

In `src/main/java/fr/enimaloc/catapult/domain/GetterConfig.java`, replace:

```java
    public enum Provider {
        STEAM, XBOX, BATTLENET
    }
```

With:

```java
    public enum Provider {
        STEAM, XBOX, BATTLENET, OBS
    }
```

- [ ] **Step 5: Implement ObsGameGetter**

```java
// src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java
package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.ObsProcessCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ObsGameGetter implements GameGetter {

    private final ObsProcessCache obsProcessCache;
    private final ProcessBindingRepository processBindingRepository;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        Set<String> processes = obsProcessCache.getProcesses(user);
        if (processes.isEmpty()) return Optional.empty();
        return processBindingRepository.findFirstByUserAndProcessNameIn(user, processes)
                .map(pb -> new DetectedGame(pb.getProcessName(), GameBinding.SourceType.OBS, pb.getTwitchGameName()));
    }
}
```

- [ ] **Step 6: Wire ObsGameGetter into GameGetterChain**

In `src/main/java/fr/enimaloc/catapult/getter/GameGetterChain.java`:

Add field after existing optional getters:

```java
    private final Optional<ObsGameGetter> obsGameGetter;
```

In `buildGetterMap()`, add after the `battleNetGameGetter` line:

```java
        obsGameGetter.ifPresent(g -> map.put(GetterConfig.Provider.OBS, g));
```

- [ ] **Step 7: Run tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.getter.ObsGameGetterTest"
```

Expected: all 3 tests PASS.

- [ ] **Step 8: Run full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/GameBinding.java \
        src/main/java/fr/enimaloc/catapult/domain/GetterConfig.java \
        src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java \
        src/main/java/fr/enimaloc/catapult/getter/GameGetterChain.java \
        src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java
git commit -m "feat: add ObsGameGetter wired into GameGetterChain"
```

---

## Task 5: API key filter + SecurityConfig + ObsApiController

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/security/ApiKeyAuthFilter.java`
- Modify: `src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java`
- Create: `src/main/java/fr/enimaloc/catapult/web/ObsApiController.java`
- Create: `src/test/java/fr/enimaloc/catapult/web/ObsApiControllerTest.java`

- [ ] **Step 1: Write failing tests for ObsApiController**

```java
// src/test/java/fr/enimaloc/catapult/web/ObsApiControllerTest.java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ObsApiControllerTest {

    @Mock private ObsProcessCache obsProcessCache;

    @InjectMocks private ObsApiController controller;

    @Test
    void receiveProcesses_updatesCache() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        controller.receiveProcesses(auth, new ObsApiController.ProcessesPayload(List.of("hl2.exe", "steam.exe")));

        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(obsProcessCache).update(org.mockito.ArgumentMatchers.eq(user), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("hl2.exe", "steam.exe");
    }

    @Test
    void receiveProcesses_emptyList_clearsCache() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());

        controller.receiveProcesses(auth, new ObsApiController.ProcessesPayload(List.of()));

        verify(obsProcessCache).clear(user);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.ObsApiControllerTest"
```

Expected: compilation error — `ObsApiController` does not exist.

- [ ] **Step 3: Implement ApiKeyAuthFilter**

```java
// src/main/java/fr/enimaloc/catapult/security/ApiKeyAuthFilter.java
package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final UserAccountRepository userAccountRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            userAccountRepository.findByApiKey(apiKey).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Update SecurityConfig**

Replace the full `securityFilterChain` method in `SecurityConfig.java`:

```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
        http
            .addFilterBefore(apiKeyAuthFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/obs/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/api/obs/**").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/app", true)
                .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                .failureHandler((request, response, exception) -> {
                    log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
                    new SimpleUrlAuthenticationFailureHandler("/login?error").onAuthenticationFailure(request, response, exception);
                })
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }
```

- [ ] **Step 5: Implement ObsApiController**

```java
// src/main/java/fr/enimaloc/catapult/web/ObsApiController.java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ObsProcessCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class ObsApiController {

    private final ObsProcessCache obsProcessCache;

    public record ProcessesPayload(List<String> processes) {}

    @PostMapping("/api/obs/processes")
    public ResponseEntity<Void> receiveProcesses(Authentication authentication,
                                                 @RequestBody ProcessesPayload payload) {
        UserAccount user = (UserAccount) authentication.getPrincipal();
        if (payload.processes() == null || payload.processes().isEmpty()) {
            obsProcessCache.clear(user);
        } else {
            obsProcessCache.update(user, Set.copyOf(payload.processes()));
        }
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.ObsApiControllerTest"
```

Expected: all 2 tests PASS.

- [ ] **Step 7: Run full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/security/ApiKeyAuthFilter.java \
        src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java \
        src/main/java/fr/enimaloc/catapult/web/ObsApiController.java \
        src/test/java/fr/enimaloc/catapult/web/ObsApiControllerTest.java
git commit -m "feat: add API key auth filter and OBS process endpoint"
```

---

## Task 6: ObsSetupController (API key + process binding CRUD)

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java`

No unit test — controller delegates to repository directly (no service layer needed for simple CRUD). Tested by integration test or manual UI check.

- [ ] **Step 1: Implement ObsSetupController**

```java
// src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ObsSetupController {

    private final UserAccountRepository userAccountRepository;
    private final ProcessBindingRepository processBindingRepository;
    private final GetterConfigRepository getterConfigRepository;

    @Value("${app.public-url:http://localhost:8080}")
    private String publicUrl;

    @GetMapping("/fragments/obs-setup")
    public String fragment(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();
        model.addAttribute("apiKey", user.getApiKey());
        model.addAttribute("publicUrl", publicUrl);
        model.addAttribute("processBindings", processBindingRepository.findByUserOrderByProcessNameAsc(user));
        return "fragments/obs-setup :: obs-setup";
    }

    @PostMapping("/obs/generate-key")
    public String generateApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        String newKey = generateSecureKey();
        user.setApiKey(newKey);
        userAccountRepository.save(user);
        ensureObsGetterConfig(user);
        return "redirect:/app";
    }

    @PostMapping("/obs/revoke-key")
    public String revokeApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        user.setApiKey(null);
        userAccountRepository.save(user);
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings")
    public String addProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                    @RequestParam String processName,
                                    @RequestParam String twitchGameId,
                                    @RequestParam String twitchGameName) {
        UserAccount user = principal.getUserAccount();
        ProcessBinding binding = new ProcessBinding();
        binding.setUser(user);
        binding.setProcessName(processName.strip());
        binding.setTwitchGameId(twitchGameId);
        binding.setTwitchGameName(twitchGameName);
        processBindingRepository.save(binding);
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings/{id}/delete")
    public String deleteProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                       @PathVariable UUID id) {
        processBindingRepository.findById(id).ifPresent(pb -> {
            if (pb.getUser().getId().equals(principal.getUserAccount().getId())) {
                processBindingRepository.delete(pb);
            }
        });
        return "redirect:/app";
    }

    private void ensureObsGetterConfig(UserAccount user) {
        if (getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.OBS).isEmpty()) {
            List<fr.enimaloc.catapult.domain.GetterConfig> existing =
                    getterConfigRepository.findByUserOrderByPriorityAsc(user);
            int nextPriority = existing.stream()
                    .mapToInt(fr.enimaloc.catapult.domain.GetterConfig::getPriority)
                    .max().orElse(0) + 1;

            GetterConfig config = new GetterConfig();
            config.setUser(user);
            config.setProvider(GetterConfig.Provider.OBS);
            config.setPriority(nextPriority);
            config.setEnabled(true);
            getterConfigRepository.save(config);
        }
    }

    private static String generateSecureKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java
git commit -m "feat: add ObsSetupController for API key management and process binding CRUD"
```

---

## Task 7: obs-setup.html fragment + integration into app.html

**Files:**
- Create: `src/main/resources/templates/fragments/obs-setup.html`
- Modify: `src/main/resources/templates/app.html`

- [ ] **Step 1: Create obs-setup.html fragment**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<section th:fragment="obs-setup" class="card">
    <h3>OBS — Détection par processus</h3>

    <!-- API Key -->
    <div th:if="${apiKey == null}">
        <p class="text-muted">Génère une clé API pour connecter le script OBS à Catapult.</p>
        <form th:action="@{/obs/generate-key}" method="post">
            <button type="submit" class="btn btn-primary">Générer une clé API</button>
        </form>
    </div>

    <div th:unless="${apiKey == null}">
        <div style="margin-bottom:12px">
            <label style="font-size:.85em;font-weight:600;color:#555">Clé API</label>
            <div style="display:flex;gap:8px;align-items:center;margin-top:4px">
                <code th:text="${apiKey}" style="background:#f4f4f4;padding:6px 10px;border-radius:4px;font-size:.9em;flex:1;word-break:break-all"></code>
                <form th:action="@{/obs/generate-key}" method="post" style="margin:0">
                    <button type="submit" class="btn btn-sm btn-outline" title="Régénérer">↺ Régénérer</button>
                </form>
                <form th:action="@{/obs/revoke-key}" method="post" style="margin:0">
                    <button type="submit" class="btn btn-sm btn-danger">Révoquer</button>
                </form>
            </div>
        </div>

        <!-- Installation Steps -->
        <details style="margin-bottom:16px">
            <summary style="cursor:pointer;font-weight:600">📋 Guide d'installation</summary>
            <ol style="margin-top:12px;line-height:2">
                <li>Installe Python et OBS Studio (v28+).</li>
                <li>Dans un terminal, installe les dépendances :<br>
                    <code style="background:#f4f4f4;padding:2px 6px;border-radius:3px">pip install psutil requests</code>
                </li>
                <li>Dans OBS : <strong>Outils → Scripts → +</strong>, sélectionne le fichier <code>catapult_obs.py</code> ci-dessous.</li>
                <li>Le script s'exécute automatiquement — aucune configuration supplémentaire.</li>
            </ol>
        </details>

        <!-- Script -->
        <details style="margin-bottom:16px">
            <summary style="cursor:pointer;font-weight:600">📄 Script OBS Python</summary>
            <div style="position:relative;margin-top:8px">
                <button type="button"
                        onclick="navigator.clipboard.writeText(document.getElementById('obs-script').textContent)"
                        style="position:absolute;top:6px;right:6px;font-size:.75em"
                        class="btn btn-sm btn-outline">Copier</button>
                <pre id="obs-script" style="background:#1e1e1e;color:#d4d4d4;padding:16px;border-radius:6px;overflow-x:auto;font-size:.82em;line-height:1.5;white-space:pre"><code># catapult_obs.py — Catapult OBS integration
# Enregistre les processus actifs vers Catapult pour la détection de jeu automatique.
import obspython as obs
import psutil
import requests

CATAPULT_URL = "<span th:text="${publicUrl}"></span>/api/obs/processes"
API_KEY      = "<span th:text="${apiKey}"></span>"
INTERVAL_MS  = 10_000  # toutes les 10 secondes

def _send():
    try:
        procs = list({p.name() for p in psutil.process_iter(["name"])})
        requests.post(CATAPULT_URL, json={"processes": procs},
                      headers={"X-Api-Key": API_KEY}, timeout=5)
    except Exception as e:
        print(f"[Catapult] {e}")

def script_load(settings):
    obs.timer_add(_send, INTERVAL_MS)

def script_unload():
    obs.timer_remove(_send)

def script_description():
    return "Catapult — détection de jeu par processus."
</code></pre>
            </div>
        </details>

        <!-- Process Bindings Table -->
        <h4 style="margin-bottom:8px">Associations processus → jeu</h4>

        <div th:if="${processBindings.empty}" class="text-muted" style="margin-bottom:12px">
            Aucune association configurée.
        </div>

        <table th:unless="${processBindings.empty}" style="width:100%;border-collapse:collapse;margin-bottom:12px">
            <thead>
                <tr style="border-bottom:2px solid #eee;text-align:left">
                    <th style="padding:8px">Processus</th>
                    <th style="padding:8px">Jeu Twitch</th>
                    <th style="padding:8px"></th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="pb : ${processBindings}" style="border-bottom:1px solid #f0f0f0">
                    <td style="padding:8px"><code th:text="${pb.processName}"></code></td>
                    <td style="padding:8px" th:text="${pb.twitchGameName}"></td>
                    <td style="padding:8px;text-align:right">
                        <form th:action="@{/obs/process-bindings/{id}/delete(id=${pb.id})}" method="post">
                            <button type="submit" class="btn btn-sm btn-danger">Supprimer</button>
                        </form>
                    </td>
                </tr>
            </tbody>
        </table>

        <!-- Add new binding -->
        <form th:action="@{/obs/process-bindings}" method="post" style="display:flex;gap:8px;align-items:flex-end;flex-wrap:wrap">
            <div style="display:flex;flex-direction:column;gap:4px">
                <label style="font-size:.8em;font-weight:600;color:#555">Nom du processus</label>
                <input type="text" name="processName" placeholder="ex: hl2.exe" required
                       style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em">
            </div>
            <div style="display:flex;flex-direction:column;gap:4px;position:relative">
                <label style="font-size:.8em;font-weight:600;color:#555">Jeu Twitch</label>
                <div class="search-wrapper">
                    <input type="text" id="obsGameSearch" placeholder="Rechercher un jeu…"
                           oninput="debouncedSearch(event,'obs')"
                           style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
                    <ul id="gameResults-obs" class="game-results"></ul>
                </div>
                <input type="hidden" name="twitchGameId" id="twitchGameId-obs" required>
                <input type="hidden" name="twitchGameName" id="twitchGameName-obs" required>
            </div>
            <button type="submit" class="btn btn-primary">Ajouter</button>
        </form>

        <script th:inline="javascript">
        function debouncedSearch(event, id) {
            const q = event.target.value.trim();
            const results = document.getElementById('gameResults-' + id);
            if (q.length < 2) { results.style.display = 'none'; return; }
            clearTimeout(window['_obsTimer']);
            window['_obsTimer'] = setTimeout(() => {
                fetch('/api/games/search?q=' + encodeURIComponent(q))
                    .then(r => r.json())
                    .then(data => {
                        results.replaceChildren();
                        if (!data.length) { results.style.display = 'none'; return; }
                        data.forEach(game => {
                            const li = document.createElement('li');
                            li.textContent = game.name;
                            li.addEventListener('click', () => {
                                document.getElementById('twitchGameId-' + id).value = game.id;
                                document.getElementById('twitchGameName-' + id).value = game.name;
                                document.getElementById('obsGameSearch').value = game.name;
                                results.style.display = 'none';
                            });
                            results.appendChild(li);
                        });
                        results.style.display = 'block';
                    })
                    .catch(() => results.style.display = 'none');
            }, 300);
        }
        </script>
    </div>
</section>
</body>
</html>
```

- [ ] **Step 2: Add obs-setup fragment to app.html**

In `src/main/resources/templates/app.html`, add after the connections fragment include:

```html
        <div th:replace="~{fragments/obs-setup :: obs-setup}"></div>
```

So the block becomes:

```html
        <div th:replace="~{fragments/connections :: connections}"></div>
        <div th:replace="~{fragments/obs-setup :: obs-setup}"></div>
        <div th:replace="~{fragments/status :: status}"></div>
```

- [ ] **Step 3: Add obs-setup to AppController.app() model**

In `src/main/java/fr/enimaloc/catapult/web/AppController.java`, inject `ProcessBindingRepository`:

Add field:
```java
    private final ProcessBindingRepository processBindingRepository;
```

In the `app()` method, add after `model.addAttribute("hasSteam", ...)`:

```java
        model.addAttribute("apiKey", user.getApiKey());
        model.addAttribute("publicUrl", "");  // served by ObsSetupController fragment endpoint
```

Note: the fragment data is actually loaded via the fragment endpoint `/fragments/obs-setup`. Remove the above lines from `app()` — the obs-setup fragment is always fetched independently.

Instead, the `app.html` include should use HTMX lazy loading:

Replace the obs-setup include line with:

```html
        <div hx-get="/fragments/obs-setup" hx-trigger="load" hx-swap="outerHTML">
            <div class="card"><p class="text-muted">Chargement OBS…</p></div>
        </div>
```

- [ ] **Step 4: Run full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/fragments/obs-setup.html \
        src/main/resources/templates/app.html
git commit -m "feat: add OBS setup fragment with installation guide, API key and process bindings CRUD"
```

---

## Self-Review Checklist

**Spec coverage:**
- OBS Python script pushes process list → `POST /api/obs/processes` ✅ Task 5
- API key authentication → `ApiKeyAuthFilter` ✅ Task 5
- `ObsProcessCache` in-memory store ✅ Task 3
- `ProcessBinding` DB table ✅ Tasks 1+2
- `ObsGameGetter` implements `GameGetter`, wired into chain ✅ Task 4
- `GetterConfig.Provider.OBS` seeded on key generation ✅ Task 6
- UI: API key generate/revoke ✅ Task 7
- UI: installation guide + script pre-filled ✅ Task 7
- UI: process binding CRUD with game search ✅ Task 7
- Empty process list clears the cache ✅ Task 5 (`ObsApiControllerTest`)
- Security: only the owner can delete their process binding ✅ Task 6 (ownership check)
- CSRF disabled for `/api/obs/**` ✅ Task 5 (`SecurityConfig`)

**Placeholder scan:** No TBD, all steps include complete code. ✅

**Type consistency:**
- `ObsProcessCache.update(UserAccount, Set<String>)` / `getProcesses(UserAccount)` / `clear(UserAccount)` — defined Task 3, used in Tasks 4, 5, 6 ✅
- `ProcessBindingRepository.findFirstByUserAndProcessNameIn(UserAccount, Collection<String>)` — defined Task 2, used Task 4 ✅
- `ObsGameGetter` uses `GameBinding.SourceType.OBS` defined Task 4 ✅
- `GetterConfig.Provider.OBS` defined Task 4, used Task 6 ✅
- `UserAccount.getApiKey()` / `setApiKey(String)` defined Task 3, used Tasks 5+6 ✅
- `UserAccountRepository.findByApiKey(String)` defined Task 3, used Task 5 ✅
