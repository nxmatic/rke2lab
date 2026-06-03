# Plan: seed-vcluster (ex « layer 2 » / Stage B)

> Créé le 2026-06-03. Amorçage du vCluster de management `gitops-mgmt` + Flux, par Pulumi,
> une fois le master (seed-master) Ready. Nommage : on dit **seed-vcluster** (l'artefact amorcé),
> pas « layer 2 ». Le vocabulaire `seed-*` prolonge `seed-master`.
>
> Source de référence (datée, vocabulaire pré-refactor) : [docs/vcluster-implementation-plan.adoc](../docs/vcluster-implementation-plan.adoc).
> ATTENTION : ce doc parle de `Component`, `Layer1Chart`, « Phase 1.5 » — terminologie périmée
> depuis les refactors (Component→ManifestsUnit, DomainRegistrars, etc.). Traduire en lisant.

## But

Établir le vCluster `gitops-mgmt` avec Flux dedans, qui pilotera ensuite le cluster parent
via GitOps. C'est un **seed** : il amorce le GitOps puis se gère seul. Après ça, le reste
(CI/CD, peers CAPI, vClusters de lab) passe en régime permanent Flux — ce n'est plus un seed.

## Vocabulaire seed-*

| Terme retenu | ex-« layer » | Amorce |
|---|---|---|
| seed-master | layer 1 / Stage A | nœud master RKE2 + infra critique (FAIT, validé sur infra réelle) |
| **seed-vcluster** | layer 2 / Stage B | vCluster gitops-mgmt + Flux (CE PLAN) |
| (pas un seed) | layer 3 | Flux en régime permanent |
| seed-vclusters (futur module) | sous layer 3 | amorçage des vClusters de lab |

NB : `seed-vcluster` (singulier, mgmt, ce plan) ≠ `seed-vclusters` (pluriel, module futur pour les labs).

## État constaté (2026-06-03) — prérequis NON satisfaits

Vérifié dans le code actuel, AVANT de coder :

1. **❌ vCluster operator ABSENT de la synthèse.** `grep -ril vcluster manifests/src` = vide.
   Le plan « layer 2 » présuppose « Wait for vCluster Operator » mais aucune unit ne le synthétise.
   → PRÉREQUIS : créer un `VClusterOperatorManifestsUnit` (HelmChart CR loft vcluster) et
   l'enregistrer dans un domaine. Le plan suggère un « bootstrap domain » regroupant
   vcluster-operator + flux-operator + cluster-api-operator ; à arbitrer (voir Décisions).

2. **✅ Flux operator présent** : `units/gitops/FluxOperatorManifestsUnit`, `FluxInstanceManifestsUnit`,
   `FluxRootManifestsUnit`, `SopsAgeSecretManifestsUnit`.

3. **✅ cluster-api operator présent** : `units/clusterapi/`.

4. **✅ pulumi-command déclaré dans le BOM** (`bom/pom.xml`: command 1.2.1) MAIS
   **❌ jamais utilisé** (`new Command(` absent de seed-master). Le bootstrap Flux du plan
   repose entièrement sur `com.pulumi.command.Command`. → À ajouter en dépendance seed-master
   + premier usage.

5. **Structure controlplane actuelle** : `bbox/ config/ incus/ pipeline/ policy/ readiness/
   resources/ systemd/`. Pas de `vcluster/`. Le code seed-vcluster irait dans un nouveau
   `controlplane/vcluster/`.

## ⚠️ GELÉ tant que seed-master n'est pas Ready

seed-vcluster dépend de la readiness de seed-master. Au 2026-06-03, le master bootstrap
**ne converge pas encore** (en cours de debug). Ne PAS démarrer seed-vcluster avant que le
master soit Ready et l'état cluster confirmé (kubectl). Ce plan est un cadrage anticipé.

Les décisions D1/D2/D3 ci-dessous demandent une analyse préalable (état des domaines, coût du
remue-ménage, binaires hôte Pulumi) — à reprendre à froid, pas à chaud.

## Décisions à arbitrer (avant de coder, demandent analyse)

- [ ] **D1 — Bootstrap domain ?** Le plan propose de regrouper vcluster-operator + flux-operator
  + cluster-api-operator dans un « bootstrap domain » dédié (déplacer flux depuis gitops/,
  capi depuis clusterapi/). OU : ajouter juste `VClusterOperatorManifestsUnit` au domaine
  existant le plus proche (platform ? un nouveau `bootstrap`/`operators` ?). Trancher selon
  ManifestDomainCatalog actuel — éviter un gros remue-ménage de domaines si pas nécessaire.
- [ ] **D2 — Où vit seed-vcluster ?** Option A : dans seed-master (`controlplane/vcluster/`),
  appelé comme une étape post-cluster du pipeline incus (Stage B). Option B : module Maven
  séparé `seed-vcluster`. Le plan met le code dans seed-master ; un module séparé serait plus
  fidèle au nommage mais plus lourd. Recommander A pour commencer (étape Pulumi dans seed-master),
  extraire en module seulement si ça grossit.
- [ ] **D3 — Flux bootstrap via Command vs natif.** Le plan utilise `flux bootstrap github` via
  `Command` (shell). Alternative : poser GitRepository + Kustomization CRs directement (déjà
  fait partiellement par `FluxRootManifestsUnit`). Décider : shell `flux bootstrap` (simple,
  mais dépend des binaires vcluster/flux/kubectl sur l'hôte Pulumi) vs CRs déclaratifs.

## Étapes (à raffiner après arbitrage D1-D3)

### Étape 1 — vCluster operator dans la synthèse (prérequis)
- [ ] Créer `VClusterOperatorManifestsUnit` (HelmChart CR, loft vcluster operator).
- [ ] L'enregistrer (domaine selon D1) via un DomainRegistrar + ManifestDomainCatalog.
- [ ] Vérifier preview : le vcluster operator apparaît dans rke2-manifests.d/.

### Étape 2 — readiness gate vCluster operator
- [ ] Étendre `ClusterBootstrapReadinessVerifier` (ou le mécanisme readiness) pour attendre
  que le vCluster operator (et Flux operator) soient Ready avant seed-vcluster.

### Étape 3 — GitopsMgmtVCluster (Pulumi, controlplane/vcluster/)
- [ ] Ajouter dépendance `com.pulumi:command` au pom seed-master.
- [ ] Créer la ressource qui : crée le namespace gitops-mgmt, pose la CR VCluster gitops-mgmt,
  attend qu'elle soit Ready.
- [ ] Bootstrap Flux dans le vCluster (D3 : Command `flux bootstrap` OU CRs).
- [ ] Injecter le Secret `parent-cluster-kubeconfig` dans flux-system du vCluster.
- [ ] Brancher comme étape Stage B du pipeline incus / resources.

### Étape 4 — structure GitOps dans le repo
- [ ] Créer `gitops/clusters/bioskop/{core,peers}/`, `gitops/vclusters/{gitops-mgmt,labs}/`,
  les kustomizations racine + `bioskop-cluster` Kustomization (watch parent via kubeconfig secret).
- [ ] Résoudre les TODO de `FluxRootManifestsUnit` (repo URL en dur l.66, SOPS age l.96) — ils
  deviennent pertinents ici (étaient « layer 3 » donc différés ; seed-vcluster les active).

### Étape 5 — validation sur infra réelle (lancée par le user)
- [ ] `pulumi up` complète ; `kubectl get vcluster -n gitops-mgmt` → Ready.
- [ ] `vcluster connect` + `flux get all` → GitRepository + Kustomization bioskop-cluster.
- [ ] Secret parent-cluster-kubeconfig présent ; `flux logs` montre « Applied revision ».

## Conventions de travail

- Builds/provisioning lancés par le user (flox activate -- ./mvnw / pulumi). Je propose, je ne lance pas.
- Vérifier preview après chaque ajout de unit (rke2-manifests.d/).
- Suivre les patterns établis : ManifestsUnit.lazy() + DomainRegistrar, fluent pipeline si étape
  multi-phase, ManifestDomainCatalog pour tout ID de domaine.

## État

| Étape | État | Notes |
|---|---|---|
| Cadrage / lecture plan | ✅ 2026-06-03 | prérequis manquants identifiés (vcluster operator, pulumi-command) |
| D1/D2/D3 arbitrage | ☐ | à trancher avec le user avant de coder |
| Étape 1 vcluster operator unit | ☐ | prérequis bloquant |
| Étape 2 readiness gate | ☐ | |
| Étape 3 GitopsMgmtVCluster Pulumi | ☐ | dépend pulumi-command |
| Étape 4 structure GitOps | ☐ | active les TODO FluxRoot |
| Étape 5 validation infra | ☐ | user |
