package io.nxmatic.rke2lab.worktree.internal;

import io.nxmatic.rke2lab.worktree.Worktree;
import io.nxmatic.rke2lab.worktree.host.Provenance;
import io.nxmatic.rke2lab.worktree.host.WorkingState;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * The jgit-backed {@link Worktree} — the SOLE holder of jgit in this domain. The seed process runs
 * inside its worktree, so the component locates its root once at activation (walking up from the
 * process directory to the {@code .git}) and reads the git facts on demand (provenance and working
 * state change as the tree is edited, so they are never cached). jgit stays sealed here; the
 * exported {@link Worktree} interface carries only JDK types and the contract's records.
 */
@Component(service = Worktree.class)
public final class JgitWorktree implements Worktree {

  private final Path root;

  @Activate
  public JgitWorktree() {
    this.root = locateFrom(Path.of("").toAbsolutePath());
  }

  @Override
  public Path root() {
    return root;
  }

  @Override
  public Provenance provenance() {
    try (Repository repository = open()) {
      final ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        return new Provenance("", false);
      }
      return new Provenance(head.name(), !status(repository).isClean());
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read the provenance of " + root, ex);
    }
  }

  @Override
  public WorkingState workingState() {
    try (Repository repository = open()) {
      final Status status = status(repository);
      if (status.isClean()) {
        return new WorkingState(true, List.of());
      }
      return new WorkingState(false, uncommittedPaths(status));
    }
  }

  /** Walk up from {@code startDir} to the enclosing {@code .git} and report its working tree. */
  private static Path locateFrom(Path startDir) {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(startDir.toFile());
    if (builder.getGitDir() == null) {
      throw new IllegalStateException("no git worktree encloses " + startDir);
    }
    try (Repository repository = builder.build()) {
      final File workTree = repository.getWorkTree();
      if (workTree == null) {
        throw new IllegalStateException("no worktree for the git dir found from " + startDir);
      }
      return workTree.toPath().toAbsolutePath().normalize();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot locate the worktree enclosing " + startDir, ex);
    }
  }

  private Repository open() {
    final FileRepositoryBuilder builder = new FileRepositoryBuilder();
    builder.setWorkTree(root.toFile());
    builder.findGitDir(root.toFile());
    try {
      return builder.build();
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot open the git repository at " + root, ex);
    }
  }

  private static Status status(Repository repository) {
    try (Git git = new Git(repository)) {
      return git.status().call();
    } catch (GitAPIException ex) {
      throw new IllegalStateException("cannot read the git status", ex);
    }
  }

  private static List<String> uncommittedPaths(Status status) {
    final LinkedHashSet<String> paths = new LinkedHashSet<>();
    paths.addAll(status.getAdded());
    paths.addAll(status.getChanged());
    paths.addAll(status.getModified());
    paths.addAll(status.getRemoved());
    paths.addAll(status.getMissing());
    paths.addAll(status.getUntracked());
    paths.addAll(status.getConflicting());
    return new ArrayList<>(paths);
  }
}
