# Plan: Consolidation de la documentation "manifests"

> Pour une **nouvelle session**. Tâche distincte du refactor de code NodeEnv (commit 8b235492).
> Date de création : 2026-06-03.

## Objectif

Le dossier `docs/` contient 37 `.adoc`. Une grappe de **9 documents (~5300 lignes)** couvre
le sous-système "manifests" avec un fort recouvrement et une navigation confuse : quand on
cherche "comment un manifest est synthétisé", 4 docs ont "architecture"/"synthesis" dans le titre.

Le but : **consolider en une structure maintenable** (un hub canonique + un petit nombre de
docs focalisés), et **dé-périmer** le contenu obsolète depuis les refactors récents.

## ⚠️ Contenu périmé à corriger pendant la fusion

Refactors récents qui ont rendu une partie de la doc fausse :

1. **Component + ManifestUnit fusionnés** → une seule classe `*ManifestsUnit` (commit 443380fb).
   - PÉRIMÉ : `manifest-system-architecture.adoc` § "Component vs ManifestUnit Pattern" (lignes 129-315),
     `manifest-nomenclature.adoc` § "Component" (toute la doc utilise l'ancien split),
     `manifest-synthesis-architecture.adoc` § "Domain Organization: DomainRegistrar → LayerDomain → ManifestUnit".
2. **`LayerDomain` → `ManifestsDomain`** (commit d40a8377). Toutes les mentions `LayerDomain`,
   `LayerDomainRegistrar` sont périmées.
3. **`ManifestUnit` → `ManifestsUnit`** (pluriel). `AbstractManifestUnit` → `AbstractManifestsUnit`.
4. **`LayerEnv*` → `NodeEnv*`**, package `manifests.node` (commit 8b235492, CETTE session).
5. **`seed-layer1` / `seed-layer3` n'existent pas** comme modules — seul `seed-master` existe.
   `manifest-nomenclature.adoc` § "In seed-layer1/seed-layer3" est fictif.
6. **DomainRegistrars supprimés** — `DefaultManifestSynthesisService.buildDomainRegistry()` lève
   `UnsupportedOperationException`. Toute doc décrivant le registre de domaines comme fonctionnel
   est aspirationnelle, pas réelle. À marquer comme tel ou à retirer.

## Inventaire de la grappe (9 docs)

| Doc | Lignes | Contenu | Sort proposé |
|-----|-------:|---------|--------------|
| `manifest-system-architecture.adoc` | 1234 | nomenclature + Component/Unit + synthesis + chart | **SOURCE** du hub ; périmé en partie |
| `manifests-architecture.adoc` | 1045 | CDK8s extension + systemd (créé cette session) | **PLUS À JOUR** (ManifestsUnit pluriel) → base du hub |
| `manifest-synthesis-architecture.adoc` | 813 | flux de synthèse, component details, domaines | fusionner dans hub (synthesis) |
| `manifest-apply-flow.adoc` | 713 | pipeline runtime 5 étapes (synth→push→systemd→symlink→rke2) | **GARDER distinct** (concern runtime) ; dé-périmer |
| `manifest-conditional-inclusion.adoc` | 623 | inclusion par policy | **GARDER distinct** (concern policy) ; dé-périmer |
| `cdk8s-chart-vs-construct-pattern.adoc` | 529 | règle Chart vs Construct | fusionner dans hub (section pattern) |
| `manifest-nomenclature.adoc` | 354 | terminologie | fusionner dans hub (glossaire) ; **très périmé** |
| `manifest-domain-catalog-pattern.adoc` | 274 | catalogue SSOT des domain IDs | **GARDER distinct** (référencé par CLAUDE.md) |
| `systemd-architecture.adoc` | 699 | hiérarchie targets, bootstrap sequence | **GARDER distinct** (concern systemd profond) ; lier depuis hub |

## Structure cible proposée (à valider en début de session)

Option recommandée : **un hub canonique + companions focalisés**, pas un méga-doc de 5000 lignes.

```
docs/
  manifests-architecture.adoc      ← HUB canonique (renommer/garder ce nom)
    ├─ Overview + problème
    ├─ Glossaire/Nomenclature (absorbe manifest-nomenclature)
    ├─ C4 L1-L4 (déjà présents, les meilleurs diagrammes)
    ├─ Pattern CDK8s : Chart vs Construct (absorbe cdk8s-chart-vs-construct-pattern)
    ├─ ManifestsUnit : synthèse K8s + systemd (déjà présent)
    ├─ Synthesis flow / orchestration (absorbe manifest-synthesis-architecture)
    └─ liens → companions
  manifest-apply-flow.adoc         ← companion (runtime apply, 5 étapes)
  manifest-conditional-inclusion.adoc ← companion (policy)
  manifest-domain-catalog-pattern.adoc ← companion (SSOT IDs)
  systemd-architecture.adoc        ← companion (systemd profond)
```

À **supprimer après fusion** (contenu absorbé) :
- `manifest-system-architecture.adoc`
- `manifest-synthesis-architecture.adoc`
- `cdk8s-chart-vs-construct-pattern.adoc`
- `manifest-nomenclature.adoc`

## Démarche suggérée (multi-agent envisageable)

Vu le volume (~5300 lignes à lire + recouper), un **Workflow** est justifié :

1. **Lecture parallèle** : un agent par doc de la grappe → résumé structuré
   (sections, contenu unique vs redondant, passages périmés repérés).
2. **Synthèse de plan** : décider section par section quoi garder/fusionner/supprimer,
   quelle est la source canonique de chaque concept (préférer le contenu le plus récent
   et terminologiquement correct — souvent `manifests-architecture.adoc`).
3. **Rédaction du hub** : assembler le hub à partir des meilleures sections, en terminologie
   à jour (ManifestsUnit, ManifestsDomain, NodeEnv, manifests.node).
4. **Dé-périmage des companions** + mise à jour des cross-références.
5. **Supprimer** les docs absorbés, mettre à jour `docs/README.adoc` et
   `docs/cross-reference-navigation.adoc`.

## Contraintes (CLAUDE.md projet)

- AsciiDoc, diagrammes Mermaid (pas PlantUML pour le nouveau contenu).
- Cross-références bidirectionnelles ; mettre à jour `docs/README.adoc`.
- Pas de code mort / doc morte : les docs absorbés doivent être SUPPRIMÉS, pas laissés en double.
- Vérifier que les liens `link:../manifests/src/...` pointent vers les chemins post-refactor
  (`manifests/node/`, plus de `manifests/layers/`).

## Fichiers de référence

- `docs/README.adoc` (173 l) — index à mettre à jour
- `docs/cross-reference-navigation.adoc` (242 l) — carte de navigation à mettre à jour
- `CLAUDE.md` § "Documentation standards" — le standard de qualité (exemple : bootstrap-identity-provider.adoc)
- `.claude/terminology-refactor-plan.md` — état du refactor de code (terminologie finale)
