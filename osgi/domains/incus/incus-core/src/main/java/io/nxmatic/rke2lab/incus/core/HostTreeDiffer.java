package io.nxmatic.rke2lab.incus.core;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The OBSERVE brick of the grow (I6c): compares two host trees into a {@link HostTreeDelta} — data,
 * not a mutation. It pairs regular files by their tree-relative POSIX path (the same key {@code
 * HostTreeChecksummer} uses, so the two agree), classifies each as ADDED / REMOVED / MODIFIED, and
 * for a MODIFIED TEXT file computes the line-level unified diff with {@code java-diff-utils}. It
 * reads bytes but writes nothing — the caller renders the delta to the FS and records its location.
 *
 * <p>MODIFIED is decided by CONTENT (a byte compare), not mtime, so a re-materialised-but-identical
 * file is not a spurious change. A file that is not valid UTF-8 (a binary) is reported MODIFIED by
 * status alone (no intra-file diff) — the {@code host/} tree is chiefly generated YAML/JSON/adoc,
 * so binaries are the exception.
 */
public final class HostTreeDiffer {

  /**
   * Diff {@code fromRoot} → {@code toRoot}. A missing root is treated as an empty tree (so a
   * first-run change against a not-yet-existing pivot is all-ADDED, and a diff against a vanished
   * side is all-REMOVED). Entries are sorted by path for a deterministic, comparable report.
   */
  public HostTreeDelta diff(Path fromRoot, Path toRoot) {
    final Map<String, Path> from = index(fromRoot);
    final Map<String, Path> to = index(toRoot);

    final Set<String> paths = new TreeSet<>();
    paths.addAll(from.keySet());
    paths.addAll(to.keySet());

    final List<HostTreeDelta.Entry> entries = new ArrayList<>();
    for (String path : paths) {
      final Path a = from.get(path);
      final Path b = to.get(path);
      if (a == null) {
        entries.add(HostTreeDelta.Entry.added(path));
      } else if (b == null) {
        entries.add(HostTreeDelta.Entry.removed(path));
      } else if (!sameContent(a, b)) {
        entries.add(HostTreeDelta.Entry.modified(path, unifiedDiff(path, a, b)));
      }
    }
    return new HostTreeDelta(String.valueOf(fromRoot), String.valueOf(toRoot), entries);
  }

  /** Map of tree-relative POSIX path → absolute file path, for every regular file under root. */
  private static Map<String, Path> index(Path root) {
    final Map<String, Path> files = new HashMap<>();
    if (!Files.isDirectory(root)) {
      return files;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile)
          .forEach(p -> files.put(relativePosix(root, p), p.toAbsolutePath().normalize()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot walk host tree " + root, e);
    }
    return files;
  }

  private static String relativePosix(Path root, Path file) {
    return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
  }

  private static boolean sameContent(Path a, Path b) {
    try {
      return Files.mismatch(a, b) == -1L;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot compare " + a + " and " + b, e);
    }
  }

  /**
   * The unified-diff lines for a MODIFIED text file, or an empty list when either side is not valid
   * UTF-8 (a binary — reported MODIFIED by status alone).
   */
  private static List<String> unifiedDiff(String path, Path a, Path b) {
    final Optional<List<String>> original = utf8Lines(a);
    final Optional<List<String>> revised = utf8Lines(b);
    if (original.isEmpty() || revised.isEmpty()) {
      return List.of();
    }
    final Patch<String> patch = DiffUtils.diff(original.get(), revised.get());
    return UnifiedDiffUtils.generateUnifiedDiff(path, path, original.get(), patch, 3);
  }

  /** The file's lines if it is valid UTF-8, else empty (a binary). */
  private static Optional<List<String>> utf8Lines(Path file) {
    final byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
    try {
      final String text =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      return Optional.of(text.isEmpty() ? List.of() : List.of(text.split("\n", -1)));
    } catch (CharacterCodingException notText) {
      return Optional.empty();
    }
  }
}
