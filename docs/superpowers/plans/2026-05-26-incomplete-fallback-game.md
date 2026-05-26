# Incomplete Fallback Game — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a detected game's binding is `INCOMPLETE`, apply a user-configured fallback Twitch category instead of silently skipping the update.

**Architecture:** Add two new columns + one join table to `user_settings` (migration V24). Modify `GameEventListener.onGameDetected()` to separate `INCOMPLETE` from `ignored` and call a new `applyIncompleteFallback()` method that mirrors the existing `onNoGameDetected()` pattern. Add a settings fragment + two endpoints to `ChannelController` for the UI.

**Tech Stack:** Java 21, Spring Boot 4, Thymeleaf, HTMX, Flyway, JUnit 5 + Mockito, Lombok, Spring MVC Test

---

## File Map

| File | Action |
|---|---|
| `src/main/java/fr/enimaloc/catapult/domain/UserSettings.java` | Modify — add 3 fields |
| `src/main/resources/db/migration/V24__add_incomplete_fallback_to_user_settings.sql` | Create |
| `src/test/java/fr/enimaloc/catapult/event/GameEventListenerLiveCheckTest.java` | Modify — add 3 tests |
| `src/main/java/fr/enimaloc/catapult/event/GameEventListener.java` | Modify — split incomplete/ignored, add `applyIncompleteFallback()` |
| `src/main/resources/lang/messages.properties` | Modify — add `incomplete_fallback.*` keys |
| `src/main/resources/lang/messages_fr.properties` | Modify — add `incomplete_fallback.*` keys |
| `src/main/resources/templates/fragments/incomplete-fallback-settings.html` | Create |
| `src/main/java/fr/enimaloc/catapult/web/ChannelController.java` | Modify — add 2 endpoints |
| `src/main/resources/templates/app.html` | Modify — include new fragment div |
| `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java` | Modify — add 1 test |

---

## Task 1: Domain — UserSettings fields + DB migration

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/domain/UserSettings.java`
- Create: `src/main/resources/db/migration/V24__add_incomplete_fallback_to_user_settings.sql`

- [ ] **Step 1.1: Add 3 new fields to `UserSettings.java`**

Open `src/main/java/fr/enimaloc/catapult/domain/UserSettings.java`. After the `noGameCcls` field (around line 41), add:

```java
    @Column(name = "incomplete_fallback_twitch_game_id")
    private String incompleteFallbackTwitchGameId;

    @Column(name = "incomplete_fallback_twitch_game_name")
    private String incompleteFallbackTwitchGameName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_incomplete_fallback_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> incompleteFallbackCcls = new HashSet<>();
```

The full file after the change:

```java
package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettings {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "ccl_feature_enabled", nullable = false)
    private boolean cclFeatureEnabled = true;

    @Column(name = "no_game_twitch_game_id")
    private String noGameTwitchGameId;

    @Column(name = "no_game_twitch_game_name")
    private String noGameTwitchGameName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_no_game_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> noGameCcls = new HashSet<>();

    @Column(name = "incomplete_fallback_twitch_game_id")
    private String incompleteFallbackTwitchGameId;

    @Column(name = "incomplete_fallback_twitch_game_name")
    private String incompleteFallbackTwitchGameName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_settings_incomplete_fallback_ccls",
        joinColumns = @JoinColumn(name = "user_id")
    )
    @Column(name = "ccl_id")
    private Set<String> incompleteFallbackCcls = new HashSet<>();
}
```

- [ ] **Step 1.2: Create migration `V24__add_incomplete_fallback_to_user_settings.sql`**

```sql
ALTER TABLE user_settings
    ADD COLUMN incomplete_fallback_twitch_game_id   VARCHAR,
    ADD COLUMN incomplete_fallback_twitch_game_name VARCHAR;

