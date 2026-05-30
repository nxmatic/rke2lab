# SSH config & keys — shared git subtree

This folder is one of **two synchronized copies** of the same SSH configuration
and key bundle, shared between repositories as a `git subtree` (not a submodule,
not a flake input). Editing either copy and syncing through the integration
branch keeps both in step.

**`nix-darwin-home` owns the tree.** `modules/home-manager/ssh.d/` here is the
canonical source of the SSH config and keys; `rke2lab`'s `.ndh-ssh.d/` is a
synchronized consumer copy. Tree ownership is independent of the branch names
below.

| Role | Repository | Path |
| --- | --- | --- |
| Canonical source (owns the tree) | `nix-darwin-home` | `modules/home-manager/ssh.d/` |
| Consumer copy | `rke2lab` | `.ndh-ssh.d/` |
| Branch published by nix-darwin-home | `nix-darwin-home` (origin) | `split/nix-darwin-home/hm-ssh.d` |
| Branch published by rke2lab | `nix-darwin-home` (origin) | `split/rke2lab/hm-ssh.d` |

Each branch is a `subtree split` of the ssh.d tree — its root *is* the ssh.d
files, so it is a ref the two repositories sync through.

Integration branches follow the scheme **`split/{repo}/{tree}`**, where:

- **`{repo}` owns and publishes the branch** — it `subtree split`s the tree from
  its own copy and pushes the branch. The *other* repo **pulls** from it.
- This is symmetric: each repo publishes the branch named after itself, and
  consumes the branch named after its peer.

So there are two branches for `hm-ssh.d`:

- `split/nix-darwin-home/hm-ssh.d` — nix-darwin-home publishes it (sync **down**);
  rke2lab pulls from it.
- `split/rke2lab/hm-ssh.d` — rke2lab publishes it (sync **up**); nix-darwin-home
  pulls from it.

Both carry the same tree; they differ only in which repo refreshes each.
**`{repo}` is the branch's publisher, not the tree owner** — the tree is always
nix-darwin-home's.

## Why a subtree and not a flake input

rke2lab's `flake.nix` holds an INVARIANT: **nix-darwin-home must never be a flake
input of rke2lab** (the two repos depend on each other in opposite scopes; an
input edge would create a flake-eval cycle). The subtree shares this content over
plain git — no eval edge, no token, no cycle — which is exactly why it is the
right mechanism here. Treat the subtree as the *only* sanctioned channel for
ssh.d content to reach rke2lab.

## Sync down (nix-darwin-home → rke2lab)

After committing changes to `modules/home-manager/ssh.d` in nix-darwin-home:

```bash
# nix-darwin-home: refresh ITS OWN branch from the latest ssh.d
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/nix-darwin-home/hm-ssh.d --rejoin HEAD
git push origin split/nix-darwin-home/hm-ssh.d
```

```bash
# rke2lab: pull nix-darwin-home's branch into the subtree (squashed)
git fetch nix-darwin-home split/nix-darwin-home/hm-ssh.d
git subtree pull --prefix=.ndh-ssh.d \
  nix-darwin-home split/nix-darwin-home/hm-ssh.d --squash
```

## Sync up (rke2lab → nix-darwin-home)

After committing changes to `.ndh-ssh.d` in rke2lab, publish rke2lab's own
branch, then pull it into the source tree in nix-darwin-home:

```bash
# rke2lab: split .ndh-ssh.d onto ITS OWN branch and push it
git subtree split --prefix=.ndh-ssh.d \
  --branch=split/rke2lab/hm-ssh.d --rejoin HEAD
git push nix-darwin-home split/rke2lab/hm-ssh.d
```

```bash
# nix-darwin-home: pull rke2lab's branch into the source ssh.d tree (squashed),
# then refresh its own branch so the next "sync down" carries the change
git fetch origin split/rke2lab/hm-ssh.d
git subtree pull --prefix=modules/home-manager/ssh.d \
  origin split/rke2lab/hm-ssh.d --squash
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/nix-darwin-home/hm-ssh.d --rejoin HEAD
git push origin split/nix-darwin-home/hm-ssh.d
```

Always sync down before editing for an up-push, and keep `--squash` consistent on
both sides — mismatched squash settings make the synthetic histories diverge and
provoke avoidable merge conflicts.

## Initial setup (historical, already done in rke2lab)

```bash
git remote add nix-darwin-home https://github.com/nxmatic/nix-darwin-home.git
git fetch nix-darwin-home split/nix-darwin-home/hm-ssh.d
git subtree add --prefix=.ndh-ssh.d \
  nix-darwin-home split/nix-darwin-home/hm-ssh.d --squash
```

## Purpose

This subtree is the single source of truth for the SSH key definitions
(including `rke2-cluster`) used for:

- Flux SOPS age key derivation (`ssh-to-age` conversion)
- Consistent SSH key metadata across nix-darwin-home and rke2lab

`keys.yaml` carries metadata for every SSH key: key types and public keys, usage
annotations, certificate authorities, and profile associations.

## SOPS encryption

`keys.yaml` is SOPS-encrypted, with 4 age recipients:

- `age10ey0l...` — Operator key (primary)
- `age1trxp...` — Additional operator key
- `age17q5k...` — Additional operator key
- `age1k0tc4...` — **rke2-cluster key** (derived from the `rke2-cluster` SSH key via `ssh-to-age`)

The rke2-cluster age key
(`age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak`) is:

- Derived from the SSH key: `ssh-to-age < ~/.local/var/run/secrets/ssh-keys/rke2-cluster.pub`
- Used as a recipient in rke2lab's `.sops.yaml` (operator key + flux key)
- What lets both the operator AND Flux in-cluster decrypt the same content
- Single source of truth: the SSH private key is stored only in nix-darwin-home secrets

## Git filter

Each repository's `.gitattributes` configures the sops filter for `keys.yaml`
(the path differs per repo — `.ndh-ssh.d/keys.yaml` in rke2lab,
`modules/home-manager/ssh.d/keys.yaml` in nix-darwin-home):

```gitattributes
<path>/keys.yaml filter=sops-yaml
```

This means:

- **Working tree**: `keys.yaml` is auto-decrypted (plaintext)
- **Git index/commits**: `keys.yaml` is encrypted
- **Code reads**: plaintext (thanks to the git filter)
