package io.nxmatic.rke2lab.incus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins I6c-OBSERVE: the differ classifies files ADDED / REMOVED / MODIFIED (by content, not mtime),
 * emits a unified diff for a modified text file, treats a missing root as an empty tree, and the
 * renderer lands both json and adoc.
 */
class HostTreeDifferTest {

  private final HostTreeDiffer differ = new HostTreeDiffer();

  private static Path tree(Path root, Map<String, String> files) throws IOException {
    for (var e : files.entrySet()) {
      final Path f = root.resolve(e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue());
    }
    return root;
  }

  private static Optional<HostTreeDelta.Entry> entryFor(HostTreeDelta delta, String path) {
    return delta.entries().stream().filter(e -> e.path().equals(path)).findFirst();
  }

  @Test
  void a_missing_from_root_is_all_added(@TempDir Path dir) throws IOException {
    final Path to = tree(dir.resolve("to"), Map.of("a.yaml", "x", "sub/b.yaml", "y"));
    final HostTreeDelta delta = differ.diff(dir.resolve("absent"), to);
    assertEquals(2, delta.entries().size());
    assertTrue(delta.entries().stream().allMatch(e -> e.status() == HostTreeDelta.Status.ADDED));
  }

  @Test
  void identical_trees_yield_an_empty_delta(@TempDir Path dir) throws IOException {
    final Map<String, String> same = Map.of("a.yaml", "hello\nworld\n");
    final HostTreeDelta delta =
        differ.diff(tree(dir.resolve("a"), same), tree(dir.resolve("b"), same));
    assertTrue(delta.isEmpty(), "same content → no delta (content compare, not mtime)");
  }

  @Test
  void added_removed_and_modified_are_classified(@TempDir Path dir) throws IOException {
    final Path from =
        tree(
            dir.resolve("from"),
            Map.of("keep.yaml", "same", "gone.yaml", "x", "edit.yaml", "one\ntwo\n"));
    final Path to =
        tree(
            dir.resolve("to"),
            Map.of("keep.yaml", "same", "edit.yaml", "one\nTWO\n", "new.yaml", "z"));

    final HostTreeDelta delta = differ.diff(from, to);

    assertTrue(entryFor(delta, "keep.yaml").isEmpty(), "an unchanged file is not in the delta");
    assertEquals(HostTreeDelta.Status.REMOVED, entryFor(delta, "gone.yaml").orElseThrow().status());
    assertEquals(HostTreeDelta.Status.ADDED, entryFor(delta, "new.yaml").orElseThrow().status());
    final HostTreeDelta.Entry edit = entryFor(delta, "edit.yaml").orElseThrow();
    assertEquals(HostTreeDelta.Status.MODIFIED, edit.status());
    assertFalse(edit.unifiedDiff().isEmpty(), "a modified text file carries a unified diff");
    assertTrue(
        edit.unifiedDiff().stream().anyMatch(l -> l.startsWith("+") && l.contains("TWO")),
        "the unified diff shows the added line");
  }

  @Test
  void a_modified_binary_is_reported_by_status_only(@TempDir Path dir) throws IOException {
    final Path from = dir.resolve("from");
    final Path to = dir.resolve("to");
    Files.createDirectories(from);
    Files.createDirectories(to);
    // Invalid UTF-8 (a lone 0xFF byte) on both sides, differing → MODIFIED, no intra-file diff.
    Files.write(from.resolve("blob.bin"), new byte[] {(byte) 0xFF, 0x01});
    Files.write(to.resolve("blob.bin"), new byte[] {(byte) 0xFF, 0x02});

    final HostTreeDelta.Entry entry = entryFor(differ.diff(from, to), "blob.bin").orElseThrow();
    assertEquals(HostTreeDelta.Status.MODIFIED, entry.status());
    assertTrue(entry.unifiedDiff().isEmpty(), "a binary carries no line-level diff");
  }

  @Test
  void the_renderer_lands_sibling_json_and_diff(@TempDir Path dir) throws IOException {
    final Path from = tree(dir.resolve("from"), Map.of("edit.yaml", "one\ntwo\n"));
    final Path to = tree(dir.resolve("to"), Map.of("edit.yaml", "one\nTWO\n"));
    final HostTreeDelta delta = differ.diff(from, to);

    // base = nodeRoot/host.0.drift → siblings host.0.drift.json + host.0.drift.diff
    final Path base = dir.resolve("host.0.drift");
    final Path diff = new HostTreeDeltaRenderer().render(base, delta);

    assertEquals(dir.resolve("host.0.drift.diff"), diff, "render returns the .diff path");
    assertTrue(Files.exists(dir.resolve("host.0.drift.json")), "the runtime json sibling lands");
    assertTrue(Files.exists(dir.resolve("host.0.drift.diff")), "the applicable diff sibling lands");
    assertTrue(Files.readString(diff).contains("TWO"), "the diff carries the modified file's hunk");
  }
}
