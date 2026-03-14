# Agent Instructions

## Stage policy

- At this stage of the project, do **not** implement backward-compatibility logic.
- Prefer a single canonical behavior and a single canonical configuration path.
- Do not add compatibility aliases for deprecated config keys unless explicitly requested by the user.

## Command execution environment

- Run verification and operational commands in a terminal that is already inside an SSH session on `bioskop-nixos.local`.
- Ensure the Flox environment for this repository is activated before running project commands.
- Preferred working directory is:
  - `/net/bioskop.local/private/var/lib/git/nxmatic/rke2lab-management-cluster`
