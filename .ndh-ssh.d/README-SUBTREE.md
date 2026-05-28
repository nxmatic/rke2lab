# SSH Keys Subtree from nix-darwin-home

This directory is a git subtree imported from the nix-darwin-home repository.

**Source**: https://github.com/nxmatic/nix-darwin-home.git  
**Branch**: `split/hm-ssh.d` (split from `develop:modules/home-manager/ssh.d`)  
**Path**: `modules/home-manager/ssh.d`

## Initial Setup (already done)

```bash
# Add nix-darwin-home as a remote
git remote add nix-darwin-home https://github.com/nxmatic/nix-darwin-home.git

# Fetch the split branch
git fetch nix-darwin-home split/hm-ssh.d

# Add the split branch as a squashed subtree
git subtree add --prefix=.ndh-ssh.d \
  nix-darwin-home split/hm-ssh.d \
  --squash
```

## Updating from nix-darwin-home

**In nix-darwin-home** (after committing changes to `modules/home-manager/ssh.d`):

```bash
# Update the split branch to reflect latest ssh.d changes
git subtree split --prefix=modules/home-manager/ssh.d \
  --branch=split/hm-ssh.d \
  --rejoin \
  HEAD

# Push the split branch
git push origin split/hm-ssh.d
```

**In rke2lab** (to pull updates):

```bash
# Fetch the split branch from nix-darwin-home
git fetch nix-darwin-home split/hm-ssh.d

# Pull the split branch into our subtree (squashed)
git subtree pull --prefix=.ndh-ssh.d \
  nix-darwin-home split/hm-ssh.d \
  --squash
```

## Purpose

This subtree provides the canonical SSH key definitions (including `rke2-cluster`) used for:

- Flux SOPS age key derivation (`ssh-to-age` conversion)  
- Single source of truth for SSH keys across nix-darwin-home and rke2lab

The `keys.yaml` file contains metadata about all SSH keys, including:

- Key types and public keys
- Usage annotations
- Certificate authorities  
- Profile associations

## SOPS Encryption

The `keys.yaml` file is SOPS-encrypted. In nix-darwin-home, it's encrypted with 4 age recipients:

- `age10ey0l...` - Operator key (primary)
- `age1trxp...` - Additional operator key
- `age17q5k...` - Additional operator key  
- `age1k0tc4...` - **rke2-cluster key** (derived from `rke2-cluster` SSH key via `ssh-to-age`)

The rke2-cluster age key (`age1k0tc4gmaqrk5df3ujja34gkqxstu0cye7fl7fktjeuua3yych3aqxfjlak`) is:

- Derived from SSH key: `ssh-to-age < ~/.local/var/run/secrets/ssh-keys/rke2-cluster.pub`
- Used as second recipient in rke2lab's `.sops.yaml` (operator key + flux key)
- Enables both operator AND Flux in-cluster to decrypt the same content
- Single source of truth: SSH private key stored only in nix-darwin-home secrets

## Git Filter

The `.gitattributes` file configures git sops filter for `keys.yaml`:

```gitattributes
.ndh-ssh.d/keys.yaml filter=sops-yaml
```

This means:

- **Working tree**: keys.yaml is auto-decrypted (plaintext)
- **Git index/commits**: keys.yaml is encrypted
- **Java code reads**: plaintext (thanks to git filter)
