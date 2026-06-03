# Checkpoint — 2026-06-03, 11h35

> Sauvegarde avant compactage. Reprise avec ce fichier après le compact.

## État actuel (HEAD = 5a17dcfc)

**Commits de cette session** (ordre chronologique) :
1. `443380fb` — Component + ManifestUnit fusionnés → `*ManifestsUnit`
2. `d40a8377` — LayerDomain → ManifestsDomain
3. `8b235492` — LayerEnv → NodeEnv, move to manifests.node (+ fix SPI LayerEnvContributor cassé)
4. `5a17dcfc` — **rk2lab → rke2lab** (package root + fix SPI `.api.` fantômes)

**Ce qui compile** : manifests + seed-master + netplan + cdk8s-systemd (compilation vérifiée au commit 5a17dcfc).

**Pulumi preview** : encore en échec, mais l'erreur a **changé** — **progrès** :
- ✅ Bug SPI `ManifestUpdateGate` **RÉSOLU** (ServiceLoader le trouve maintenant).
- ❌ **Nouveau blocage** : `DefaultManifestSynthesisService.buildDomainRegistry()` lève
  `UnsupportedOperationException` — les `DomainRegistrars` ont été supprimés pendant le refactor
  Component→ManifestsUnit (ligne 273 de DefaultManifestSynthesisService.java).

## Pourquoi la preview échouait (bug corrigé)

Les 3 fichiers SPI avaient **deux erreurs cumulées** :
1. Segment `.api.` fantôme dans le nom du fichier (ex: `io.nxmatic.rk2lab.manifests.api.ManifestUpdateGate`)
   alors que l'interface vit dans `io.nxmatic.rk2lab.manifests` (sans `.api.`).
2. Package root `rk2lab` (wrong) au lieu de `rke2lab` (correct).

→ `ServiceLoader.load(ManifestUpdateGate.class)` cherchait un fichier nommé exactement d'après
le FQN de l'interface, ne trouvait rien → "No provider found" → crash preflight.

**Fix (commit 5a17dcfc)** : renommé les 4 fichiers SPI sans `.api.` et avec `rke2lab`, mis à jour
leurs contenus. ServiceLoader les trouve maintenant.

## Blocage actuel : DomainRegistrars manquants

`DefaultManifestSynthesisService.buildDomainRegistry(policy)` est un **stub** depuis le refactor.
Code actuel (lignes 269-291) :

```java
private ManifestsDomainRegistry buildDomainRegistry(ManifestDomainPolicy policy) {
  // FIXME: Temporarily disabled during layers→components migration
  // DomainRegistrars deleted, new domain registration pattern pending
  throw new UnsupportedOperationException(
      "Domain registration temporarily disabled during layers→components migration. "
          + "DomainRegistrars have been deleted; new domain pattern pending implementation.");
  // ... commented-out old code ...
}
```

Ce qui manque : les **DomainRegistrars** qui créaient les `ManifestsDomain` (ex: ClusterDomainRegistrar,
NetworkingDomainRegistrar, GitopsDomainRegistrar, etc.). Ils instanciaient les `*ManifestsUnit` et
les groupaient en domaines. Supprimés pendant le merge Component→ManifestsUnit (le registre appelait
`.withAllComponents()` sur des classes Component qui n'existent plus).

## Prochaine étape (après compactage)

**Réimplémenter `buildDomainRegistry()`** pour débloquer la preview. Deux approches :

### Option A : Stub minimal (rapide, preview possible mais vide)
Retourner un `ManifestsDomainRegistry` vide ou hardcodé avec 1-2 domaines de test. La preview
passerait, mais ne synthétiserait aucun manifest réel. Juste pour voir si le reste de la pipeline
marche.

```java
private ManifestsDomainRegistry buildDomainRegistry(ManifestDomainPolicy policy) {
  // TEMPORARY STUB for preview unblocking
  return ManifestsDomainRegistry.builder().build(); // empty
}
```

### Option B : Réimplémentation complète (correct, prend du temps)
Recréer les DomainRegistrars OU les remplacer par un pattern inline. Étapes :
1. Inventorier les anciens registrars (9 domaines : cluster, networking, gitops, storage, runtime,
   mesh, ha, cicd, clusterapi, platform).
2. Pour chaque domaine, lister les `*ManifestsUnit` qu'il contient (chercher dans `manifests/units/`).
3. Créer les `ManifestsDomain` via le builder, avec dépendances inter-domaines si besoin.
4. Filtrer par policy (`policy.isEnabled(domainId)`).
5. Construire le registre.

**Complexité** : ~9 domaines × moyenne 3-5 units chacun = 30-40 units à recenser et câbler.
Ça prend plusieurs heures si fait proprement (avec vérification des dépendances, conditional inclusion, etc.).

## Recommandation

**Option A d'abord** (stub minimal) → débloquer la preview immédiatement, voir si d'autres bugs
apparaissent downstream. **Puis Option B** (réimplémentation) dans une session dédiée, avec inventaire
propre des units par domaine.

La session doc parallèle peut continuer — elle ne dépend pas de la preview fonctionnelle.

## Fichiers de référence

- `.claude/terminology-refactor-plan.md` — état détaillé du refactor terminologique (à jour)
- `manifests/src/main/java/io/nxmatic/rke2lab/manifests/DefaultManifestSynthesisService.java:269` — le stub qui bloque
- Anciens DomainRegistrars : **supprimés** (chercher dans git log `git log --all --full-history -- "*DomainRegistrar.java"`)
- Liste units actuels : `find manifests/src/main/java/io/nxmatic/rke2lab/manifests/units -name "*ManifestsUnit.java"`

## Autres tâches en attente (liste "Remaining")

1. Introduire `BootstrapPhase` pour stages temporels (seed-master) — terminologie réservée, pas encore utilisée
2. Fix `CiliumConfigManifestsUnit.apply(ManifestsUnitContext)` self-instantiation bug (ligne 34-36)
3. Supprimer constructeur null-scope mort dans `AbstractManifestsUnit`
4. Mettre à jour `docs/manifests-architecture.adoc` avec BootstrapPhase (quand introduit)

## Mémoire (auto-memory)

Les mémoires `manifests-doc-consolidation` et `terminology-refactor-state` sont à jour dans
`/Users/nxmatic/.claude/projects/-private-var-lib-git-nxmatic-rke2lab/memory/MEMORY.md`.
Elles pointent vers ce checkpoint et le plan de refactor.
