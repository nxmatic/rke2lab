# Replication Inventory

This document catalogs all Kubernetes resources managed by the kubernetes-replicator controller. Resources are automatically propagated between namespaces based on annotations and labels defined in this inventory.

**Discovery:** All replicated resources are marked with `app.kubernetes.io/replicated: "true"` label for easy querying:

```bash
kubectl get configmaps,secrets -l app.kubernetes.io/replicated=true -A
```

## Source Resources (replicate-to)

Resources that push data to other namespaces using `replicator.v1.mittwald.de/replicate-to` annotation.

### flox-env ConfigMap

**Source:** `flox-runtime/flox-env`  
**Annotation:** `replicator.v1.mittwald.de/replicate-to: "headscale-system"`  
**Data:** Flox environment variables (FLOX_DISABLE_METRICS, FLOX_NO_TELEMETRY, FLOX_NONINTERACTIVE)  
**Purpose:** Propagate flox configuration to headscale agents in the mesh layer  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/runtime/flox-containerd-shim/01-configmap-flox-env.yaml`
- `rke2.d/bioskop/master/catalog/runtime/flox-containerd-shim/01-configmap-flox-env.yaml`
- `rke2.d/bioskop/master/manifests.d/runtime/flox-containerd-shim/02-configmap-flox-env.yml`
- `rke2.d/bioskop/master/manifests.yaml` (line 6025)

---

## Recipient Resources (replicate-from)

Resources that pull data from source namespaces using `replicator.v1.mittwald.de/replicate-from` annotation.

### Mesh Layer Resources

#### operator-oauth Secret

**Source:** `kube-system/operator-oauth`  
**Destination:** `${tailscale-namespace}` (typically `tailscale-system`)  
**Type:** Opaque Secret  
**Purpose:** Tailscale operator OAuth credentials  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/mesh/tailscale/02-secret-operator-oauth.yaml`
- `rke2.d/bioskop/master/catalog/mesh/tailscale/02-secret-operator-oauth.yaml`
- `rke2.d/bioskop/master/manifests.d/mesh/tailscale/02-secret-operator-oauth.yml`

#### flox-env ConfigMap (recipient)

**Source:** `flox-runtime/flox-env`  
**Destination:** `${headscale-namespace}` (typically `headscale-system`)  
**Data:** Empty in recipient; populated from source by replicator  
**Purpose:** Make flox config available to headscale clients  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Location:**
- `rke2.d/catalog/mesh/headscale/02-configmap-flox-env.yaml`
- `rke2.d/bioskop/master/manifests.d/mesh/headscale/02-configmap-flox-env.yml`

### GitOps Layer Resources (Porch)

#### porch-git-auth Secret

**Source:** `kube-system/porch-git-auth`  
**Destinations:**
- `porch-system/porch-git-auth`
- `porch-fn-system/porch-git-auth`

**Type:** kubernetes.io/basic-auth  
**Purpose:** Git authentication for Porch package management  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/gitops/porch/core/base/2-secrets-porch-auth.yaml`
- `rke2.d/bioskop/master/catalog/gitops/porch/core/base/2-secrets-porch-auth.yaml`
- `rke2.d/bioskop/master/manifests.d/gitops/porch/02-secret-porch-git-auth.yml`

#### porch-git-ssh Secret

**Source:** `kube-system/porch-git-ssh`  
**Destinations:**
- `porch-system/porch-git-ssh`
- `porch-fn-system/porch-git-ssh`

**Type:** kubernetes.io/ssh-auth  
**Purpose:** SSH authentication for Porch package management  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/gitops/porch/core/base/2-secrets-porch-auth.yaml`
- `rke2.d/bioskop/master/catalog/gitops/porch/core/base/2-secrets-porch-auth.yaml`
- `rke2.d/bioskop/master/manifests.d/gitops/porch/02-secret-porch-git-ssh.yml`

### CI/CD Layer Resources (Tekton)

#### tekton-git-auth Secret

**Source:** `kube-system/tekton-git-auth`  
**Destination:** `tekton-pipelines`  
**Type:** kubernetes.io/basic-auth  
**Purpose:** Git authentication for Tekton pipeline execution  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/cicd/tekton-pipelines/01-secrets-tekton-auth.yaml`
- `rke2.d/bioskop/master/catalog/cicd/tekton-pipelines/01-secrets-tekton-auth.yaml`
- `rke2.d/bioskop/master/manifests.d/cicd/tekton-pipelines/02-secret-tekton-git-auth.yml`

#### tekton-docker-config Secret

**Source:** `kube-system/tekton-docker-config`  
**Destination:** `tekton-pipelines`  
**Type:** kubernetes.io/dockerconfigjson  
**Purpose:** Docker registry authentication for Tekton image builds  
**Label:** `app.kubernetes.io/replicated: "true"`  

**Manifest Locations:**
- `rke2.d/catalog/cicd/tekton-pipelines/01-secrets-tekton-auth.yaml`
- `rke2.d/bioskop/master/catalog/cicd/tekton-pipelines/01-secrets-tekton-auth.yaml`
- `rke2.d/bioskop/master/manifests.d/cicd/tekton-pipelines/02-secret-tekton-docker-config.yml`

---

## Replication Flow Summary

```
kube-system (Source)
├── operator-oauth (Secret) → tailscale-system
├── porch-git-auth (Secret) → porch-system, porch-fn-system
├── porch-git-ssh (Secret) → porch-system, porch-fn-system
├── tekton-git-auth (Secret) → tekton-pipelines
└── tekton-docker-config (Secret) → tekton-pipelines

flox-runtime (Source)
└── flox-env (ConfigMap) → headscale-system
```

---

## Troubleshooting

### Verify replication is working:

```bash
# Check if kubernetes-replicator is running
kubectl get deployment -n replication-system -o wide

# View replicated resources
kubectl get secrets,cm -l app.kubernetes.io/replicated=true -A

# Check replicator logs
kubectl logs -n replication-system -l app.kubernetes.io/name=kubernetes-replicator -f

# Verify specific replication
kubectl get secret porch-git-auth -n porch-system -o yaml
kubectl get secret porch-git-auth -n kube-system -o yaml
# Compare the data sections
```

### Common Issues

**Resources not replicating:**
1. Verify source resource exists and has the correct `replicate-to` or `replicate-from` annotation
2. Verify destination namespace exists
3. Check kubernetes-replicator controller logs for errors
4. Ensure all manifest files have `app.kubernetes.io/replicated: "true"` label for discoverability

**Namespace doesn't exist:**
- GitOps/Tekton use namespace variables like `${headscale-namespace}` that must be resolved during rendering
- Ensure `make generate@kpt` completes before applying manifests

---

## Related Documentation

- **Kubernetes Replicator:** https://github.com/mittwald/kubernetes-replicator
- **RKE2Lab Architecture:** See `docs/` directory
- **KPT Packages:** `rke2.d/catalog/*/` directories contain source manifests
- **Systemd Manifest Installation:** `make.d/incus/systemd/rke2lab-*-manifests.service`
