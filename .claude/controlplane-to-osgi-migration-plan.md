# Plan: control-plane → OSGi — faire naître les domaines

> Créé le 2026-07-07. Découpe en incréments de la spec
> [docs/architecture/osgi/controlplane-to-osgi-migration-spec.adoc](../docs/architecture/osgi/controlplane-to-osgi-migration-spec.adoc).
> Objectif : migrer les 87 fichiers Pulumi-free de `controlplane/` en services de domaine OSGi
> (port + core + fake, patron `cluster-edge`), en laissant les 17 `com.pulumi` côté host.
> Frame : `.claude/claude-preview.adoc` + mémoire `controlplane-to-osgi-migration-frame.md`.

## Scope — on FAIT NAÎTRE les services, on ne re-câble PAS le pipeline

**NOTRE scope** : créer les modules OSGi, extraire la logique, livrer chaque domaine avec tests + fake.
L'ancien chemin host reste branché.

**PAS notre scope** (chantier « split » séparé, `bdd-pipeline-migration-plan.md`) : re-pointer un
`XxxStage` de `import controlplane.xxx.*` vers `awaitService(XxxPort.class)`. C'est la *consommation*
de ce qu'on birthe. Chaque domaine migré DÉBLOQUE la bascule de son stage, mais la bascule elle-même
est faite par l'autre chantier.

## Règles de travail (conventions projet)

- Réacteur toujours : `flox activate -- ./mvnw -pl :<module> -am …` (jamais `mvn install`, jamais `-pl`
  seul). Le build est lancé par le user.
- Refactor atomique : pas de legacy, pas de constructeur de compat, pas de `@Deprecated`. Supprimer
  l'ancien dans le même change.
- Patron par domaine : `<d>-port` (`type=seam`, contrat flat-in/flat-out) + `<d>-core` (`@Component`,
  la logique) + `<d>-*-fake` (fragment fixture, `variant=fake`, ranking -1000).
- Immuable → record ; builder → constructeur privé (sauf record public).
- Green-before-next : chaque incrément compile avant le suivant.
- Vérif de non-régression : l'ancien host-code compile toujours ET produit la même preview
  (`pulumi preview` inchangé) — on n'a débranché personne.

## Ordre retenu (spec, couplage croissant)

Incrément-0 (ports transverses) → config → policy → readiness → bdd-split → bbox → incus.
Rationale : peupler vite avec le plus facile, valider le patron sur config (1 seule couture) avant le
hub incus (4 coutures).

---

## INCRÉMENT 0 — les 4 ports transverses (préalable partagé)

Extraits UNE fois, consommés par plusieurs domaines. Chacun : seam neutre + fake in-memory.

### [ ] 0.1 `HostPathResolver` (seam)
- **Sert** : config (§1), readiness (§3), incus (§6).
- **Isole** : topologie NFS-automount `/net`↔`/private`, `toAbsolutePath()` sur CWD.
- **Source** : `config/BootstrapConfig.java` — méthodes `pathOn`/`worktreeDirOn`/`netPrefix`/
  `normalizeAbsolutePath` (111-171).
- **Contrat** : `resolve(base, rawPath) → Path` plat, host-topology injectée. Core = le mapping pur ;
  fake = résolveur à racine fixe.

### [ ] 0.2 `GitFactsReader` (seam)
- **Sert** : policy (§2), incus (§6).
- **Isole** : ouverture jgit d'un worktree host → faits plats (dirty?, HEAD commit).
- **Source** : `policy/EntryGatePolicyEnforcer.java` (jgit worktree) + `incus/GitMetadataExtractor.java`
  (`extract`/`openRepository`/`isDirty`, 38-96) + `incus/DeploymentMetadata.java` (`GitMetadata.capture`).
- **Contrat** : `factsFor(worktreePath) → GitFacts` (record plat). Core = interprétation ; fake = faits fixes.

