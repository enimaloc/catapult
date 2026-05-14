# Global Process Rules — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter des règles globales admin (process → jeu) avec matching regex et prédicats, qui s'appliquent en fallback après les bindings utilisateur.

**Architecture:** `ProcessBinding.user` devient nullable (`NULL` = règle globale). Un flag `isRegex` permet le matching regex. `ObsGameGetter` insère une étape 2 entre les bindings user et le fallback IGDB. `AdminProcessRuleController` expose le CRUD admin sur `/admin/process-rules`.

**Tech Stack:** Spring Boot, Spring Data JPA, Thymeleaf, Flyway, JUnit 5, Mockito, AssertJ.

---

## Fichiers

| Fichier | Action |
|---|---|
| `src/main/resources/db/migration/V16__global_process_rules.sql` | Créer |
| `src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java` | Modifier |
| `src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java` | Modifier |
| `src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java` | Modifier |
| `src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java` | Modifier |
| `src/main/java/fr/enimaloc/catapult/web/AdminProcessRuleController.java` | Créer |
| `src/test/java/fr/enimaloc/catapult/web/AdminProcessRuleControllerTest.java` | Créer |
| `src/main/resources/templates/admin/global-process-rules.html` | Créer |

---

## Task 1 : Migration SQL V16

**Files:**
- Create: `src/main/resources/db/migration/V16__global_process_rules.sql`

- [ ] **Step 1 : Créer la migration**

```sql
-- ============================================================
-- V16 — Global process rules : règles admin sans propriétaire
-- ============================================================

-- Rendre user_id nullable (NULL = règle globale admin)
ALTER TABLE process_binding ALTER COLUMN user_id DROP NOT NULL;

-- Remplacer la contrainte unique globale par une contrainte partielle
-- (les règles globales n'ont pas de contrainte d'unicité sur process_name)
ALTER TABLE process_binding DROP CONSTRAINT process_binding_user_id_process_name_key;
CREATE UNIQUE INDEX uq_process_binding_user
    ON process_binding (user_id, process_name)
    WHERE user_id IS NOT NULL;

-- Flag pour distinguer nom exact vs pattern regex
ALTER TABLE process_binding ADD COLUMN is_regex BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2 : Commit**

```bash
git add src/main/resources/db/migration/V16__global_process_rules.sql
git commit -m "feat: add V16 migration for global process rules"
```

---

## Task 2 : Entité ProcessBinding

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java`

- [ ] **Step 1 : Mettre à jour l'entité**

Remplacer le contenu complet de `ProcessBinding.java` :

```java
package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "process_binding")
@Getter
@Setter
public class ProcessBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(name = "process_name", nullable = false)
    private String processName;

    @Column(name = "is_regex", nullable = false)
    private boolean regex = false;

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

    @OneToMany(mappedBy = "binding", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC, id ASC")
    private List<ProcessPredicate> predicates = new ArrayList<>();

    public boolean isGlobal() {
        return user == null;
    }
}
```

> Note : la contrainte `@UniqueConstraint` est supprimée de l'annotation `@Table` car la contrainte partielle est gérée par la migration SQL.
> Lombok génère `isRegex()` (getter) et `setRegex(boolean)` (setter) pour le champ `boolean regex`.

- [ ] **Step 2 : Corriger ObsSetupController (null-safety)**

Dans `src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java`, mettre à jour les deux guards qui appellent `pb.getUser().getId()` sans null-check :

`deleteProcessBinding` — remplacer :
```java
if (pb.getUser().getId().equals(principal.getUserAccount().getId())) {
    processBindingRepository.delete(pb);
}
```
par :
```java
if (pb.getUser() != null && pb.getUser().getId().equals(principal.getUserAccount().getId())) {
    processBindingRepository.delete(pb);
}
```

`addPredicate` — remplacer :
```java
if (!pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
```
par :
```java
if (pb.getUser() == null || !pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
```

`deletePredicate` — remplacer :
```java
if (!pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
```
par :
```java
if (pb.getUser() == null || !pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
```

- [ ] **Step 3 : Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/domain/ProcessBinding.java \
        src/main/java/fr/enimaloc/catapult/web/ObsSetupController.java
