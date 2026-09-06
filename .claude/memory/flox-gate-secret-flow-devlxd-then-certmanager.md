---
name: flox-gate-secret-flow-devlxd-then-certmanager
description: "DIAGNOSTIC (2026-09-06, non fixé) — le scheduling-gate flox est INERTE en cluster (tailnet-purge & co démarrent avant leur env → CreateContainerError, self-heal par retry NRI). Racine : le webhook flox n'est pas déployé car son cert (cluster-pki, grow-sealed) est strippé par le render in-cluster secret-blind — cas d'un problème SYSTÉMIQUE de flow des secrets grow→cluster. Plan en 2 étapes tranché : (1) devlxd/NODE_BOOTSTRAP, (2) cert-manager."
metadata:
  node_type: memory
  type: project
---

**Diagnostic conclusif (analyse read-only, non fixé). Plan tranché, à implémenter APRÈS compact.**

## Symptôme
Pendant la reconciliation cold-start (avant que les flox envs soient réalisés), le pod `tailnet-purge` (Job, ns `mesh-system`, annotation `flox.seedmatic.io/environment.purge: mesh/tailnet`) échoue `CreateContainerError` : `failed to resolve flox environment: flox environment mesh/tailnet not GC-rooted at /nix/var/nix/gcroots/flox-runtime/env/mesh/tailnet`. Self-heal par retry NRI quand le DaemonSet réalise le gcroot (~12 min de reconcile).

## Racine (prouvée)
Le pod n'a **jamais été gaté** → il est schedulé avant que son env existe. La logique du gate est **saine** (`PodGateReconciler.readyNodesForEnv` : `nr.Ready && nr.ObservedGeneration==env.Generation` ; env absent → reste gaté, ligne 64-67 ; le tout derrière `hasGate` ligne 45). Le problème est **en amont** : **le webhook n'est pas déployé**.
- Live : `kubectl get mutatingwebhookconfiguration` → **aucun flox**. `daemonset/flox-controller` args = `--gcroot-base`, `--env-root` — **PAS `--enable-webhook`** (que le DaemonSet node-agent, pas de mode cluster-manager).
- Le webhook ET le `PodGateReconciler` ne sont enregistrés QUE sous `--enable-webhook` (flox-controller `cmd/.../main.go`, mode cluster-manager). Le DaemonSet fait double-emploi : node-agent + webhook **quand `--enable-webhook`**.
- `--enable-webhook` + le `MutatingWebhookConfiguration` sont émis **UNIQUEMENT si `ManifestSynthesisContext.current().webhookServing().isPresent()`** au render (`FloxControllerManifestsUnit.java:247`, `FloxWebhookManifestsUnit.java:85-86`).
- `webhookServing()` = `revealWebhookServing()` → cellier `ClusterPkiCase.WEBHOOK_SERVING`, **scellé UNIQUEMENT au GROW** (cluster-pki `ClusterSeal`/`ClusterPkiSealScenario`, cellier DURABLE Pulumi). L'**in-cluster render** (`PublishCliScenario`) tourne avec un **`EphemeralCellar`** semé seulement par `ghapp` → `WEBHOOK_SERVING` absent → `webhookEnabled=false` → **le webhook est strippé de la branche** (grow l'allume, 1er render in-cluster l'éteint ; prouvé : à `manifests/bioskop-mgmt@e0f789d5`, `--enable-webhook` absent du daemonset, `workloads/runtime/flox-webhook/` = group-marker vide).

## C'est SYSTÉMIQUE — flow des secrets grow→cluster
Tout `Secret` **gated-sur-reveal + rendu dans la branche** est strippé par le render in-cluster secret-blind (in-cluster : `.secrets` sops-chiffré illisible ET pas de seal cluster-pki). Prouvé au tip `e0f789d5` (réduits à `.configmap-*.group.yml` vides) :
- **flox webhook TLS** (`revealWebhookServing`, cluster-pki) — `FloxWebhookManifestsUnit`,
- **kubeconfig operator/CAPI** (`revealOperatorPki`, cluster-pki) — `ClusterKubeconfigManifestsUnit`,
- **githubapp Secret** (`revealGithubApp`) — `GithubAppSecretManifestsUnit`.
Le **replicator source secret** est déjà OK (voie node-bootstrap). Le push in-cluster marche car il utilise le token **PaC env** (`RKE2LAB_PUSH_TOKEN`), pas le mint (revealGithubApp vide in-cluster).

## Les deux voies
- **Durable — NODE_BOOTSTRAP** : grow scelle → exploder collecte HORS branche dans `.bootstrap/rke2lab-bootstrap.yaml` → `SERVER_MANIFESTS` au cellier → grow **pose sur la devlxd key** `user.rke2lab.server-manifests` → master applique au bootstrap. Hors git, jamais touché par un render in-cluster. ✓
- **Fragile — rendu branche** : `Secret` dans la branche gated-sur-reveal → strip in-cluster. ✗

## PLAN TRANCHÉ (user, 2 étapes)
1. **Étape 1 (immédiate, résout le strip)** : router les 3 unités (**flox webhook TLS, kubeconfig operator/CAPI, githubapp Secret**) sur la **voie devlxd/NODE_BOOTSTRAP** (comme le replicator). Les secrets circulent grow→cluster par devlxd, plus par la branche.
2. **Étape 2 (ensuite)** : aligner le provisioning du **cert du webhook** sur le **standard K8s = cert-manager** (mint in-cluster, cert-manager POSSÈDE le Secret → jamais dans la branche, jamais prune ; le render n'y touche plus). Supprime le flow de ce cert + le chemin cluster-pki `WEBHOOK_SERVING` (SEUL consommateur = le webhook flox → cleanup total : `WebhookServingCredentials`, `ClusterPkiCoordinate.WEBHOOK_SERVING`, `ClusterSeal.mintWebhookServing`/`WEBHOOK_SERVING_DNS`, `SealedClusterPki`, `WebhookServingMaterial`, `ManifestSynthesisContext.webhookServing`, `ManifestSynthesisRequest.webhookServing`, `revealWebhookServing`, le gate `webhookServing().isPresent()`).

## Contraintes gravées (pour l'étape 2)
- Le **webhook flox DOIT rester self-signed** (cert-manager self-signed CA), PAS Tailscale : il gate les pods tailscale eux-mêmes (**circulaire**), tourne **avant le tailnet** (bootstrap), SAN interne `flox-webhook.rke2lab-system.svc` (Tailscale ne signe que `*.ts.net`).
- **Pas besoin de persister** ce cert (ni PVC ni ZFS) : l'API-server lit le `caBundle` depuis le `MutatingWebhookConfiguration` (ca-injector) → un CA self-signed régénéré par cluster-lifetime est cohérent (rien d'externe ne l'épingle). YAGNI.
- L'idée user **self-signed→Tailscale + persist `tank/rke2lab/persist`** est bonne mais pour les **certs EXTERNES** (funnel/headscale `*.ts.net`, confiance publique + stabilité d'identité au rebuild) → **3e chantier distinct**, pas le gate.

See [[cold-start-cleanup-and-funnel-cert-persistence]] [[manifests-publish-in-cluster-render]] [[flox-env-migration-design]].
