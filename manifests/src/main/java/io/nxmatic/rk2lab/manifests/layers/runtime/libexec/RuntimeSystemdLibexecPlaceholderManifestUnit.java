// @codebase
package io.nxmatic.rk2lab.manifests.layers.runtime.libexec;

import io.nxmatic.rk2lab.manifests.api.HostAssetDeliveryPolicy;
import io.nxmatic.rk2lab.manifests.api.ManifestDomainCatalog;
import io.nxmatic.rk2lab.manifests.layers.common.AbstractManifestUnit;
import io.nxmatic.rk2lab.manifests.layers.common.ManifestUnitContext;
import java.util.List;

/**
 * Disabled-by-default placeholder preserving the runtime/systemd-libexec host-asset use case.
 *
 * <p>This unit intentionally emits no resources unless explicitly toggled on, but keeps the
 * canonical host asset policy path in code (`/srv/host/systemd-libexec.d`) for future runtime
 * contribution wiring from controlnode-managed systemd executable assets.
 */
public final class RuntimeSystemdLibexecPlaceholderManifestUnit extends AbstractManifestUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.RUNTIME + "/systemd-libexec-placeholder";

  static final String ENABLE_PROPERTY =
      "rk2lab.manifests.runtime.systemdLibexecPlaceholder.enabled";

  private static final HostAssetDeliveryPolicy PLACEHOLDER_POLICY =
      HostAssetDeliveryPolicy.systemdLibexecPlaceholder();

  public RuntimeSystemdLibexecPlaceholderManifestUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  public void apply(final ManifestUnitContext context) {
    if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
      return;
    }

    if (!PLACEHOLDER_POLICY.enabled()) {
      return;
    }
  }
}
