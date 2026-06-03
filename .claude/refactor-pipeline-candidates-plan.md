# Plan: Refactor des candidats pipeline/builder

> Créé le 2026-06-03. Audit lecture-seule des méthodes à trop d'arguments / trop longues
> dans `manifests/` et `seed-master/`. Objectif : appliquer la fluent pipeline grammar
> (voir [docs/fluent-pipeline-grammar.adoc](../docs/fluent-pipeline-grammar.adoc)),
> les builders et les records pour rendre ces méthodes lisibles et type-safe.

## Contexte

Décidé après le nettoyage des FQN dans `DefaultManifestSynthesisService`. Le user veut
traiter **tous** les items, potentiellement sur plusieurs sessions. Ce fichier est la
source de vérité : cocher au fur et à mesure, ne rien oublier.

Exemplar de la grammaire : `seed-master/.../controlplane/pipeline/ApplicationPipeline.java`
et `BootstrapPipeline.java`. Relire avant chaque refactor "grammar".

## Règles de travail (rappel conventions projet)

- Refactor atomique : migrer + mettre à jour TOUS les call sites dans le même change.
- Pas de legacy / pas de constructeur de compat / pas de `@Deprecated`. Supprimer l'ancien.
- Builder → constructeur privé pour forcer son usage.
- Classe immuable non destinée à l'héritage → record.
- Le build est lancé par le user (`flox activate -- ./mvnw …`), pas par moi.
- Vérifier après chaque item : compile + `synthesize` produit la même preview
  (`.local.d/bioskop/master/host.preview/`), aucun domaine/unit perdu.

## Ordre retenu

1. **synthesizeInContext** (manifests) — choisi en premier, scope déjà touché.
2. Records WaitConfig / YamlSummaryContext (petit, ciblé, faible risque).
3. HostSlotManifest builder (mécanique, faible risque).
4. Méthodes longues Incus (plus gros chantier, garder pour la fin).

---

## CATÉGORIE A — Trop de paramètres

