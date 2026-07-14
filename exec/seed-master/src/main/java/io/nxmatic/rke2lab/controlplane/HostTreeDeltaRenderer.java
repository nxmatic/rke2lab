package io.nxmatic.rke2lab.controlplane;

import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link HostTreeDelta} to the FS in two forms — {@code json} (the runtime serialisation)
 * and {@code adoc} (the operator narration) — the twin of {@code RunbookRenderer}, but for the
 * delta DATA rather than a played scenario. The caller records the returned root in a host entry
 * (the delta lives on the FS, the entry carries only its location — § the two deltas). Best-effort
 * is the caller's concern; this writes or throws.
 */
public final class HostTreeDeltaRenderer {

  private final SeedCodec codec = new SeedCodec();

  /**
   * Write {@code delta} under {@code deltaRoot} as {@code json/delta.json} + {@code
   * adoc/delta.adoc}, and return {@code deltaRoot}. The json is the delta record verbatim
   * (jackson); the adoc is a readable rendering — a status list plus each modified file's unified
   * diff in a source block.
   */
  public Path render(Path deltaRoot, HostTreeDelta delta) {
    try {
      final Path json = deltaRoot.resolve("json");
      final Path adoc = deltaRoot.resolve("adoc");
      Files.createDirectories(json);
      Files.createDirectories(adoc);
      Files.writeString(json.resolve("delta.json"), codec.encode(delta));
      Files.writeString(adoc.resolve("delta.adoc"), asciidoc(delta));
      return deltaRoot;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot render the host-tree delta under " + deltaRoot, e);
    }
  }

  private static String asciidoc(HostTreeDelta delta) {
    final List<String> lines = new ArrayList<>();
    lines.add("= Host tree delta");
    lines.add("");
    lines.add("`" + delta.fromRoot() + "` -> `" + delta.toRoot() + "`");
    lines.add("");
    if (delta.isEmpty()) {
      lines.add("No differences.");
      return String.join("\n", lines) + "\n";
    }
    for (HostTreeDelta.Entry entry : delta.entries()) {
      lines.add("* " + entry.status() + " `" + entry.path() + "`");
    }
    for (HostTreeDelta.Entry entry : delta.entries()) {
      if (entry.unifiedDiff().isEmpty()) {
        continue;
      }
      lines.add("");
      lines.add("== " + entry.path());
      lines.add("[source,diff]");
      lines.add("----");
      lines.addAll(entry.unifiedDiff());
      lines.add("----");
    }
    return String.join("\n", lines) + "\n";
  }
}