git commit -m "feat: make ProcessBinding user nullable, add isRegex and isGlobal"
```

---

## Task 3 : Repository — findByUserIsNull

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java`

- [ ] **Step 1 : Ajouter la méthode**

```java
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

    List<ProcessBinding> findByUserAndProcessNameIn(UserAccount user, Collection<String> processNames);

    List<ProcessBinding> findByUserIsNull();
}
```

- [ ] **Step 2 : Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/repository/ProcessBindingRepository.java
git commit -m "feat: add findByUserIsNull to ProcessBindingRepository"
```

---

## Task 4 : ObsGameGetter — étape globale + tests

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java`
- Modify: `src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java`

- [ ] **Step 1 : Écrire les tests qui échouent**

Ajouter ces trois méthodes à la fin de `ObsGameGetterTest` (après le helper `binding()`), avant la fermeture de classe :

```java
@Test
void getCurrentGame_globalRuleMatchesExact_whenNoUserBinding() {
    ProcessBinding globalRule = globalBinding("hl2", "Half-Life 2");

    when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
    when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
    when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
    when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

    Optional<DetectedGame> result = getter.getCurrentGame(user);

    assertThat(result).isPresent();
    assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
}

@Test
void getCurrentGame_globalRuleMatchesRegex_whenNoUserBinding() {
    ProcessBinding globalRule = globalBinding("hl.*", "Half-Life");
    globalRule.setRegex(true);

    when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
    when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
    when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
    when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

    Optional<DetectedGame> result = getter.getCurrentGame(user);

    assertThat(result).isPresent();
    assertThat(result.get().getSourceName()).isEqualTo("Half-Life");
}

@Test
void getCurrentGame_userBindingTakesPriorityOverGlobalRule() {
    ProcessBinding userBinding = binding("hl2", "Half-Life 2 User");
    ProcessBinding globalRule = globalBinding("hl2", "Half-Life 2 Global");

    when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
    when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of(userBinding));
    when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

    Optional<DetectedGame> result = getter.getCurrentGame(user);

    assertThat(result).isPresent();
    assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2 User");
    // findByUserIsNull should never be called when a user binding matches
}

@Test
void getCurrentGame_globalRuleRegexNoMatch_fallsBackToIgdb() {
    ProcessBinding globalRule = globalBinding("minecraft.*", "Minecraft");
    globalRule.setRegex(true);

    when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
    when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
    when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
    when(igdbService.findByWindowsExecutable("hl2"))
            .thenReturn(Optional.of(new IgdbService.IgdbGame("51", "Half-Life 2")));

    Optional<DetectedGame> result = getter.getCurrentGame(user);

    assertThat(result).isPresent();
    assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
}
```

Ajouter le helper `globalBinding` après le helper `binding` existant :

```java
private static ProcessBinding globalBinding(String pattern, String gameName) {
    ProcessBinding pb = new ProcessBinding();
    pb.setProcessName(pattern);
    pb.setTwitchGameId("70");
    pb.setTwitchGameName(gameName);
    // user est null => isGlobal() == true
    return pb;
}
```

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

```bash
./mvnw test -pl . -Dtest=ObsGameGetterTest -q
```

Expected: les 4 nouveaux tests FAIL (méthodes `findByUserIsNull` / `matchGlobal` non implémentées).

- [ ] **Step 3 : Mettre à jour ObsGameGetter**

Remplacer le contenu complet de `ObsGameGetter.java` :