### [ ] 0.3 `SecretsSource` (seam)
- **Sert** : bbox (§5), incus (§6).
- **Isole** : lecture `.secrets` (worktree host) → bytes/InputStream.
- **Source** : `bbox/BboxSecretsReader.java` (`readPlaintextRoot`, 66-90) + `incus/IncusIdentityMaterialAssembler.java`
  (lecture `.secrets`/server-cert).
- **Contrat** : `open(name) → bytes`. Core = parse/validate (le `sops:`-check, key-traversal) reste
  au-dessus, dans le domaine ; fake = YAML in-memory.

### [ ] 0.4 `ArtifactSink` (seam)
- **Sert** : bdd (§4, ×3), incus (§6).
- **Isole** : write d'un artefact rendu → le host persiste (loi flat-out).
- **Source** : `bdd/{MedicalRecordDump,RunbookRenderer,RecordInterventionCommand}` (les `--out`/write) +
  `incus/{ClasspathTreeCopier,SystemdTarget}` (sinks `Files.copy`/`createDirectories`).
- **Contrat** : `write(relPath, bytes)`. Core = le rendu de l'artefact ; fake = capture in-memory (assertions).

---

## INCRÉMENT 1 — config (l'entrée la plus propre, 1 couture)

### [ ] 1.1 `config-core` + le record placeable
- **Fichiers** : `config/BootstrapConfig.java` (172 l), `config/Rke2labConfig.java` (⚠ importe pulumi —
  la lecture pulumi reste host ; seul le DTO/`from()` migre), + les 10 fichiers `config/` pulumi-free.
- **Direction** : le record (fields, defaults, `from()` derivation, `DEFAULT_IMAGE_DISTROBUILDER_CONFIG`
  = `classpath:` bundle-resource) devient placeable ; `pathOn`/`normalizeAbsolutePath` partent derrière
  `HostPathResolver` (0.1).
- **Fake** : config fixture.

## INCRÉMENT 2 — policy (le plus host-anchré, git facts)

### [ ] 2.1 `policy-core`
- **Fichiers** : `policy/EntryGatePolicyEnforcer.java` (368 l) + les 6 `policy/`.
- **Direction** : le flake/manifest parsing (`FlakeInputsParser`, `BraceMatcher`, `summarizeStatus`)
  = core placeable ; `enforceCleanWorktree`/`enforceFlakeLockCoherence` (jgit) → `GitFactsReader` (0.2).
  `enforceManifestUpdateGate` lit déjà le registry OSGi — OK tel quel.
- **Fake** : policy fixture (faits git injectés).

## INCRÉMENT 3 — readiness (déjà cluster-edge, 1 poll résiduel)

### [ ] 3.1 `readiness-core` + repli du Stage
- **Fichiers** : `readiness/ClusterBootstrapReadinessVerifier.java` (454 l),
  `readiness/ReadinessOutputMapper.java` (⚠ pulumi — reste host).
- **Direction** : API/controller checks délèguent déjà à `ClusterReadinessContact` injecté. Résidu =
  `waitForKubeconfigPublished` (`config.kubeconfigRef().toAbsolutePath()`) → `HostPathResolver` (0.1).
  Construction `VerificationResult` déjà plate.
- **Fragments bdd** : `ClusterReadinessStage`/`Probe`/`Scenario`, `LiveClusterReadinessProbe`,
  `SimulatedClusterReadinessProbe` (= le fake) rejoignent le domaine cluster/readiness.
- Note : valide le patron end-to-end (domaine le plus avancé).

## INCRÉMENT 4 — bdd split (dispersion des fragments)

### [ ] 4.1 Répartir les triples par domaine
- **Fragments par-domaine** (→ leur domaine) : `Bbox*` → bbox, `Incus*` → incus,
  `SystemdAdapter*`/`SimulatedSystemdAdapterProbe` → systemd. (readiness fait en §3.)