CREATE TABLE user_settings_incomplete_fallback_ccls (
    user_id UUID         NOT NULL REFERENCES user_settings(user_id) ON DELETE CASCADE,
    ccl_id  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (user_id, ccl_id)
);
```

- [ ] **Step 1.3: Run existing tests to verify nothing broke**

```bash
./gradlew test --tests "fr.enimaloc.catapult.event.GameEventListenerLiveCheckTest"
```

Expected: All 7 existing tests PASS.

- [ ] **Step 1.4: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/UserSettings.java \
        src/main/resources/db/migration/V24__add_incomplete_fallback_to_user_settings.sql
git commit -m "feat: add incomplete fallback fields to UserSettings"
```

---

## Task 2: GameEventListener — incomplete fallback logic (TDD)

**Files:**
- Modify: `src/test/java/fr/enimaloc/catapult/event/GameEventListenerLiveCheckTest.java`
- Modify: `src/main/java/fr/enimaloc/catapult/event/GameEventListener.java`

- [ ] **Step 2.1: Write 3 failing tests in `GameEventListenerLiveCheckTest.java`**

Add these 3 tests to the existing class (after the last `@Test` method):

```java
    @Test
    void onGameDetected_whenBindingIncomplete_andFallbackConfigured_andLive_appliesFallback() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);

        UserSettings settings = new UserSettings();
        settings.setIncompleteFallbackTwitchGameId("fallback-id");
        settings.setIncompleteFallbackTwitchGameName("Just Chatting");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(streamStateService.isLive(user)).thenReturn(true);

        DetectedGame game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Unknown Game");
        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(twitchService).updateChannel(eq(user), any(GameBinding.class));
        verify(streamStateService, never()).storePending(any(), any());
    }

    @Test
    void onGameDetected_whenBindingIncomplete_andFallbackConfigured_andNotLive_storesPending() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);

        UserSettings settings = new UserSettings();
        settings.setIncompleteFallbackTwitchGameId("fallback-id");
        settings.setIncompleteFallbackTwitchGameName("Just Chatting");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(streamStateService.isLive(user)).thenReturn(false);

        DetectedGame game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Unknown Game");
        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(streamStateService).storePending(eq(user), any(GameBinding.class));
        verify(twitchService, never()).updateChannel(any(), any());
    }

    @Test
    void onGameDetected_whenBindingIncomplete_andNoFallbackConfigured_skipsUpdate() {
        binding.setStatus(GameBinding.Status.INCOMPLETE);
        when(bindingService.resolveOrCreate(eq(user), any())).thenReturn(binding);

        UserSettings settings = new UserSettings();
        // incompleteFallbackTwitchGameId is null — no fallback configured
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        DetectedGame game = new DetectedGame("g1", GameBinding.SourceType.STEAM, "Unknown Game");
        listener.onGameDetected(new GameDetectedEvent(this, user, game));

        verify(twitchService, never()).updateChannel(any(), any());
        verify(streamStateService, never()).storePending(any(), any());
    }
```

- [ ] **Step 2.2: Run the 3 new tests to confirm they FAIL**

```bash
./gradlew test --tests "fr.enimaloc.catapult.event.GameEventListenerLiveCheckTest.onGameDetected_whenBindingIncomplete*"
```

Expected: 3 tests FAIL — the current `onGameDetected()` skips INCOMPLETE bindings without calling `applyIncompleteFallback()`.

- [ ] **Step 2.3: Modify `GameEventListener.java`**

Replace the current `onGameDetected()` method and add `applyIncompleteFallback()`. The full updated file:

```java
package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameEventListener {

    private final BindingService bindingService;
    private final TwitchService twitchService;
    private final UserSettingsRepository userSettingsRepository;
    private final StreamStateService streamStateService;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("GameDetectedEvent for user {}: {}", user.getId(), event.getDetectedGame().getSourceName());

        GameBinding binding = bindingService.resolveOrCreate(user, event.getDetectedGame());

        if (binding.isIgnored()) {
            log.debug("Binding is ignored — skipping update for user {}", user.getId());
            return;
        }

        if (binding.getStatus() == GameBinding.Status.INCOMPLETE) {
            log.debug("Binding is INCOMPLETE — checking fallback for user {}", user.getId());
            applyIncompleteFallback(user);
            return;
        }

        if (streamStateService.isLive(user)) {
            twitchService.updateChannel(user, binding);
        } else {
            streamStateService.storePending(user, binding);
            log.debug("User {} not live — stored pending binding for game {}",
                user.getId(), binding.getSourceName());
        }
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        UserAccount user = event.getUser();
        log.debug("NoGameDetectedEvent for user {}", user.getId());

        if (!streamStateService.isLive(user)) {
            log.debug("User {} not live — skipping no-game fallback", user.getId());
            return;
        }

        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getNoGameTwitchGameId() != null && !settings.getNoGameTwitchGameId().isBlank()) {
                GameBinding fallbackBinding = new GameBinding();
                fallbackBinding.setUser(user);
                fallbackBinding.setSourceType(GameBinding.SourceType.MANUAL);
                fallbackBinding.setSourceName("no-game-fallback");
                fallbackBinding.setTwitchGameId(settings.getNoGameTwitchGameId());
                fallbackBinding.setTwitchGameName(settings.getNoGameTwitchGameName());
                fallbackBinding.setStatus(GameBinding.Status.MANUAL);

                twitchService.updateChannel(user, fallbackBinding);
            }
        });
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOnlineEvent for user {}", user.getId());
        streamStateService.getPending(user).ifPresent(binding -> {
            twitchService.updateChannel(user, binding);
            streamStateService.clearPending(user);
        });
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        UserAccount user = event.getUser();
        log.debug("StreamOfflineEvent for user {}", user.getId());
        twitchService.resetToDefault(user);
        streamStateService.clearPending(user);
    }

    private void applyIncompleteFallback(UserAccount user) {
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getIncompleteFallbackTwitchGameId() == null
                    || settings.getIncompleteFallbackTwitchGameId().isBlank()) {
                log.debug("No incomplete fallback configured for user {} — skipping", user.getId());
                return;
            }
            GameBinding fallback = new GameBinding();
            fallback.setUser(user);
            fallback.setSourceType(GameBinding.SourceType.MANUAL);
            fallback.setSourceName("incomplete-fallback");
            fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
            fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
            fallback.getCcls().addAll(settings.getIncompleteFallbackCcls());
            fallback.setStatus(GameBinding.Status.MANUAL);

            if (streamStateService.isLive(user)) {
                twitchService.updateChannel(user, fallback);
            } else {
                streamStateService.storePending(user, fallback);
                log.debug("User {} not live — stored incomplete fallback as pending", user.getId());
            }
        });
    }
}
```

- [ ] **Step 2.4: Run all GameEventListener tests**

```bash
./gradlew test --tests "fr.enimaloc.catapult.event.GameEventListenerLiveCheckTest"
```

Expected: All 10 tests PASS (7 existing + 3 new).

- [ ] **Step 2.5: Commit**

```bash
git add src/test/java/fr/enimaloc/catapult/event/GameEventListenerLiveCheckTest.java \
        src/main/java/fr/enimaloc/catapult/event/GameEventListener.java
git commit -m "feat: apply incomplete fallback game when binding is INCOMPLETE"
```

---

## Task 3: UI — Fragment, endpoints, template inclusion

**Files:**
- Modify: `src/main/resources/lang/messages.properties`
- Modify: `src/main/resources/lang/messages_fr.properties`
- Create: `src/main/resources/templates/fragments/incomplete-fallback-settings.html`
- Modify: `src/main/java/fr/enimaloc/catapult/web/ChannelController.java`
- Modify: `src/main/resources/templates/app.html`
- Modify: `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java`

- [ ] **Step 3.1: Add i18n keys to `messages.properties`**

Append to the end of `src/main/resources/lang/messages.properties`:

```properties
incomplete_fallback.title=Default category (incomplete game)
incomplete_fallback.subtitle=Category and content labels applied automatically when a detected game cannot be resolved to a Twitch category.
```