```java
package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.ObsProcessCache;
import fr.enimaloc.catapult.service.ObsSession;
import fr.enimaloc.catapult.service.ProcessPredicateEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("obs.enabled")
public class ObsGameGetter implements GameGetter {

    private final ObsProcessCache obsProcessCache;
    private final ProcessBindingRepository processBindingRepository;
    private final IgdbService igdbService;
    private final ProcessPredicateEvaluator predicateEvaluator;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        ObsSession session = obsProcessCache.getSession(user);
        if (session.processes().isEmpty()) return Optional.empty();

        Set<String> names = session.processes().stream()
                .map(ObsSession.ProcessSnapshot::name)
                .collect(Collectors.toSet());

        // Step 1: bindings utilisateur (exact match + prédicats)
        List<ProcessBinding> candidates = processBindingRepository.findByUserAndProcessNameIn(user, names);
        Optional<DetectedGame> fromBinding = session.processes().stream()
                .flatMap(proc -> candidates.stream()
                        .filter(b -> b.getProcessName().equals(proc.name()))
                        .filter(b -> predicateEvaluator.evaluate(b, proc, session))
                        .findFirst()
                        .map(b -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, b.getTwitchGameName()))
                        .stream())
                .findFirst();
        if (fromBinding.isPresent()) return fromBinding;

        // Step 2: règles globales admin (regex ou exact + prédicats)
        List<ProcessBinding> globalRules = processBindingRepository.findByUserIsNull();
        if (!globalRules.isEmpty()) {
            Optional<DetectedGame> fromGlobal = matchGlobal(globalRules, session.processes().stream().toList(), session);
            if (fromGlobal.isPresent()) return fromGlobal;
        }

        // Step 3: fallback IGDB par nom d'exe
        return session.processes().stream()
                .map(proc -> igdbService.findByWindowsExecutable(proc.name())
                        .map(game -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, game.name())))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<DetectedGame> matchGlobal(
            List<ProcessBinding> globalRules,
            List<ObsSession.ProcessSnapshot> processes,
            ObsSession session) {
        return processes.stream()
                .flatMap(proc -> globalRules.stream()
                        .filter(rule -> matchesPattern(rule, proc.name()))
                        .filter(rule -> predicateEvaluator.evaluate(rule, proc, session))
                        .findFirst()
                        .map(rule -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, rule.getTwitchGameName()))
                        .stream())
                .findFirst();
    }

    private boolean matchesPattern(ProcessBinding rule, String name) {
        if (!rule.isRegex()) return rule.getProcessName().equals(name);
        return name.matches(rule.getProcessName());
    }
}
```

- [ ] **Step 4 : Lancer tous les tests ObsGameGetter**

```bash
./mvnw test -pl . -Dtest=ObsGameGetterTest -q
```

Expected: tous les tests PASS.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/getter/ObsGameGetter.java \
        src/test/java/fr/enimaloc/catapult/getter/ObsGameGetterTest.java
git commit -m "feat: add global admin rules as fallback step in ObsGameGetter"
```

---

## Task 5 : AdminProcessRuleController + tests

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/web/AdminProcessRuleController.java`
- Create: `src/test/java/fr/enimaloc/catapult/web/AdminProcessRuleControllerTest.java`

- [ ] **Step 1 : Écrire le test**

