package io.nxmatic.rke2lab.controlplane.incus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure heart of the Incus fan-in: {@code BuildMetadata} is recombined from its two
 * producers at the parent's fan-in — the {@code manifests} half from the PREPARE sub-pipeline, the
 * {@code imageChecksum} half from PROVISION. This join is what let the former mid-run {@code
 * registry.update(BuildMetadata)} disappear when the flat {@code ApplyState} became three
 * sub-pipelines. This is the one piece of {@code toResult} whose semantics changed in that
 * refactor; the rest is opaque pass-through of unchanged Pulumi values.
 */
class IncusBuildMetadataFanInTest {

  @Test
  void recombinesImageChecksumWithManifestsHalf() {
    final BuildMetadata.Manifests manifests =
        BuildMetadata.Manifests.of(Map.of("checksum", "abc123", "fileCount", 7));

    final BuildMetadata recombined =
        IncusResourceBootstrap.recombineBuildMetadata("image-sha-deadbeef", manifests);

    // The image half is present with PROVISION's checksum.
    assertEquals("image-sha-deadbeef", recombined.requireImage().checksum());
    // The manifests half is PREPARE's, carried through untouched.
    assertSame(manifests, recombined.manifests());
  }
}
