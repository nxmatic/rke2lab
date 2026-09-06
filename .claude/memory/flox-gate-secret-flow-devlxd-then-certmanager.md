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
Tout `Secret` **gated-sur-reveal + rendu dans la branche** est strippé par le render in-cluster secret-blind (in-cluster : `.secrets` sops-chiffré illisible ET pas de seal cluster-pki). **CORRECTION (traçage 2026-09-06) : seuls DEUX étaient réellement exposés, pas trois** :
- **flox webhook TLS** (`revealWebhookServing`, cluster-pki) — `FloxWebhookManifestsUnit` → **Porte 2**,
- **kubeconfig CAPI** `<cluster>-kubeconfig` (`revealOperatorPki`, cluster-pki) — `ClusterKubeconfigManifestsUnit` → **Porte 1 (FIXÉ, cf. plan)**.
`GithubAppSecretManifestsUnit` roule **DÉJÀ** sur la voie durable (`new PackageMetadataProfile("gitops","githubapp", true)`) → jamais dans la branche, **PAS strippé** (le diagnostic précédent le comptait à tort). Le **replicator source secret** aussi. Le push in-cluster marche via le token **PaC env** (`RKE2LAB_PUSH_TOKEN`).

## L'INVARIANT — manifests ne porte JAMAIS de matériel de secret dans une ressource réconciliée par Flux (les DEUX PORTES)
Corollaire de la branche secret-blind ; gravé dans `pac-in-cluster-render-spec §secret-delivery`. Un secret n'entre dans le cluster que par deux portes, choisies par **immuable vs rotatable** :
- **Porte 1 — voie durable NODE_BOOTSTRAP (grow-only)** : l'exploder détourne le Secret HORS de la branche → `.bootstrap/rke2lab-bootstrap.yaml` → `SERVER_MANIFESTS` → clé devlxd `user.rke2lab.server-manifests` au grow → oneshot RKE2 `rke2lab-server-manifests` au bootstrap master. **Ne joue JAMAIS dans une réconciliation** (le prune Flux est inventory-scoped ; ces ressources n'ont aucun label d'inventaire Flux car appliquées par RKE2 → Flux aveugle en apply ET prune → **PAS d'écrasement**). IMMUABLE : kubeconfig CAPI, githubapp, sops-age, replicator source, signing key. Opt-in = annotation `ManifestAnnotation.NODE_BOOTSTRAP` : whole-unit `PackageMetadataProfile(...,true)` OU **per-resource pour une unité MIXTE** (patron `ReplicatorManifestsUnit`/`ClusterKubeconfigManifestsUnit` : seul le credential + son namespace ridnt, `dependsOn` pour l'ordre du fichier bootstrap ; le reste reste sur la branche).
- **Porte 2 — un contrôleur mint in-cluster depuis une graine durable** : manifests émet une **CR (pas un Secret)** ; le contrôleur réconcilie le Secret in-cluster → un re-render blind ne re-émet que la CR, rien n'est strippé. ROTATABLE/dérivé : cert-manager (`Certificate` → mint+renew, ca-injector remplit le `caBundle`), replicator mittwald (fan-out depuis la source durable), **operator Tailscale (cert funnel `*.ts.net` in-cluster — précédent user validé)**.
- **Complémentaires** : un secret rotatable utilise souvent LES DEUX (Porte 1 = graine durable, Porte 2 = dérivation). **INTERDIT** = la 3e voie : un Secret reveal-peuplé rendu direct sur la branche → strippé silencieusement.

## PLAN — 2 étapes (RÉORDONNÉ 2026-09-06 après traçage)
1. **Étape 1 — Porte 1 (devlxd) — CODÉ (à builder + commit)** : `ClusterKubeconfigManifestsUnit` — le Secret CAPI `<cluster>-kubeconfig` + son namespace basculés **per-resource `NODE_BOOTSTRAP`** (l'operator-kubeconfig reste un dotfile `local-config` consommé host-side au grow, PAS sur la voie durable). githubapp/replicator déjà durables. **Réarme le kubeconfig CAPI, PAS la gate flox.**
2. **Étape 2 — Porte 2 (cert-manager) — RÉARME LA GATE FLOX** : `--enable-webhook` (daemonset) + le `MutatingWebhookConfiguration` + son `caBundle` sont TOUS rendus-branche ET gated sur `webhookServing().isPresent()` → **la voie durable seule NE PEUT PAS réarmer le webhook** (le `caBundle` surtout ne peut pas être rendu in-cluster sans le cert ; mettre juste le TLS sur Porte 1 = jetable). cert-manager mint le cert self-signed in-cluster (Issuer CA self-signed), POSSÈDE le Secret (jamais dans la branche, jamais prune), ca-injector remplit le `caBundle` ; `--enable-webhook`+manifests émis **INCONDITIONNELLEMENT**. Supprime le chemin cluster-pki `WEBHOOK_SERVING` (SEUL consommateur → cleanup total : `WebhookServingCredentials`, `ClusterPkiCoordinate.WEBHOOK_SERVING`, `ClusterSeal.mintWebhookServing`/`WEBHOOK_SERVING_DNS`, `SealedClusterPki`, `WebhookServingMaterial`, `ManifestSynthesisContext.webhookServing`, `ManifestSynthesisRequest.webhookServing`, `revealWebhookServing`, le gate `webhookServing().isPresent()`).

## Contraintes gravées (pour l'étape 2)
- Le **webhook flox DOIT rester self-signed** (cert-manager self-signed CA), PAS Tailscale : il gate les pods tailscale eux-mêmes (**circulaire**), tourne **avant le tailnet** (bootstrap), SAN interne `flox-webhook.rke2lab-system.svc` (Tailscale ne signe que `*.ts.net`).
- **Pas besoin de persister** ce cert (ni PVC ni ZFS) : l'API-server lit le `caBundle` depuis le `MutatingWebhookConfiguration` (ca-injector) → un CA self-signed régénéré par cluster-lifetime est cohérent (rien d'externe ne l'épingle). YAGNI.
- L'idée user **self-signed→Tailscale + persist `tank/rke2lab/persist`** est bonne mais pour les **certs EXTERNES** (funnel/headscale `*.ts.net`, confiance publique + stabilité d'identité au rebuild) → **3e chantier distinct**, pas le gate.

See [[cold-start-cleanup-and-funnel-cert-persistence]] [[manifests-publish-in-cluster-render]] [[flox-env-migration-design]].
