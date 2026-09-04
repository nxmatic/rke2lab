---
name: ghapp-webhook-reconcile-and-funnel-rename
description: ghapp-webhook scion re-points the App webhook at grow; funnel renamed pac-webhook→pipelines-webhook via PacWebhookFunnel SSOT; force:true on service Kustomizations; deploy hot-fix + cert backed up
metadata:
  type: project
---

**SHIPPED + PUSHED 2026-09-04, commit `8835b9fae` on `feature/nixos-node-substrate`.** Closes the last operator-manual GitHub-UI step (the App webhook) and gives the PaC funnel a durable identity.

## ghapp-webhook scion (sown at grow, reconciles the App webhook)
- **Contract `ghapp-contract` (bump 1.0.0→1.1.0)**: `GithubAppWebhookConfigurer.configure(GithubAppCredentials, WebhookConfig)` verb; `WebhookConfig(url, secret)`; `WebhookReconcileInput(@Amendment(Amendment.FUNNEL) String funnelUrl)` — `@SeedContract("runbook")` (= `RunbookCoordinate.SLUG`).
- **Edge `ghapp-edge`**: `GithubAppWebhookConfigurerEdge` — `PATCH /app/hook/config` (App JWT via `AppJwt`, content_type=json, insecure_ssl=0), `@Component property="rke2lab.gardening=cultivating"` → filtered under survey/preview so the scion PENDS (twin of `GithubAppTokenMinter`).
- **bdd `ghapp-bdd`**: `GithubAppWebhookScenario` (creds ← cellar `GhAppCoordinate.GITHUB_APP`; secret ← `.secrets` key `github` path `.webhook.secret` — the SAME value PaC validates; `@OsgiService(await=false) Optional<GithubAppWebhookConfigurer>` → empty under survey → no-op, the [[mock-service-substitution-pattern]] gate shape) + `GithubAppWebhookRunbookHandler` (`RunbookCoordinate("ghapp-webhook")`, `seedFrom`=`INPUT.into(codec.decode(...))`) + `GithubAppWebhookAmendReflector` (`AmendCoordinate("ghapp-webhook")`, binds FUNNEL role → funnelUrl). Added `seed-broker-codec` dep. Template = the `dataplan` domain (AmendmentBinder/AmendmentAssembler).
- **broker-port**: new `Amendment.FUNNEL` role (String constant, additive/non-breaking).
- **Host `ClusterSeedScenario`**: `the_github_app_webhook_is_reconciled` nested step, sows `ghapp-webhook` AFTER `the_github_app_is_registered`, amendment `Map.of(Amendment.FUNNEL, funnelUrl)`. Funnel URL = the ONLY host-held fact (leaf is a constant but the tailnet is host-config Tailscale appends at runtime — never on the in-container synth context).

## Funnel identity SSOT + rename (fresh LE budget)
- `PacWebhookFunnel(tailnet)` record: `LEAF` + `url()` (=`https://<LEAF>.<tailnet>` — `config.tailnet()` ALREADY carries `.ts.net`, do NOT re-append). **Lives in `manifests-ingress-contract` (`manifests.ingress`, type=DUAL-REALM), NOT `manifests-contract`** — the latter is bundle-only, and host-flat `GithubAppCli`/`ClusterSeedScenario` referencing it TRIPS the `realm-boundary` staging gate (NoClassDefFoundError at runtime). Dual-realm is exactly for a pure JDK-only manifests-owned type both realms read. See [[spec-coverage-gate-state]].
- **Renamed `pac-webhook` → `pipelines-webhook`** (user chose it; durable). LE limits are per-FQDN → a different hostname = fresh cert bucket → bypasses the `pac-webhook` 429 without waiting. Persistence (below) then never re-issues.
- **`FunnelStatePersistenceManifestsUnit` proxy-class + state-secret (`ts-<LEAF>-state`) + PV/PVC/ZFSVolume (`<LEAF>-funnel-cert`) all derive from `PacWebhookFunnel.LEAF`** → a rename is ONE edit. (cdk8s construct-ids left literal — invisible in-cluster, touching them churns annotation hashes.)
- Spec-coverage gate satisfied by documenting the 4 new exported types (`PacWebhookFunnel`, `GithubAppWebhookConfigurer`, `WebhookConfig`, `WebhookReconcileInput`) in `docs/architecture/osgi/ghapp-domain-spec.adoc` (new "webhook reconcile" section + UML). Gate matches simple-name-as-whole-word in any `docs/**/*.adoc`.

