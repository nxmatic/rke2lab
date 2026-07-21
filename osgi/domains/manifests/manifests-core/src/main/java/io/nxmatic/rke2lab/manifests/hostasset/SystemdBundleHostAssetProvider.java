package io.nxmatic.rke2lab.manifests.hostasset;

import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetEntry;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetSlot;
import io.nxmatic.rke2lab.manifests.systemd.SystemdBundleConfigMaps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * Contributes the synthesised systemd bundle. Reads its own slice ({@code systemd/}), where the
 * synthesis carried the units and scripts as two ConfigMap dotfiles (decision X — {@code
 * SystemdChart} owns the coupled unit+script bundle), and yields them raw. Incus's {@link
 * HostAssetDeliveryKind#CONFIGMAP_FILES} strategy extracts each ConfigMap {@code data} key back to
 * a file: the units into {@code systemd-units.d}, the scripts into {@code systemd-scripts.d}
 * (executable). Two contributions because the two bundles land in different slot roots.
 */
@Component(service = HostAssetProvider.class)
public final class SystemdBundleHostAssetProvider implements HostAssetProvider {

  private static final String SYSTEMD_SLICE = "systemd";

  @Override
  public List<HostAssetContribution> contribute(Path synthesizedRoot) throws IOException {
    final Path slice = synthesizedRoot.resolve(SYSTEMD_SLICE);
    if (!Files.isDirectory(slice)) {
      return List.of();
    }
    final List<HostAssetContribution> contributions = new ArrayList<>();
    addBundle(
        contributions,
        slice.resolve(SystemdBundleConfigMaps.UNITS_DOTFILE),
        HostAssetSlot.SYSTEMD_UNITS);
    addBundle(
        contributions,
        slice.resolve(SystemdBundleConfigMaps.SCRIPTS_DOTFILE),
        HostAssetSlot.SYSTEMD_SCRIPTS);
    return contributions;
  }

  private static void addBundle(
      List<HostAssetContribution> contributions, Path configMap, HostAssetSlot slot) {
    if (!Files.isRegularFile(configMap)) {
      return;
    }
    final HostAssetEntry entry =
        HostAssetEntry.file(configMap.getFileName().toString(), readString(configMap));
    contributions.add(
        HostAssetContribution.fanOut(slot, HostAssetDeliveryKind.CONFIGMAP_FILES, List.of(entry)));
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read systemd bundle ConfigMap: " + file, ex);
    }
  }
}
