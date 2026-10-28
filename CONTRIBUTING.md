# Contribuer à Catapult

Merci de t'intéresser à Catapult. Ce document explique où ouvrir une PR, comment écrire les commits et comment
une release est publiée.

## Branches

| Branche | Rôle |
|---|---|
| `dev` | Intégration (branche par défaut). Toutes les PR de fonctionnalités et de correctifs arrivent ici. |
| `beta` | Pré-releases `vX.Y.Z-BETA.N`. Alimentée uniquement par la promotion `dev` → `beta`. |
| `main` | Releases stables `vX.Y.Z`. Alimentée par la promotion `beta` → `main` et par les hotfix. |

Ne pousse jamais directement sur ces trois branches : elles exigent une PR et les checks `build`, `test` et
`commitlint`.

## Proposer un changement

1. Crée une branche depuis `dev` : `feat/ma-fonctionnalite`, `fix/mon-correctif`, `docs/…`, `chore/…`.
2. Fais des commits ciblés (un commit par fonctionnalité, correctif ou refactor).
3. Ouvre une PR **vers `dev`**. Le modèle de PR rappelle la checklist.
4. Les checks doivent être verts ; la PR est ensuite fusionnée dans `dev`.

Les PR ouvertes vers `beta` ou `main` sont réservées aux promotions préparées par le bot et aux hotfix.

## Conventional Commits

Les messages de commit suivent [Conventional Commits](https://www.conventionalcommits.org/fr/v1.0.0/) ; le check
`commitlint` le vérifie sur chaque PR.

```
type(portée optionnelle): description à l'impératif
```

- Types usuels : `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`.
- `feat` donne une version mineure, `fix`, `perf` et les autres types un patch ; `!` après le type (ou un pied
  `BREAKING CHANGE:`) donne une version majeure.
- En-tête de 99 caractères au plus, lignes du corps de 100 caractères au plus.

La version d'une release est calculée à partir de ces messages : un titre précis donne un changelog lisible.

## Comment une release part

1. Dès qu'un commit arrive sur `dev`, un bot ouvre (ou met à jour) une PR **en draft**
   `chore(release): vX.Y.Z-BETA.N` de `dev` vers `beta`. Son corps contient le changelog, que le propriétaire du
   dépôt peut modifier à la main : c'est ce texte qui devient les notes de la release. Tant que la case
   « Le bot met à jour le changelog » est cochée, le bot ajoute les nouveaux commits sans toucher à tes lignes.
2. Le propriétaire sort la PR du draft (« Ready for review »). Le bot met alors la version à jour dans
   `build.gradle.kts` et active la fusion automatique, qui se fait dès que les checks sont verts.
3. À la fusion, le tag `vX.Y.Z-BETA.N` est créé, la pré-release GitHub est publiée et les images Docker sont
   poussées (`:<version>` et `:beta`).
4. La promotion `beta` → `main` suit le même cycle (`chore(release): vX.Y.Z`) et publie une release stable
   (images `:<version>` et `:latest`). Le bot ouvre ensuite une PR de report `beta` → `dev` (label `backport`) :
   fusionne-la par commit de merge, la promotion `dev` → `beta` est refusée tant qu'elle est ouverte.

Si le bot remet une PR en draft, c'est qu'un nouveau commit est arrivé depuis ta validation : relis-la, puis
sors-la de nouveau du draft.

### PR en conflit

GitHub ne déclenche pas le workflow « ready for review » sur une PR en conflit. Dans ce cas, résous le conflit,
puis repasse la PR en draft puis en ready.

## Hotfix

Pour un correctif urgent d'une version stable :

1. Crée `hotfix/mon-correctif` **depuis `main`**.
2. Ouvre une PR vers `main` (label `hotfix`) ; la sortie du draft et la fusion publient une release stable patch.
3. Le bot ouvre ensuite une PR de report `main` → `beta` (label `backport`), puis, une fois celle-ci fusionnée,
   le report `beta` → `dev`. Fusionne-les par commit de merge : tant qu'un report est en attente, les promotions
   depuis et vers sa branche sont refusées.

## Signaler un problème

Utilise les modèles d'issue (bug, fonctionnalité, question). Pour une faille de sécurité, ne crée pas d'issue
publique : suis [SECURITY.md](SECURITY.md).
