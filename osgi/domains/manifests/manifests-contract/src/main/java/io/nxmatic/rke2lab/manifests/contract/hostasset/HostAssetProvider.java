package io.nxmatic.rke2lab.manifests.contract.hostasset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Contract for a manifests concern to contribute host assets to incus's bootstrap materialization.
 * Implementations are SCR {@code @Component}s discovered through the OSGi registry and collected by
 * incus's {@code BootstrapHostAssetMaterializer} (a {@code @Reference(MULTIPLE)}).
 *
 * <p>This is the FIRST explicit, typed contribution across the incus↔manifests seam: it makes the
 * former implicit "manifests writes files by convention, incus reads them back blind" enumerable,
 * so a missing asset is a missing provider — visible, not a silent gap.
 *
 * <p>Each provider READS its own slice of the tree the manifests synthesis (the broker sow) already
 * wrote under {@code synthesizedRoot} — manifests co-locates the write and the read of that
 * convention. It returns the slice's entries as raw content plus the {@link HostAssetDeliveryKind}
 * incus applies; incus owns the slot→root mapping, the transform strategy, and the write.
 */
public interface HostAssetProvider {

  /**
   * @param synthesizedRoot the staging root the manifests synthesis wrote its tree into
   * @return the contributions this concern offers (empty if it has nothing to place this run)
   * @throws IOException if reading the synthesized slice fails
   */
  List<HostAssetContribution> contribute(Path synthesizedRoot) throws IOException;
}