## force:true on service Kustomizations (immutable Jobs)
`FluxServiceKustomizationPlanner.kustomization(...)` now sets `spec.force=true` on EVERY service cell. Reason: a cell may render a one-shot Job (funnel-cert restore/backup) whose pod template changes across renders; `Job.spec.template` is immutable → server-side apply fails "field is immutable" → the whole cell wedges `Ready=false` until an operator `kubectl delete job`. force = Flux delete-and-recreate on that failure (no-op otherwise). See [[flux-per-service-kustomizations]].

## GithubAppCli — minimize operator UI
`REGISTRATION_URL` enriched: `webhook_active=true` + `webhook_url=<PacWebhookFunnel(DEFAULT_TAILNET).url()>` + `events[]` (check_run/check_suite/commit_comment/issue_comment/pull_request/push, percent-encoded `%5B%5D`). The ONE field GitHub forbids as a URL param is `webhook_secret` → the grow's ghapp-webhook scion sets it. `BootstrapConfig.DEFAULT_TAILNET` made package-visible for the CLI. Irreducible operator clicks remain: create-confirm, key-gen, install.

## DEPLOY state (2026-09-04, bioskop-mgmt) — cert SAFE, re-grow pending
- The render pipeline ALREADY deployed the `pipelines-webhook` manifests (Flux rev `manifests/bioskop-mgmt@sha1:81aa08…`). Funnel got a FRESH LE cert: `ts-pipelines-webhook-state` carries `.crt`+`.key`+`_machinekey`/PrivateNodeKey+`acme-account.key.pem` → **lossless restore** (same device → served, no re-issue).
- funnel-state Kustomizations were `Ready=false` on the immutable Jobs (pre-existing alpine Jobs vs new flox-carrier template). **User deleted the jobs** → Flux recreated them (flox-carrier + `kube/base` FloxEnv, kubectl+yq) → both **Complete** → cert **backed up** to `tank/rke2lab/persist/pipelines-webhook-funnel-cert`. `force:true` (above) prevents the recurrence.
- **openebs-zfs CREATES the child dataset** under the persist pool (proven: `pac-webhook-funnel-cert` PV was Bound/Ready) — it does NOT adopt dataplan's pre-declared `persist/funnel-cert`. So LEAF-derived PV names are safe (fresh child on rename).
- Safety: local dump in `.local.d/funnel-cert-backup/*.yaml` (gitignored) as a 3rd net (dataset + PV + local).
- **NEXT (pending)**: rebuild the seed-image + re-grow WITH commit `8835b9fae` — the earlier grow used the OLD image (`8613dec…`, no scion) so **the App webhook on GitHub still points at `pac-webhook`** (dead) → GitHub POSTs 404 → PaC won't fire. Only a grow on the new image runs the `ghapp-webhook` scion that re-points it. Then verify PaC fires end-to-end on a push.
- Pulumi.dev.yaml: the `debug:` block (mesh/networking/nriPlugins.flox) is defaulted `false` (debug flavors off — prod flox NRI unaffected); committed as the branch default.

See [[cold-start-cleanup-and-funnel-cert-persistence]] [[tailscale-operator-funnel-nodeattr]] [[manifests-publish-in-cluster-render]] [[flux-per-service-kustomizations]].