Créer `src/test/java/fr/enimaloc/catapult/web/AdminProcessRuleControllerTest.java` :

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminProcessRuleControllerTest {

    @Mock private ProcessBindingRepository processBindingRepository;
    @InjectMocks private AdminProcessRuleController controller;

    @Test
    void addRule_exactMatch_savesWithoutRegexFlag() {
        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);

        controller.addRule("hl2", false, "51", "Half-Life 2", new RedirectAttributesModelMap());

        verify(processBindingRepository).save(captor.capture());
        ProcessBinding saved = captor.getValue();
        assertThat(saved.getProcessName()).isEqualTo("hl2");
        assertThat(saved.isRegex()).isFalse();
        assertThat(saved.getTwitchGameId()).isEqualTo("51");
        assertThat(saved.getTwitchGameName()).isEqualTo("Half-Life 2");
        assertThat(saved.getUser()).isNull();
    }

    @Test
    void addRule_validRegex_savesWithRegexFlag() {
        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);

        controller.addRule("hl.*", true, "51", "Half-Life", new RedirectAttributesModelMap());

        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().isRegex()).isTrue();
        assertThat(captor.getValue().getProcessName()).isEqualTo("hl.*");
    }

    @Test
    void addRule_invalidRegex_doesNotSave() {
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        controller.addRule("[invalid(", true, "51", "Game", redirectAttributes);

        verify(processBindingRepository, never()).save(any());
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("error");
    }

    @Test
    void deleteRule_globalRule_deletes() {
        ProcessBinding rule = new ProcessBinding();
        UUID id = UUID.randomUUID();
        // user est null => isGlobal() == true

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deleteRule(id);

        verify(processBindingRepository).delete(rule);
    }

    @Test
    void deleteRule_userBinding_doesNotDelete() {
        ProcessBinding rule = new ProcessBinding();
        rule.setUser(new fr.enimaloc.catapult.domain.UserAccount());
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deleteRule(id);

        verify(processBindingRepository, never()).delete(any());
    }

    @Test
    void addPredicate_globalRule_savesPredicate() {
        ProcessBinding rule = new ProcessBinding();
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.addPredicate(id,
                ProcessPredicate.PredicateType.WORKING_DIRECTORY,
                ProcessPredicate.Connector.AND,
                null,
                "C:\\Games",
                ProcessPredicate.OsTarget.WINDOWS);

        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);
        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().getPredicates()).hasSize(1);
        assertThat(captor.getValue().getPredicates().get(0).getValue()).isEqualTo("C:\\Games");
    }

    @Test
    void deletePredicate_globalRule_removesPredicate() {
        ProcessBinding rule = new ProcessBinding();
        UUID predId = UUID.randomUUID();
        ProcessPredicate pred = new ProcessPredicate();
        pred.setId(predId);
        pred.setType(ProcessPredicate.PredicateType.WORKING_DIRECTORY);
        pred.setValue("C:\\Games");
        pred.setOsTarget(ProcessPredicate.OsTarget.ALL);
        pred.setConnector(ProcessPredicate.Connector.AND);
        rule.getPredicates().add(pred);
        UUID id = UUID.randomUUID();

        when(processBindingRepository.findById(id)).thenReturn(Optional.of(rule));

        controller.deletePredicate(id, predId);

        ArgumentCaptor<ProcessBinding> captor = ArgumentCaptor.forClass(ProcessBinding.class);
        verify(processBindingRepository).save(captor.capture());
        assertThat(captor.getValue().getPredicates()).isEmpty();
    }
}
```

- [ ] **Step 2 : Lancer les tests pour vérifier qu'ils échouent**

```bash
./mvnw test -pl . -Dtest=AdminProcessRuleControllerTest -q
```

Expected: FAIL (classe `AdminProcessRuleController` inexistante).

- [ ] **Step 3 : Créer AdminProcessRuleController**

Créer `src/main/java/fr/enimaloc/catapult/web/AdminProcessRuleController.java` :

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Controller
@RequestMapping("/admin/process-rules")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AdminProcessRuleController {

    private final ProcessBindingRepository processBindingRepository;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("globalRules", processBindingRepository.findByUserIsNull());
        model.addAttribute("predicateTypes", ProcessPredicate.PredicateType.values());
        model.addAttribute("osTargets", ProcessPredicate.OsTarget.values());
        model.addAttribute("connectors", ProcessPredicate.Connector.values());
        return "admin/global-process-rules";
    }

    @PostMapping
    public String addRule(
            @RequestParam @NotBlank @Size(max = 255) String processName,
            @RequestParam(defaultValue = "false") boolean isRegex,
            @RequestParam @NotBlank @Size(max = 50) String twitchGameId,
            @RequestParam(required = false) @Size(max = 255) String twitchGameName,
            RedirectAttributes redirectAttributes) {

        if (isRegex) {
            try {
                Pattern.compile(processName);
            } catch (PatternSyntaxException e) {
                redirectAttributes.addFlashAttribute("error", "Pattern regex invalide : " + e.getMessage());
                return "redirect:/admin/process-rules";
            }
        }

        ProcessBinding rule = new ProcessBinding();
        rule.setProcessName(processName);
        rule.setRegex(isRegex);
        rule.setTwitchGameId(twitchGameId);
        rule.setTwitchGameName(twitchGameName);
        processBindingRepository.save(rule);
        log.info("Global process rule added: pattern='{}' regex={} game='{}'", processName, isRegex, twitchGameName);
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/delete")
    public String deleteRule(@PathVariable UUID id) {
        processBindingRepository.findById(id).ifPresent(rule -> {
            if (rule.isGlobal()) {
                processBindingRepository.delete(rule);
                log.info("Global process rule deleted: id={}", id);
            }
        });
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/predicates")
    public String addPredicate(
            @PathVariable UUID id,
            @RequestParam @NotNull ProcessPredicate.PredicateType type,
            @RequestParam @NotNull ProcessPredicate.Connector connector,
            @RequestParam(required = false) @Size(max = 255) String key,
            @RequestParam @NotBlank @Size(max = 500) String value,
            @RequestParam @NotNull ProcessPredicate.OsTarget osTarget) {

        processBindingRepository.findById(id).ifPresent(rule -> {
            if (!rule.isGlobal()) return;
            ProcessPredicate pred = new ProcessPredicate();
            pred.setBinding(rule);
            pred.setType(type);
            pred.setConnector(connector);
            pred.setKey(key);
            pred.setValue(value);
            pred.setOsTarget(osTarget);
            pred.setPosition(rule.getPredicates().size());
            rule.getPredicates().add(pred);
            processBindingRepository.save(rule);
        });
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/predicates/{predId}/delete")
    public String deletePredicate(@PathVariable UUID id, @PathVariable UUID predId) {
        processBindingRepository.findById(id).ifPresent(rule -> {
            if (!rule.isGlobal()) return;
            rule.getPredicates().removeIf(p -> p.getId().equals(predId));
            for (int i = 0; i < rule.getPredicates().size(); i++) {
                rule.getPredicates().get(i).setPosition(i);
            }
            processBindingRepository.save(rule);
        });
        return "redirect:/admin/process-rules";
    }
}
```

