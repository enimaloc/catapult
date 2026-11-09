# Politique de sécurité

## Signaler une vulnérabilité

Ne crée **pas** d'issue publique pour une faille de sécurité.

Utilise le signalement privé de GitHub : onglet **Security** du dépôt, puis
[« Report a vulnerability »](https://github.com/enimaloc/catapult/security/advisories/new)
(GitHub Security Advisories). Décris le problème, comment le reproduire, la version touchée
(`v1.0.0-BETA.N`) et l'impact que tu imagines.

Tu auras un accusé de réception dans les meilleurs délais ; le correctif est préparé en privé, puis publié sous
la forme d'un hotfix avec un avis de sécurité.

## Versions prises en charge

Seules la dernière release stable (`vX.Y.Z`, branche `main`) et la dernière pré-release (`vX.Y.Z-BETA.N`, branche
`beta`) reçoivent des correctifs.

## Ce qui n'en relève pas

Les problèmes de configuration de ta propre instance (clés d'API, secrets du `.env`) ne sont pas des failles de
Catapult : ne les publie jamais dans une issue.