### [ ] A1. HostSlotManifest — constructeur 12 params → builder
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/incus/HostSlotManifest.java:34`
- **Signature** : `(Construct scope, String id, SlotType slotType, Integer slotSequence, Instant timestamp, String buildId, GitInfo git, PolicyInfo policy, List<FloxEnvironment> floxEnvironments, List<StagedManifest> stagedManifests, PromotionInfo promotion, SourceInfo source)`
- **Pourquoi** : 12 params, plusieurs optionnels (git, policy, promotion, source) → telescoping.
- **Direction** : builder `HostSlotManifest.builder(scope, id).slotType(...)…build()`, constructeur privé.
- **Call sites à mettre à jour** : chercher `new HostSlotManifest(` + `writeSlotManifest` (A4).

### [ ] A2. SeedNodeBootstrapWatcher.renderYamlSummary — 8 params → record
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/resources/SeedNodeBootstrapWatcher.java:143`
- **Signature** : `(String probeStatus, String mandatoryTarget, String mandatoryTargetState, int pendingJobCount, int failedUnitCount, String hostContext, Map<String,Object> statusSnapshot, String adapterSummary)`
- **Direction** : record `YamlSummaryContext` regroupant ces champs.

### [ ] A3. SeedNodeBootstrapWatcher.waitForBootstrapPreconditions — 5 params (3 Durations) → record
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/resources/SeedNodeBootstrapWatcher.java:29`
- **Signature** : `(BootstrapConfig config, Duration timeout, Duration retryInterval, Duration progressLogInterval, Consumer<String> logger)`
- **Direction** : record `WaitConfig(timeout, retryInterval, progressLogInterval)`.
- **Note** : cette méthode est AUSSI dans la catégorie B (corps ~80 lignes) — traiter params + corps ensemble.

### [ ] A4. IncusResourceBootstrap.writeSlotManifest — 5 params
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/incus/IncusResourceBootstrap.java:2172`
- **Signature** : `(Path slotPath, int slotSeq, BootstrapConfig config, ControlplanePolicy policy, SystemdTarget systemdTarget)`
- **Direction** : dépend de A1 (construit un HostSlotManifest). Faire APRÈS A1.

---

## CATÉGORIE B — Méthodes longues à étapes commentées (fluent grammar)

### [ ] B1. DefaultManifestSynthesisService.synthesizeInContext  ⭐ PREMIER
- **Fichier** : `manifests/src/main/java/io/nxmatic/rke2lab/manifests/DefaultManifestSynthesisService.java:73`
- **Taille** : ~195 lignes, 8 étapes commentées.
- **Étapes repérées** (constituent les `.during(...)`) :
  1. build domain registry + apply policy + apply units (l.83-112)
  2. shared domain catalog (l.114)
  3. créer les SystemdTargets : rke2lab / network / tools / bootstrap / manifests / secrets (l.121-175)
  4. SystemdSynthesisContext (l.178)
  5. BootstrapInfrastructureSynthesizer.synthesizeAll (l.190)
  6. domaines → synthesizeSystemdUnits (l.195)
  7. dépendances du target principal + finalize (l.201-218)
  8. drop-in rke2-server + app.synth + systemdChart.synthesize + move fichier (l.221-266)
- **Direction** : pipeline `.during("label", lambda).then()…`. Étape 3 (création des 6 targets)
  est elle-même un sous-candidat — peut-être un helper/record `SystemdTargets`.
- **Vérif** : preview identique après refactor (11 domaines, mêmes units).

### [ ] B2. IncusResourceBootstrap.synthesizeImageStateConfigMapYaml — ~100 lignes
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/incus/IncusResourceBootstrap.java:850`
- **Étapes** : créer app CDK8s in-memory (l.858) / namespace + ConfigMap (l.891) / dépendance (l.918) / relire depuis disque (l.921) / cleanup temp dir (l.932).
- **Direction** : fluent grammar + try-with-resources pour le cycle temp dir.

### [ ] B3. IncusResourceBootstrap.ensureNetwork — ~80 lignes
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/incus/IncusResourceBootstrap.java:1752`
- **Étapes** : check lan-br (l.1754) / safeguard non-canonique (l.1774) / vmnet source de vérité (l.1792) / restriction projet OVN (l.1800) / build args + options + create.
- **Direction** : extraire stages (check → build args → build options → create), attention aux returns précoces.

### [ ] B4. IncusResourceBootstrap.HostAssetRootLifecycle.syncStagingToFinal — ~45 lignes
- **Fichier** : `seed-master/src/main/java/io/nxmatic/rke2lab/controlplane/incus/IncusResourceBootstrap.java:2121`
- **Étapes** : no-op si identique (l.2128) / promote scratch → slot numéroté (l.2134) / write manifest (l.2142) / backup (l.2146) / synth ou copy (l.2149) / symlink manifest (l.2156).
- **Direction** : pipeline `.during("compare",…).then().during("allocate",…)…`. Lié à A1/A4 (manifest).

### [ ] B5. SeedNodeBootstrapWatcher.waitForBootstrapPreconditions — ~80 lignes
- **Voir A3** : même méthode. Traiter params (record WaitConfig) + corps (boucle d'attente / checks séquentiels) ensemble.

---

## Déjà conforme (ne pas toucher)

- `IncusResourceBootstrap.HostStage.materializeAssets` (l.225) — utilise déjà le pattern pipeline.
- `Main.java` (manifests + controlplane) — déjà en grammaire fluide.

## Post-refactoring : revue d'uniformité des pipelines

**IMPORTANT** : Une fois tous les refactorings terminés, faire une passe de revue pour vérifier que TOUS les pipelines suivent le même modèle d'implémentation.

### [ ] Revue finale : uniformité des pipelines

Vérifier la cohérence entre :

- `BootstrapPipeline` (public, controlplane.pipeline)
- `ApplicationPipeline` (public, controlplane.pipeline)
- `TargetChecksumPipeline` (package-private, controlplane.incus)
- `SynthesisPipeline` (inner non-static, DefaultManifestSynthesisService) — nouveau
- Tous les autres pipelines créés durant ce refactoring

**Points à vérifier** :

1. Visibilité cohérente (public vs package-private vs inner) selon le contexte d'usage
2. `.onFailure()` toujours AVANT le premier `.during()` (type-state enforcement)
3. Pas de cast unchecked dans les transitions (voir pitfall dans grammar.adoc)
4. Chaque transition instancie explicitement `new NextState(state)`
5. Labels de topics en lowercase, descriptifs du domaine
6. Terminal verbs cohérents : `.complete()`, `.collectOutputs()`, `.toResult()`
7. Inner classes : static si aucun accès à l'instance englobante, non-static sinon

**Si incohérence détectée** : uniformiser TOUS les pipelines, pas juste "le nouveau". Refactor atomique.

---

## Suivi

| Item | État | Session | Notes |
|------|------|---------|-------|
| FQN cleanup DefaultManifestSynthesisService | ✅ fait | 2026-06-03 | imports propres, FIXME mort supprimé |
| B1 synthesizeInContext | ✅ fait | 2026-06-03 | inner SynthesisPipeline (static classes, State avec ref service), 6 stages, compile ✓ |
| A2 YamlSummaryContext | ✅ fait | 2026-06-03 | record + builder, renderYamlSummary(context), compile ✓ |
| A3+B5 WaitConfig | ✅ fait | 2026-06-03 | public record WaitConfig(3 Durations), call site mis à jour, compile ✓. Corps méthode (B5) à traiter plus tard si besoin |
| A1 HostSlotManifest builder | ✅ déjà fait | — | builder existe, constructeur déjà privé ✓ |
| A4 writeSlotManifest | ⊘ non pertinent | — | méthode d'orchestration privée, params justifiés |
| B2 synthesizeImageStateConfigMapYaml | ✅ fait | 2026-06-03 | record ImageStateData + extraction méthodes (namespace/configMap/read/cleanup) + try-finally, compile ✓ |
| B3 ensureNetwork | ✅ fait | 2026-06-03 | inner class **NetworkEnsurer** (6 méthodes encapsulées), compile ✓ |
| B2 (révision) | ✅ fait | 2026-06-03 | inner static class **ImageStateSynthesizer** (record + 5 méthodes encapsulées), compile ✓ |
| B4 syncStagingToFinal | ✅ fait | 2026-06-03 | extraction méthodes : isNoOpDeploy + promoteToSlot + syncToFinal, compile ✓ |
| **Local classes refactor** | ✅ fait | 2026-06-03 | ImageStateSynthesizer + NetworkEnsurer → local classes dans leurs méthodes. Pattern ajouté à CLAUDE.md. SynthesisPipeline reste inner (trop complexe). Compile ✓ |
| **Builder terminal verbs** | ✅ fait | 2026-06-03 | ImageStateConfig.builder(this)…synthesize() + YamlSummaryContext.builder()…render(). Chaînage fluent complet. Compile ✓ |
| Pipeline local : service (B1 révision) | ✅ fait | 2026-06-03 | SynthesisPipeline descendu local à synthesizeInContext(), stages non-static, State.service (copie de this) supprimé, private→package-private. Commit afadd869. Compile ✓ |
| Pipeline local : systemd | ✅ fait | 2026-06-03 | manifests.systemd.SynthesisPipeline inliné dans BootstrapInfrastructureSynthesizer.synthesizeAll() ; fichier standalone supprimé (doublon de nom #1 réglé) ; helpers → package-private. Commit 3878286b. Compile ✓ |
| **Revue uniformité pipelines** | ◐ partiel | 2026-06-03 | **2 pipelines manifests faits.** RESTE (seed-master) : ApplicationPipeline/BootstrapPipeline/TargetChecksumPipeline → descendre en local à leur méthode + non-static ; ResourceCreationPipeline → inner classes non-static (#2). Terminal verb : .complete() peut retourner un output (tranché 2026-06-03, c'est OK — « terminer » et rendre le résultat produit est naturel ; pas de renommage en .toResult()). |
