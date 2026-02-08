# Statement of Work (SOW)

## Context
We are evolving the custom systemd orchestration for the RKE2Lab environment. Recent changes include prefixed unit names to avoid collisions with upstream RKE2, and flox/Nix build flow adjustments.

## Objectives
- Refactor readiness checks into post-start behaviors where appropriate to align with standard systemd patterns.
- Simplify and standardize unit and script naming conventions for clarity and consistency.
- Reduce unnecessary target dependencies while preserving correct boot ordering.
- Keep documentation and activation scripts aligned with the new conventions.

## Scope
- Custom systemd units and targets installed by this repository.
- Associated scripts invoked by those units.
- Documentation describing boot flow and systemd target architecture.

## Out of Scope
- Upstream `rke2-server.service` and `rke2-agent.service` definitions.
- Non-systemd runtime components not managed by these units.
- Unrelated docs and packages outside the custom systemd layer.

## Assumptions
- The RKE2 server/agent units remain the authoritative upstream services.
- Custom units are deployed via bind mounts and activation scripts.
- The cluster uses the rke2lab-prefixed unit naming moving forward.

## Remote Execution Pattern
Use this command pattern to run commands on the NixOS VM and inside the Incus node:

```
ssh <host>-nixos.local -- incus exec <node> -- flox activate --dir=/var/lib/rancher/rke2 -- <cmd>
```

Examples:
- `ssh bioskop-nixos.local -- incus exec master -- flox activate --dir=/var/lib/rancher/rke2 -- kubectl get nodes`
- `ssh bioskop-nixos.local -- incus exec master -- systemctl status rke2lab-mesh-ready.target`

## Host Orchestration Conventions
- On the NixOS VM, activate flox in the rke2lab working tree before orchestration:
	`ssh <host>-nixos.local -- flox activate --dir=/var/lib/git/nxmatic/rke2lab -- <cmd>`
- Always use `remake` (not `make`) for deployment orchestration.

## Deliverables
- Updated unit files and scripts per new naming conventions.
- Reduced target dependencies with readiness checks integrated into post-start behavior where appropriate.
- Updated documentation reflecting the revised flow.

## Acceptance Criteria
- systemd units load without naming conflicts.
- Boot flow remains stable with correct ordering.
- Docs and scripts match the implemented unit names.
- Ready checks still run and fail fast when prerequisites are not met.
