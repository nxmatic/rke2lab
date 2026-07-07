package io.nxmatic.rke2lab.host.edge;

import io.nxmatic.rke2lab.host.port.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.osgi.service.component.annotations.Component;

/**
 * The live {@link ArtifactSink}: writes rendered artifacts under the {@code outputRoot} the caller
 * supplies on each call. Stateless — the host owns WHERE the artifact tree lives (it always has:
 * the runbook dir resolves from the seed worktree), so it passes the root in rather than the sink
 * baking one. This is the flat-OUT edge — the core rendered the bytes, the host persists them here.
 */
@Component(service = ArtifactSink.class)
public final class LiveArtifactSink implements ArtifactSink {

  @Override
  public void write(Path outputRoot, String relativePath, byte[] content) {
    final Path target = outputRoot.resolve(relativePath);
    try {
      final Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(
          target,
          content,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write artifact to " + target, ex);
    }
  }
}
