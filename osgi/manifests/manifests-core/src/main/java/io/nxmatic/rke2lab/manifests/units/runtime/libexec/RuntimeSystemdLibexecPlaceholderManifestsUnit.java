// @codebase
package io.nxmatic.rke2lab.manifests.units.runtime.libexec;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.HostAssetDeliveryPolicy;
import io.nxmatic.rke2lab.manifests.ManifestsUnitContext;
import io.nxmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import java.util.List;
import software.constructs.Construct;

/**
 * Disabled-by-default placeholder preserving the runtime/systemd-libexec host-asset use case.
 *
 * <p>This unit intentionally emits no resources unless explicitly toggled on, but keeps the
 * canonical host asset policy path in code (`/srv/host/systemd-libexec.d`) for future runtime
 * contribution wiring from controlnode-managed systemd executable assets.
 */
public final class RuntimeSystemdLibexecPlaceholderManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.RUNTIME + "/systemd-libexec-placeholder";

  static final String ENABLE_PROPERTY =
      "rke2lab.manifests.runtime.systemdLibexecPlaceholder.enabled";

  private static final HostAssetDeliveryPolicy PLACEHOLDER_POLICY =
      HostAssetDeliveryPolicy.systemdLibexecPlaceholder();

  public RuntimeSystemdLibexecPlaceholderManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
      return;
    }

    if (!PLACEHOLDER_POLICY.enabled()) {
      return;
    }

    // Conditional unit: if enabled, synthesis logic would go here.
    // Currently just an empty marker (group marker emitted by base regardless).
  }
}
