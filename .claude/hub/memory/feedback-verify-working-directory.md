---
name: verify-working-directory
description: Always verify working directory before shell commands to avoid executing in wrong repo
metadata:
  type: feedback
---

Always check the current working directory before executing shell commands, especially when working across multiple repositories.

**Why:** The shell sandbox can be in any directory. Running git commands or file operations in the wrong repo causes confusion, wrong commits, or lost work.

**How to apply:** 
- Before any `git` command: `cd /path/to/correct/repo` or verify with `pwd`
- When switching between repos: explicitly `cd` to the target repo path
- When uncertain: always run `pwd` first to confirm location
