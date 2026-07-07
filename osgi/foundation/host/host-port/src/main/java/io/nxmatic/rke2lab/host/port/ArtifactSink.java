package io.nxmatic.rke2lab.host.port;

import java.nio.file.Path;

/**
 * The host-access seam for artifact writes: the OSGi world computes a rendered artifact (a runbook,
 * a medical-record dump, an intervention ledger entry) and hands the bytes to this sink; the host
 * persists them. This is the flat-OUT direction of the frontier — the core owns the rendering, the
 * edge owns the filesystem write.
 *
 * <p>The sink is stateless: the host owns WHERE the artifact tree lives and passes {@code
 * outputRoot} on each call, while the caller expresses only the artifact's position within that
 * tree ({@code relativePath}). Neither side bakes an absolute host path into the sink.
 */
public interface ArtifactSink {

  /**
   * Write {@code content} to {@code outputRoot}/{@code relativePath}, creating parent directories
   * as needed and replacing any existing file.
   *
   * @throws IllegalStateException if the write fails.
   */
  void write(Path outputRoot, String relativePath, byte[] content);
}
