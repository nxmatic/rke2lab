# Agent Instructions

## Stage policy

- At this stage of the project, do **not** implement backward-compatibility logic.
- Prefer a single canonical behavior and a single canonical configuration path.
- Do not add compatibility aliases for deprecated config keys unless explicitly requested by the user.

## Command execution environment

- Run verification and operational commands in a terminal that is already inside an SSH session on `bioskop-nixos.local`.
- Ensure the Flox environment for this repository is activated before running project commands.
- Preferred working directory is:
  - `/var/lib/git/nxmatic/rke2lab`

## Policy-first execution order

- Before exercising the controlnode via Pulumi (`preview`/`up`), implement any newly agreed policy changes first.
- Keep policy changes in small, incremental commits before Pulumi controlnode exercise.
- Pulumi controlnode exercise is blocked until policy updates are implemented and committed.

## Checkpointing policy

- The agent may identify appropriate checkpoints without waiting for an explicit user request.
- When a checkpoint is warranted, commit changes proactively.
- Always split checkpoints by subject; do not mix unrelated changes into the same commit.
- Prefer small, reviewable conventional commits with `@codebase` and `@copilot` tags.
- Use the AI commit author identity `GitHub Copilot <copilot@github.com>` unless the user explicitly requests a different author.