- [ ] **Step 3.2: Add i18n keys to `messages_fr.properties`**

Append to the end of `src/main/resources/lang/messages_fr.properties`:

```properties
incomplete_fallback.title=Catégorie par défaut (jeu incomplet)
incomplete_fallback.subtitle=Catégorie et labels de contenu appliqués automatiquement quand un jeu détecté ne peut pas être associé à une catégorie Twitch.
```

- [ ] **Step 3.3: Write the failing controller test**

Add to `src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java` (after the last `@Test` method, before the closing `}`):

```java
    @Test
    void fragmentIncompleteFallbackSettings_returns200() throws Exception {
        when(channelAccessService.canAccess(owner, owner)).thenReturn(true);

        mockMvc.perform(get("/channels/streamer/fragments/incomplete-fallback-settings")
                .with(authentication(ownerAuth)))
            .andExpect(status().isOk())
            .andExpect(view().name("fragments/incomplete-fallback-settings :: incomplete-fallback-settings"));
    }
```

- [ ] **Step 3.4: Run the new test to confirm it FAILS**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.ChannelControllerTest.fragmentIncompleteFallbackSettings_returns200"
```

Expected: FAIL with 404 — endpoint does not exist yet.

- [ ] **Step 3.5: Create `incomplete-fallback-settings.html` fragment**

Create file `src/main/resources/templates/fragments/incomplete-fallback-settings.html`:

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<body>
<!--/*@thymesvar id="incompleteFallbackSettings" type="fr.enimaloc.catapult.domain.UserSettings"*/-->
<!--/*@thymesvar id="availableCcls" type="java.util.List"*/-->
<section th:fragment="incomplete-fallback-settings" class="card">
    <div class="card-header">
        <h3 th:text="#{incomplete_fallback.title}">Catégorie par défaut (jeu incomplet)</h3>
    </div>
    <p class="text-muted mb-16" th:text="#{incomplete_fallback.subtitle}">Catégorie appliquée quand un jeu ne peut pas être résolu.</p>

    <form th:action="@{/channels/{u}/settings/incomplete-fallback(u=${channelUsername})}" method="post"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <div class="inline-edit-fields">
            <div class="inline-edit-field">
                <label for="gameSearch-incompleteGame" th:text="#{bindings.edit.twitch_category}">Catégorie Twitch</label>
                <div class="search-wrapper">
                    <input type="text"
                           id="gameSearch-incompleteGame"
                           th:attr="data-search-url=@{/channels/{u}/api/games/search(u=${channelUsername})}"
                           th:value="${incompleteFallbackSettings.incompleteFallbackTwitchGameName}"
                           th:placeholder="#{common.search_placeholder}"
                           autocomplete="off"
                           oninput="debouncedSearch(event, 'incompleteGame')">
                    <ul id="gameResults-incompleteGame" class="game-results"></ul>
                </div>
                <input type="hidden" id="twitchGameId-incompleteGame"
                       name="twitchGameId"
                       th:value="${incompleteFallbackSettings.incompleteFallbackTwitchGameId}">
                <input type="hidden" id="twitchGameName-incompleteGame"
                       name="twitchGameName"
                       th:value="${incompleteFallbackSettings.incompleteFallbackTwitchGameName}">
            </div>
            <div class="inline-edit-field">
                <label th:text="#{bindings.edit.ccls}">CCLs</label>
                <div class="ccl-checkboxes">
                    <div th:each="ccl : ${availableCcls}">
                        <label>
                            <input type="checkbox" name="ccls"
                                   th:value="${ccl.id}"
                                   th:checked="${incompleteFallbackSettings.incompleteFallbackCcls.contains(ccl.id)}">
                            <span th:text="${ccl.name}"></span>
                        </label>
                    </div>
                </div>
            </div>
        </div>
        <div class="inline-edit-actions" style="margin-top:12px">
            <button type="submit" class="btn btn-primary" th:text="#{common.save}">Enregistrer</button>
        </div>
    </form>
</section>
</body>
</html>
```