- [ ] **Step 4 : Lancer les tests**

```bash
./mvnw test -pl . -Dtest=AdminProcessRuleControllerTest -q
```

Expected: tous les tests PASS.

- [ ] **Step 5 : Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/web/AdminProcessRuleController.java \
        src/test/java/fr/enimaloc/catapult/web/AdminProcessRuleControllerTest.java
git commit -m "feat: add AdminProcessRuleController for global process rules CRUD"
```

---

## Task 6 : Template admin

**Files:**
- Create: `src/main/resources/templates/admin/global-process-rules.html`

- [ ] **Step 1 : Créer le template**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Catapult — Règles globales processus</title>
    <link rel="stylesheet" th:href="@{/css/app.css}">
</head>
<body>
    <nav class="navbar">
        <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
        <div class="nav-links">
            <a th:href="@{/app}">App</a>
            <a th:href="@{/admin/ccl}">Admin CCL</a>
            <a th:href="@{/admin/process-rules}" class="active">Règles processus</a>
            <form th:action="@{/logout}" method="post" style="display:inline">
                <button type="submit" class="btn-link">Déconnexion</button>
            </form>
        </div>
    </nav>

    <main class="container">
        <h2>Administration — Règles processus globales</h2>
        <p class="text-muted" style="margin-bottom:1.5rem">
            Ces règles s'appliquent à <strong>tous les utilisateurs</strong> en fallback,
            si l'utilisateur n'a pas de binding personnel pour le processus détecté.
        </p>

        <!-- Message d'erreur -->
        <div th:if="${error}" style="background:#fee;border:1px solid #fcc;padding:10px 14px;border-radius:4px;margin-bottom:1rem;color:#c00"
             th:text="${error}"></div>

        <!-- Tableau des règles existantes -->
        <div th:if="${globalRules.empty}" class="text-muted" style="margin-bottom:1.5rem">
            Aucune règle globale configurée.
        </div>

        <div th:unless="${globalRules.empty}" style="margin-bottom:1.5rem">
            <table style="width:100%;border-collapse:collapse">
                <thead>
                    <tr style="border-bottom:2px solid #eee;text-align:left">
                        <th style="padding:8px">Pattern processus</th>
                        <th style="padding:8px">Regex</th>
                        <th style="padding:8px">Jeu Twitch</th>
                        <th style="padding:8px">Prédicats</th>
                        <th style="padding:8px"></th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="rule : ${globalRules}" style="border-bottom:1px solid #f0f0f0;vertical-align:top">
                        <td style="padding:8px"><code th:text="${rule.processName}"></code></td>
                        <td style="padding:8px">
                            <span th:if="${rule.regex}"
                                  style="background:#e8f4fd;color:#1a7abf;padding:2px 7px;border-radius:3px;font-size:.8em;font-weight:600">regex</span>
                            <span th:unless="${rule.regex}" style="color:#bbb;font-size:.85em">exact</span>
                        </td>
                        <td style="padding:8px" th:text="${rule.twitchGameName}"></td>

                        <!-- Prédicats -->
                        <td style="padding:8px;min-width:260px">
                            <details>
                                <summary style="cursor:pointer;font-size:.85em;color:#555">
                                    <span th:if="${rule.predicates.empty}">Aucun prédicat</span>
                                    <span th:unless="${rule.predicates.empty}" th:text="${rule.predicates.size()} + ' prédicat(s)'"></span>
                                </summary>

                                <div th:unless="${rule.predicates.empty}" style="margin-top:6px">
                                    <table style="width:100%;font-size:.8em;border-collapse:collapse">
                                        <thead>
                                            <tr style="border-bottom:1px solid #eee;color:#888">
                                                <th style="padding:4px">Lien</th>
                                                <th style="padding:4px">Type</th>
                                                <th style="padding:4px">Clé</th>
                                                <th style="padding:4px">Valeur</th>
                                                <th style="padding:4px">OS</th>
                                                <th style="padding:4px"></th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            <tr th:each="pred, iterStat : ${rule.predicates}" style="border-bottom:1px solid #f5f5f5">
                                                <td style="padding:4px;color:#888">
                                                    <span th:if="${iterStat.first}">—</span>
                                                    <span th:unless="${iterStat.first}" th:text="${pred.connector}"></span>
                                                </td>
                                                <td style="padding:4px" th:text="${pred.type}"></td>
                                                <td style="padding:4px"><code th:text="${pred.key}"></code></td>
                                                <td style="padding:4px"><code th:text="${pred.value}"></code></td>
                                                <td style="padding:4px" th:text="${pred.osTarget}"></td>
                                                <td style="padding:4px">
                                                    <form th:action="@{/admin/process-rules/{id}/predicates/{predId}/delete(id=${rule.id},predId=${pred.id})}" method="post">
                                                        <button type="submit" class="btn btn-sm btn-danger" style="padding:2px 6px;font-size:.75em">✕</button>
                                                    </form>
                                                </td>
                                            </tr>
                                        </tbody>
                                    </table>
                                </div>

                                <!-- Formulaire ajout prédicat -->
                                <form th:action="@{/admin/process-rules/{id}/predicates(id=${rule.id})}" method="post"
                                      style="margin-top:8px;display:grid;grid-template-columns:auto auto 1fr auto auto auto;gap:6px;align-items:end;font-size:.82em">
                                    <div style="display:flex;flex-direction:column;gap:2px">
                                        <label style="color:#777;font-weight:600">Lien</label>
                                        <select name="connector" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                            <option th:each="c : ${connectors}" th:value="${c}" th:text="${c}"></option>
                                        </select>
                                    </div>
                                    <div style="display:flex;flex-direction:column;gap:2px">
                                        <label style="color:#777;font-weight:600">Type</label>
                                        <select name="type" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                            <option th:each="t : ${predicateTypes}" th:value="${t}" th:text="${t}"></option>
                                        </select>
                                    </div>
                                    <div style="display:flex;flex-direction:column;gap:2px">
                                        <label style="color:#777;font-weight:600">Valeur</label>
                                        <input type="text" name="value" required placeholder="ex: %APPDATA%\MonApp"
                                               style="padding:4px 8px;border:1px solid #ccc;border-radius:4px">
                                    </div>
                                    <div style="display:flex;flex-direction:column;gap:2px">
                                        <label style="color:#777;font-weight:600">Clé (ENV_VAR)</label>
                                        <input type="text" name="key" placeholder="ex: STEAM_APPID"
                                               style="padding:4px 8px;border:1px solid #ccc;border-radius:4px;width:100px">
                                    </div>
                                    <div style="display:flex;flex-direction:column;gap:2px">
                                        <label style="color:#777;font-weight:600">OS</label>
                                        <select name="osTarget" style="padding:4px 6px;border:1px solid #ccc;border-radius:4px">
                                            <option th:each="o : ${osTargets}" th:value="${o}" th:text="${o}"></option>
                                        </select>
                                    </div>
                                    <button type="submit" class="btn btn-sm btn-primary" style="align-self:end">+ Ajouter</button>
                                </form>
                            </details>
                        </td>

                        <td style="padding:8px;text-align:right">
                            <form th:action="@{/admin/process-rules/{id}/delete(id=${rule.id})}" method="post">
                                <button type="submit" class="btn btn-sm btn-danger">Supprimer</button>
                            </form>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Formulaire ajout règle -->
        <div class="card" style="padding:1.5rem">
            <h4 style="margin-top:0;margin-bottom:1rem">Ajouter une règle</h4>
            <p class="text-muted" style="font-size:.85em;margin-bottom:1rem">
                <strong>Note :</strong> le pattern est comparé au nom du processus tel que reçu par OBS (ex: <code>hl2.exe</code> sur Windows).
                Avec regex activé, <code>String.matches()</code> est utilisé — le pattern est ancré (<code>^...$</code>) :
                utilisez <code>.*minecraft.*\.exe</code> pour un match partiel.
            </p>
            <form th:action="@{/admin/process-rules}" method="post"
                  style="display:flex;gap:12px;align-items:flex-end;flex-wrap:wrap">
                <div style="display:flex;flex-direction:column;gap:4px">
                    <label style="font-size:.8em;font-weight:600;color:#555">Pattern processus</label>
                    <input type="text" name="processName" placeholder="ex: hl2.exe ou .*minecraft.*" required
                           style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
                </div>
                <div style="display:flex;flex-direction:column;gap:4px">
                    <label style="font-size:.8em;font-weight:600;color:#555">Regex</label>
                    <label style="display:flex;align-items:center;gap:6px;padding:7px 0">
                        <input type="checkbox" name="isRegex" value="true">
                        <span style="font-size:.9em">Pattern regex</span>
                    </label>
                </div>
                <div style="display:flex;flex-direction:column;gap:4px;position:relative">
                    <label style="font-size:.8em;font-weight:600;color:#555">Jeu Twitch</label>
                    <div class="search-wrapper">
                        <input type="text" id="adminGameSearch" placeholder="Rechercher un jeu…"
                               oninput="adminSearch(event)" autocomplete="off"
                               style="padding:6px 10px;border:1px solid #ccc;border-radius:4px;font-size:.9em;width:220px">
                        <ul id="gameResults-admin" class="game-results"></ul>
                    </div>
                    <input type="hidden" name="twitchGameId" id="twitchGameId-admin" required>
                    <input type="hidden" name="twitchGameName" id="twitchGameName-admin">
                </div>
                <button type="submit" class="btn btn-primary">Ajouter</button>
            </form>
        </div>
    </main>

    <script>
    (function() {
        var _timer = null;
        window.adminSearch = function(event) {
            var q = event.target.value.trim();
            var results = document.getElementById('gameResults-admin');
            if (q.length < 2) { results.style.display = 'none'; return; }
            clearTimeout(_timer);
            _timer = setTimeout(function() {
                fetch('/api/games/search?q=' + encodeURIComponent(q))
                    .then(function(r) { return r.json(); })
                    .then(function(data) {
                        results.replaceChildren();
                        if (!data.length) { results.style.display = 'none'; return; }
                        data.forEach(function(game) {
                            var li = document.createElement('li');
                            li.textContent = game.name;
                            li.addEventListener('click', function() {
                                document.getElementById('twitchGameId-admin').value = game.id;
                                document.getElementById('twitchGameName-admin').value = game.name;
                                document.getElementById('adminGameSearch').value = game.name;
                                results.style.display = 'none';
                            });
                            results.appendChild(li);
                        });
                        results.style.display = 'block';
                    })
                    .catch(function() { results.style.display = 'none'; });
            }, 300);
        };
    })();
    </script>
</body>
</html>
```

- [ ] **Step 2 : Commit**

```bash
git add src/main/resources/templates/admin/global-process-rules.html
git commit -m "feat: add admin UI template for global process rules"
```

---

## Task 7 : Validation finale

- [ ] **Step 1 : Lancer tous les tests**

```bash
./mvnw test -q
```

Expected: BUILD SUCCESS, zéro échec.

- [ ] **Step 2 : Vérifier la compilation complète**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS.