- **Preflight** (`PreflightStage`/`Probe`/`LivePreflightProbe`) → RESTE host (edge exec).

### [ ] 4.2 Artifact writers derrière `ArtifactSink`
- **Fichiers** : `bdd/MedicalRecordDump.java`, `bdd/RunbookRenderer.java`, `bdd/RecordInterventionCommand.java`.
- **Direction** : core de rendu placeable ; write → `ArtifactSink` (0.4). Les `--backend` (lecture
  pulumi-state-dir) RESTENT host.

### [ ] 4.3 Ne PAS toucher la colonne vertébrale (le scénario = assemblage)
- `ClusterSeedScenario`, `StageContext`, `SeedProbes`, `Outputs/ResourcesStage`, `HostSeeder`,
  `PendingMarkingScenarioExecutor` : c'est l'assemblage qui tourne sur `scenario-engine` (existe déjà).
  Owner = concern seeding-scenario, pas un domaine. Hors périmètre de birthing.

## INCRÉMENT 5 — bbox (nouveau domaine)

### [ ] 5.1 `bbox-port` + `bbox-core` + `bbox-*-fake`
- **Fichiers** : `bbox/BboxReconciliationOrchestrator.java` (49 l, PLACEABLE : branch `pulumiMode`
  forward `Path`), `bbox/BboxSecretsReader.java` (parse in-memory + `SecretsSource` 0.3), + les 5
  `bbox/` pulumi-free. (⚠ `BboxReconcilerComponent`/`BboxReservation*Resource` = pulumi, restent host.)
- **Direction** : `bbox-port` = contrat reconcile + consomme `SecretsSource` ; `bbox-core` = parse/
  validate + décision reconcile `@Component` ; fake = YAML in-memory.
- **Débloque** : `BboxStage` (aujourd'hui 🔴, `import controlplane.bbox.*` direct).

## INCRÉMENT 6 — incus (le hub, 4 coutures, le plus lourd)

### [ ] 6.1 `incus-port` + `incus-core` + `incus-*-fake`
- **Fichiers** : 15 `incus/` pulumi-free. Purs : `ProvisioningTarget` (59 l), `ProvisioningTargetRegistry`
  (151 l). MIXED-sains : `TargetChecksumPipeline` (281 l, SHA-256 + foreign-descendant),
  `IncusIdentityMaterialAssembler` (parse YAML + cert classpath), `GitMetadataExtractor` (build-id fmt),
  `ClasspathTreeCopier` (resolve classpath + sink), `SystemdTarget`, `DeploymentMetadata`.
- **Consomme les 4 ports** : `GitFactsReader` + `SecretsSource` + `ArtifactSink` + `HostPathResolver`.
- **La classe grasse** : `IncusResourceBootstrap.java` (⚠ pulumi, 3000+ l) entrelace décision plate +
  `Output<T>`. NOTRE part = extraire le *plan de provisioning plat* vers `incus-core`. Le câblage
  `com.pulumi.incus.*` avec `Output<T>` RESTE host (c'est le split, pas nous).
- **Débloque** : `IncusStage` (aujourd'hui 🔴).

---

## Ce qui reste host (ne pas migrer)

- `RuntimeCommandPreflight` (edge exec irréductible ; veneer plat build-ssh-argv/exit-code extractible).
- `PreflightStage`/`Probe`, `LivePreflightProbe`.
- Les 17 `com.pulumi` (registration + `Output<T>`).
- `HostSeeder` (couplage session JUnit + system props).
- `scenario-engine` (déjà OSGi, dans `runtime/`, bonne couche).

## État des stages (preuve que le mécanisme est vivant)

- ✅ `SystemdAdapterStage` — déjà `awaitService(port)`.
- 🟡 `ClusterReadinessStage` — mi-chemin (reste `import controlplane.readiness.*`) → débloqué par §3.
- 🔴 `IncusStage` / `BboxStage` — bloqués, débloqués par §6 / §5.
