---
name: precompact-session-state
description: Author session-specific checkpoint before compaction - captures current task, blockers, discoveries, and concrete next steps
when: Automatically before context compaction; periodically during long sessions
---

# PreCompact Session State Capture

## Purpose

Write a detailed checkpoint capturing **conversation-specific state** that git cannot preserve:

- Current task (the actual goal, not just "fixing a bug")
- Why this approach was chosen
- Discoveries/learnings during the session
- Current blockers or open questions
- Concrete next steps with file paths and specifics

This draft will be merged with git state by the PreCompact hook to create a complete recovery point.

## Execution

Write `.claude/checkpoint-draft-<SESSION_ID>.md` with this structure:

```markdown
# Session Context

## Current Task

[One sentence: what are you implementing/fixing/investigating?]

## Context & Motivation

[2-3 sentences: why this work? What problem does it solve? Background needed to resume?]

## Approach Taken

[Key decisions, patterns applied, architecture choices. Why this way not alternatives?]

## Discoveries & Learnings

[Things learned that aren't obvious from code:
- API limitations discovered
- Performance characteristics
- Edge cases identified
- Patterns that worked/didn't work]

## Current State

- **Status**: [In progress / Blocked / Ready for review]
- **Last action**: [What was the last thing done?]
- **Key files**: [Main files touched - hook adds full git status]

## Blockers & Open Questions

[What's preventing progress? What needs user decision? What's unclear?]

## Next Steps

[Concrete, actionable steps with file paths:
1. Edit path/to/File.java:123 - add XYZ method
2. Run `mvn test -Dtest=Specific` to verify
3. If passes, commit "feat: ..."

NOT generic like "continue implementation"]

## Related Documentation

[Links to docs that help resume: CLAUDE.md sections, architecture docs]
```

## Guidelines

**Be specific:**
- ❌ "Working on manifest system"
- ✅ "Adding lazy instantiation to 27 ManifestsUnit classes to eliminate null scope parameters"

**Include WHY:**
- ❌ "Modified IncusResourceBootstrap.java"
- ✅ "Refactored ImageStateSynthesizer to local class - reduces namespace pollution in 171-method class"

**Actionable next steps:**
- ❌ "Continue refactoring"
- ✅ "Refactor FluxResourcesManifestUnit.java:45 - move inner class to local (lines 245-280)"

**Capture discoveries:**
- API limitations hit
- Performance observed
- What didn't work and why switched
- Patterns validated/rejected

**If blocked, be explicit:**
- What tried that failed
- What information missing
- What needs user decision

## When to Execute

1. **Before compaction**: When detecting compaction about to happen
2. **Periodically**: Every ~20-30 turns during long sessions
3. **After milestones**: Completing phase, discovering something important, hitting blocker

## Output Format

After writing, confirm briefly:
```
Checkpoint draft updated: .claude/checkpoint-draft-<session_id>.md
```

## Anti-Patterns

**Don't write generic summaries:**
- "Made progress" → useless
- "Encountered issues" → what issues?
- "Continue testing" → which tests, why?

**Don't duplicate git:**
- File lists (git status has this)
- Commit history (git log)
- Diff content (git diff)

**Don't reference conversation:**
- "As discussed..." → checkpoint read in new session
- "User asked..." → state goal directly
- "Earlier I said..." → self-contained

**Focus on what git CANNOT capture:**
- Intent behind changes
- Decisions and rationale
- Failed attempts not in commits
- Open questions/blockers
- Next steps with context

## Example

```markdown
# Session Context

## Current Task

Refactoring 27 ManifestsUnit classes to lazy instantiation pattern.

## Context & Motivation

CDK8s Constructs require Chart scope at construction, but ManifestsUnit instances registered statically before Chart exists. Lazy instantiation via interface factory resolves mismatch cleanly.

## Approach Taken

Pattern: `ManifestsUnit.lazy(id, deps, (scope, id) -> new ConcreteUnit(scope, id))`
All 27 units migrated to ONLY two-parameter constructor. Removed no-arg constructors for uniformity.

## Discoveries & Learnings

- Records can't have private canonical constructors (Java spec)
- Absolute uniformity critical: one legacy variant breaks pattern audit
- Local classes better than inner classes for single-use (namespace pollution)

## Current State

- **Status**: Implementation complete, verifying
- **Last action**: Migrated final 3 units
- **Key files**: 27 files in manifests/src/main/java/.../unit/

## Blockers & Open Questions

None - ready to verify.

## Next Steps

1. `flox activate -- ./mvnw -pl :manifests clean install`
2. `grep -r "ManifestsUnit()" manifests/src/` - confirm no no-arg calls
3. If clean: commit "refactor(manifests): lazy instantiation for 27 units"
4. Update docs/architecture/manifests/manifests-architecture.adoc
5. Next: BootstrapPhase terminology alignment

## Related Documentation

- CLAUDE.md: Lazy instantiation pattern
- docs/architecture/manifests/manifests-architecture.adoc
- Memory: terminology-refactor-state.md
```
