# A/B Testing — Provider abstraction

**Date:** 2026-05-27  
**Status:** Approved

## Contexte

Catapult dispose d'un système A/B testing complet (expériences, variantes, règles d'assignation, statistiques, NPS). Ce système est entièrement interne, stocké en PostgreSQL. L'objectif est d'introduire une abstraction "provider" permettant de déléguer la gestion et la résolution des expériences à un système externe (GitLab Feature Flags, GrowthBook, Unleash) tout en conservant le tracking d'événements local.

## Décisions de design

| Décision | Choix |
|---|---|
| Portée du provider | Résolution + gestion complète |
| Admin UI si externe | Redirect vers l'interface native du provider |
| Sélection du provider | `application.properties` (statique, sync au démarrage si changement) |
| Event tracking | Mirroiré : local + forwarded au provider (async) |
| Fallback | Provider interne si le provider externe est indisponible |

## Architecture

### Interface centrale

```java
public interface ExperimentProvider {
    String type();
    Optional<ExperimentVariant> getVariant(UserAccount user, String experimentKey);
    List<ExperimentSummary> listExperiments();
    Optional<String> adminUrl();
    void trackEvent(UserAccount user, String experimentKey, String eventKey);
    void importExperiments(List<Experiment> experiments);
    boolean isHealthy();

    // Optionnel — implémentation par défaut no-op
    default void trackFeedback(UserAccount user, String experimentKey, int npsScore) {}
}
```

**Contrats :**
- `getVariant()` ne lève jamais d'exception — retourne `Optional.empty()` si indisponible
- `adminUrl()` retourne `Optional.empty()` pour le provider interne
- `trackEvent()` est fire-and-forget — les failures sont loguées mais non propagées
- `importExperiments()` est idempotent

### DTO partagé

```java
public record ExperimentSummary(
    String key,
    String name,
    String status,
    int variantCount
) {}
```

### Composants

```
ExperimentProvider (interface)
├── InternalExperimentProvider    — wrapper ExperimentService existant
├── GitLabExperimentProvider      — protocole Unleash via GitLab API
├── GrowthBookExperimentProvider  — SDK GrowthBook + API REST
└── UnleashExperimentProvider     — SDK Unleash standalone

ExperimentProviderProperties     — @ConfigurationProperties "app.experiment"
ExperimentProviderBootstrap      — ApplicationRunner : sync au démarrage si provider changé
ActiveProviderHolder             — Spring bean exposant le provider actif
```

## Configuration

```properties
# Provider actif
app.experiment.provider=internal          # internal | gitlab | growthbook | unleash
app.experiment.fallback-to-internal=true  # fallback si provider externe indisponible

# GitLab Feature Flags (protocole Unleash)
app.experiment.gitlab.project-id=${GITLAB_PROJECT_ID}
app.experiment.gitlab.instance-id=${GITLAB_UNLEASH_INSTANCE_ID}
app.experiment.gitlab.private-token=${GITLAB_PRIVATE_TOKEN}
app.experiment.gitlab.admin-url=https://gitlab.com/{namespace}/{project}/-/feature_flags

# GrowthBook
app.experiment.growthbook.api-host=https://app.growthbook.io
app.experiment.growthbook.client-key=${GROWTHBOOK_CLIENT_KEY}
app.experiment.growthbook.api-key=${GROWTHBOOK_API_KEY}

# Unleash standalone
app.experiment.unleash.api-url=https://unleash.example.com
app.experiment.unleash.client-key=${UNLEASH_CLIENT_KEY}
```

Validation : si `app.experiment.provider=gitlab` et que les propriétés GitLab sont absentes, l'application refuse de démarrer avec un message d'erreur explicite (`@Validated` + `@NotBlank` conditionnels).

## Implémentations des providers

