# claude-hub — shared git subtree

The hub owns this tree (`claude-hub` repo `.claude/`); `rke2lab`'s `.claude/hub/`
is a synchronized consumer copy.

## Sync down (claude-hub -> rke2lab)
git -C <hub> subtree split --prefix=.claude --branch=split/claude-hub/dot-claude --rejoin HEAD
git -C <hub> push origin split/claude-hub/dot-claude
git fetch claude-hub split/claude-hub/dot-claude
git subtree pull --prefix=.claude/hub claude-hub split/claude-hub/dot-claude --squash

## Sync up (rke2lab -> claude-hub)
git subtree split --prefix=.claude/hub --branch=split/rke2lab/dot-claude --rejoin HEAD
git push claude-hub split/rke2lab/dot-claude
git -C <hub> subtree pull --prefix=.claude origin split/rke2lab/dot-claude --squash

Keep `--squash` consistent on both sides.