- [ ] **Step 3.6: Add 2 endpoints to `ChannelController.java`**

In `src/main/java/fr/enimaloc/catapult/web/ChannelController.java`, add these two methods inside the `// Settings actions` section (after `saveNoGameSettings()`, before the `// Owner-only actions` section):

```java
    @GetMapping("/channels/{username}/fragments/incomplete-fallback-settings")
    public String fragmentIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            Model model) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        model.addAttribute("channelUsername", username);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId())
            .orElseGet(UserSettings::new);
        model.addAttribute("incompleteFallbackSettings", settings);
        model.addAttribute("availableCcls", adminCclService.getAllCcls());
        return "fragments/incomplete-fallback-settings :: incomplete-fallback-settings";
    }

    @PostMapping("/channels/{username}/settings/incomplete-fallback")
    public String saveIncompleteFallbackSettings(
            @PathVariable String username,
            @AuthenticationPrincipal CatapultOAuth2User principal,
            @RequestParam(required = false) String twitchGameId,
            @RequestParam(required = false) String twitchGameName,
            @RequestParam(required = false) Set<String> ccls) {
        UserAccount channelUser = resolveAndCheck(username, principal);
        UserSettings settings = userSettingsRepository.findById(channelUser.getId()).orElse(null);
        if (settings == null) {
            settings = new UserSettings();
            settings.setUser(channelUser);
        }
        settings.setIncompleteFallbackTwitchGameId(twitchGameId);
        settings.setIncompleteFallbackTwitchGameName(twitchGameName);
        settings.getIncompleteFallbackCcls().clear();
        if (ccls != null) settings.getIncompleteFallbackCcls().addAll(ccls);
        userSettingsRepository.save(settings);
        return "redirect:/channels/" + channelUser.getTwitchUsername();
    }
```

- [ ] **Step 3.7: Include the new fragment in `app.html`**

In `src/main/resources/templates/app.html`, after the no-game-settings HTMX div (line 12–13), add:

```html
        <div th:attr="hx-get=@{/channels/{u}/fragments/incomplete-fallback-settings(u=${channelUsername})}" hx-trigger="load" hx-swap="outerHTML">
            <div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading}">Chargement…</p></div>
        </div>
```

The block in `app.html` after the change:

```html
        <div th:replace="~{fragments/connections :: connections}"></div>
        <div th:attr="hx-get=@{/channels/{u}/fragments/no-game-settings(u=${channelUsername})}" hx-trigger="load" hx-swap="outerHTML">
            <div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading}">Chargement…</p></div>
        </div>
        <div th:attr="hx-get=@{/channels/{u}/fragments/incomplete-fallback-settings(u=${channelUsername})}" hx-trigger="load" hx-swap="outerHTML">
            <div class="card"><p class="text-muted" style="padding:16px" th:text="#{app.loading}">Chargement…</p></div>
        </div>
        <div exp:show-for="sidebar-layout:control"><div th:replace="~{fragments/bot :: bot}"></div></div>
```

- [ ] **Step 3.8: Run the controller test to confirm it now PASSES**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.ChannelControllerTest.fragmentIncompleteFallbackSettings_returns200"
```

Expected: PASS.

- [ ] **Step 3.9: Run the full test suite**

```bash
./gradlew test
```

Expected: All tests PASS. Pay attention to `AppTemplateTest.app_noUnresolvedI18nKeys` — if it fails, ensure both `messages.properties` and `messages_fr.properties` have the `incomplete_fallback.*` keys.

- [ ] **Step 3.10: Commit**

```bash
git add src/main/resources/lang/messages.properties \
        src/main/resources/lang/messages_fr.properties \
        src/main/resources/templates/fragments/incomplete-fallback-settings.html \
        src/main/java/fr/enimaloc/catapult/web/ChannelController.java \
        src/main/resources/templates/app.html \
        src/test/java/fr/enimaloc/catapult/web/ChannelControllerTest.java
git commit -m "feat: add incomplete fallback settings UI"
```
