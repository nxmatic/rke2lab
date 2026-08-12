package io.nxmatic.rke2lab.worktree.internal;

import io.nxmatic.rke2lab.worktree.GitIdentity;
import io.nxmatic.rke2lab.worktree.LinkedWorktree;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The prepared linked worktree {@link JgitRenderedBranch#prepare} returns — a composition, not a
 * subclass: it holds the {@link GitCli} that made it (for the {@link #close() remove}) and a {@link
 * JgitCheckout} of its path (for stage / commit / push). Its verbs are pure delegation; the only
 * knowledge it adds is its own {@code branch}, which {@link #forcePush} names to the checkout. jgit
 * and {@code git worktree} stay sealed in the two collaborators.
 */
final class JgitLinkedWorktree implements LinkedWorktree {

  private final GitCli gitCli;
  private final JgitCheckout checkout;
  private final String branch;

  JgitLinkedWorktree(GitCli gitCli, JgitCheckout checkout, String branch) {
    this.gitCli = gitCli;
    this.checkout = checkout;
    this.branch = branch;
  }

  @Override
  public Path path() {
    return checkout.root();
  }

  @Override
  public String branch() {
    return branch;
  }

  @Override
  public void stage(List<Path> paths) {
    checkout.stage(paths);
  }

  @Override
  public void stageAll() {
    gitCli.addAll(checkout.root());
  }

  @Override
  public String commit(String message, GitIdentity identity, Optional<String> sshSigningKey) {
    return checkout.commit(message, identity, sshSigningKey);
  }

  @Override
  public void push(String token) {
    checkout.push(branch, token);
  }

  @Override
  public void close() {
    gitCli.worktreeRemove(checkout.root());
  }
}