### InternalExperimentProvider
- Délègue `getVariant()` → `ExperimentService.getVariant()`
- Délègue `listExperiments()` → `ExperimentRepository.findAll()` mappé en `ExperimentSummary`
- `adminUrl()` → `Optional.empty()` (Catapult est l'admin)
- `importExperiments()` → crée des expériences DRAFT via le flux `ExperimentSynchronizer` existant
- `isHealthy()` → `true` (toujours disponible)

### GitLabExperimentProvider
- Endpoint Unleash : `https://gitlab.com/api/v4/feature_flags/unleash/{project_id}`
- `getVariant()` → GET `/client/features/{key}` avec header `Authorization: {instanceId}`
- `trackEvent()` → POST `/client/metrics`
- `importExperiments()` → POST `https://gitlab.com/api/v4/projects/{id}/feature_flags` avec `PRIVATE-TOKEN`
- `adminUrl()` → valeur de `app.experiment.gitlab.admin-url`
- `isHealthy()` → GET `/client/features` et vérifie HTTP 200

### GrowthBookExperimentProvider
- Utilise le SDK `io.growthbook.sdk:GrowthBook` (à ajouter aux dépendances)
- `getVariant()` → évalue l'expérience avec les attributs utilisateur : `id`, `account_age_days`, `has_steam`, `has_xbox`, `has_battlenet`
- `trackEvent()` → callback de tracking GrowthBook
- `importExperiments()` → POST `/api/v1/experiments` (API GrowthBook REST)
- `adminUrl()` → `app.experiment.growthbook.api-host`
- `isHealthy()` → GET `/api/v1/experiments?limit=1`

### UnleashExperimentProvider
- Utilise `io.getunleash:unleash-client-java` (à ajouter aux dépendances)
- `getVariant()` → `unleash.getVariant(key, context)` où `context` contient l'userId
- `trackEvent()` → Unleash Metrics API
- `importExperiments()` → API Admin Unleash `/api/admin/features`
- `adminUrl()` → `app.experiment.unleash.api-url`
- `isHealthy()` → `unleash.isEnabled("__health_check__")` ou GET `/api/client/features`

## Mécanisme de sync

`ExperimentProviderBootstrap` (ApplicationRunner, ordre élevé) :

1. Lit `app.experiment.provider`
2. Compare avec `system_settings["experiment.last-provider"]` (clé-valeur en base)
3. Si identique → skip
4. Si différent :
   a. Exporte toutes les expériences ACTIVE et PAUSED depuis la base interne
   b. Appelle `newProvider.importExperiments(experiments)`
   c. Met à jour `system_settings["experiment.last-provider"]`
   d. Logue : `N expériences importées, M erreurs`
5. Si `isHealthy()` → false ET `fallback-to-internal=true` → démarre sur InternalExperimentProvider + log warning

La table `system_settings` est une table clé/valeur simple (key VARCHAR PK, value TEXT) créée par migration Flyway si elle n'existe pas encore.

## Admin UI

**Provider interne :** comportement actuel inchangé sur `/admin/experiments`.

**Provider externe :**
```
GET /admin/experiments  →  redirect:{adminUrl()}
```

Un bandeau s'affiche sur toutes les pages admin (`/admin/**`) quand le provider est externe :
```
⚠ Expériences gérées via [GitLab] — [Ouvrir ↗]   |   Statut : ✓ healthy
```

Endpoint de statut (lecture seule) :
```
GET /admin/experiments/status
→ { "provider": "gitlab", "healthy": true, "lastSync": "2026-05-27T10:00:00Z" }
```

Ce endpoint alimente également un gauge Micrometer : `experiment.provider.healthy` (0/1).

## Event Mirroring

Point d'entrée : `ExperimentService.track()` (inchangé pour l'appelant).

```
track(user, experimentKey, eventKey)
├── [sync]  INSERT experiment_events (comportement actuel)
└── [async @Async] activeProvider.trackEvent(user, experimentKey, eventKey)
                   └── failure → log.warn + increment experiment.event.forward.error{provider}
```

Même pattern pour `trackFeedback()` (NPS) si le provider implémente la méthode.

## Fichiers à créer / modifier

| Fichier | Action |
|---|---|
| `experiment/provider/ExperimentProvider.java` | Créer — interface |
| `experiment/provider/ExperimentSummary.java` | Créer — DTO record |
| `experiment/provider/InternalExperimentProvider.java` | Créer — wrapper ExperimentService |
| `experiment/provider/GitLabExperimentProvider.java` | Créer |
| `experiment/provider/GrowthBookExperimentProvider.java` | Créer |
| `experiment/provider/UnleashExperimentProvider.java` | Créer |
| `experiment/provider/ActiveProviderHolder.java` | Créer — bean Spring |
| `config/ExperimentProviderProperties.java` | Créer — @ConfigurationProperties |
| `experiment/ExperimentProviderBootstrap.java` | Créer — ApplicationRunner sync |
| `web/AdminExperimentsController.java` | Modifier — redirect si adminUrl présent |
| `service/ExperimentService.java` | Modifier — async forward dans track() |
| `templates/admin/layout.html` ou `template.html` | Modifier — bandeau provider |
| `resources/db/migration/V25__system_settings.sql` | Créer — table system_settings |
| `build.gradle.kts` | Modifier — ajouter SDK GrowthBook + Unleash |

## Tests

- `InternalExperimentProvider` : tests unitaires wrappant un `ExperimentService` mocké
- `GitLabExperimentProvider` : tests avec MockWebServer (okhttp3) pour simuler l'API Unleash GitLab
- `GrowthBookExperimentProvider` : tests avec SDK GrowthBook en mode offline
- `UnleashExperimentProvider` : tests avec FakeUnleash du SDK officiel
- `ExperimentProviderBootstrap` : test d'intégration vérifiant la sync au changement de provider
- `AdminExperimentsController` : test que le redirect est bien émis quand `adminUrl()` est présent
