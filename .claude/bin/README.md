# Claude Code Hooks

This directory contains hook scripts that run during Claude Code session lifecycle events.

## Checkpoint System

The checkpoint system automatically creates snapshots of your work before context compaction, allowing you to recover state if needed.

### How it works

1. **PreCompact** (`checkpoint-write.sh`): Creates a checkpoint before compaction
   - Filename format: `checkpoint-<session_id>-<timestamp>.md`
   - Timestamp format: `YYYYMMDD-HHMMSS` (e.g., `20260605-191826`)
   - Contains: recent commits, git status, suggested next steps
   - Non-destructive: only writes, never deletes

2. **PostCompact** (`checkpoint-gc.sh`): Garbage-collects old checkpoints
   - Phase 1: Removes ALL checkpoints for deleted sessions (orphans)
   - Phase 2: For active sessions, keeps only the N most recent checkpoints
   - Default: keep last 3 checkpoints per session
   - Session ID extraction: strips `-YYYYMMDD-HHMMSS` suffix from filename

### Configuration

Set the `CLAUDE_CHECKPOINT_KEEP_LAST` environment variable to control retention:

```json
{
  "env": {
    "CLAUDE_CHECKPOINT_KEEP_LAST": "5"
  }
}
```

Default is 3 if not set.

### Files

- `checkpoint-write.sh` - PreCompact hook (creates checkpoints)
- `checkpoint-gc.sh` - PostCompact hook (garbage collection)
- Generated checkpoints: `.claude/checkpoint-*.md` (gitignored)

### Manual operations

```bash
# List checkpoints for a session
ls -lt .claude/checkpoint-<session_id>-*.md

# View a specific checkpoint
cat .claude/checkpoint-<session_id>-<timestamp>.md

# Clean all checkpoints (USE WITH CAUTION)
rm .claude/checkpoint-*.md

# Clean checkpoints for a specific session
rm .claude/checkpoint-<session_id>-*.md
```

### Troubleshooting

**No checkpoints created?**

- Check that `yq` (yq-go v4) is available: `yq --version`
- Check hook execution in Claude Code output
- Verify `.claude/settings.json` has PreCompact/PostCompact hooks configured

**Too many/few checkpoints kept?**

- Set `CLAUDE_CHECKPOINT_KEEP_LAST` in `.claude/settings.json` env section
- Default is 3 per session

**Orphaned checkpoints not cleaned?**

- GC only runs after successful compaction
- Unkeyed checkpoints (`unkeyed-*`) are never auto-cleaned (manual cleanup needed)

## Related Documentation

- [Claude Code hooks documentation](https://docs.anthropic.com/claude-code/hooks)
- Session checkpoint commit: `c1f053d1` (2026-06-04)
