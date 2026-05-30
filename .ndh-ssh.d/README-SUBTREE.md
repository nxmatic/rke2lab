# SSH config & keys — shared git subtree

This folder is one of **two synchronized copies** of the same SSH configuration
and key bundle, shared between repositories as a `git subtree` (not a submodule,
not a flake input). Editing either copy and syncing through the integration
branch keeps both in step.

| Role | Repository | Path |
| --- | --- | --- |
| Canonical source | `nix-darwin-home` | `modules/home-manager/ssh.d/` |
| Consumer copy | `rke2lab` | `.ndh-ssh.d/` |
| Integration branch | `nix-darwin-home` (origin) | `split/hm-ssh.d` |

`split/hm-ssh.d` is a `subtree split` of `modules/home-manager/ssh.d` — its root
*is* the ssh.d files, so it is the ref both repositories pull from and push to.

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
# nix-darwin-home: refresh the integration branch from the latest ssh.d
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/hm-ssh.d --rejoin HEAD
git push origin split/hm-ssh.d
```

```bash
# rke2lab: pull the integration branch into the subtree (squashed)
git fetch nix-darwin-home split/hm-ssh.d
git subtree pull --prefix=.ndh-ssh.d \
  nix-darwin-home split/hm-ssh.d --squash
```

## Sync up (rke2lab → nix-darwin-home)

rke2lab's subtree was added with `--squash`, so its history does not contain
`split/hm-ssh.d`'s commits as ancestors — only a squash commit referencing them.
Pushing **directly** onto `split/hm-ssh.d` would therefore be rejected as
non-fast-forward. Push to a **side branch** and integrate in nix-darwin-home:

```bash
# rke2lab: split .ndh-ssh.d and push it to a fresh branch (fast-forward-safe)
git subtree push --prefix=.ndh-ssh.d \
  nix-darwin-home ssh.d-from-rke2lab
```

```bash
# nix-darwin-home: merge those changes into the source, then refresh the
# integration branch so the next "sync down" carries them
git fetch origin ssh.d-from-rke2lab
git subtree merge --prefix=modules/home-manager/ssh.d \
  --squash origin/ssh.d-from-rke2lab
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/hm-ssh.d --rejoin HEAD
git push origin split/hm-ssh.d
```

Always sync down before editing for an up-push, and keep `--squash` consistent on
both sides — mismatched squash settings make the synthetic histories diverge and
provoke avoidable merge conflicts.

## Initial setup (historical, already done in rke2lab)

```bash
git remote add nix-darwin-home https://github.com/nxmatic/nix-darwin-home.git
git fetch nix-darwin-home split/hm-ssh.d
git subtree add --prefix=.ndh-ssh.d \
  nix-darwin-home split/hm-ssh.d --squash
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
