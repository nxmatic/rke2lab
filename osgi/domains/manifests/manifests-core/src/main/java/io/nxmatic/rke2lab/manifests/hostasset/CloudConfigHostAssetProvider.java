package io.nxmatic.rke2lab.manifests.hostasset;

import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetEntry;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetSlot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.osgi.service.component.annotations.Component;

/**
 * Contributes the synthesised cloud-config ConfigMap as the instance's NoCloud seed. Reads its own
 * slice ({@code runtime/cloud-config}, the layout manifests owns under the synthesis root) and
 * yields the envelope YAML raw — incus's {@link HostAssetDeliveryKind#SEED_DIR} strategy strips it
 * and writes {@code user-data}/{@code meta-data}/{@code network-config}, so the instance's seed
 * format stays incus's business.
 */
@Component(service = HostAssetProvider.class)
public final class CloudConfigHostAssetProvider implements HostAssetProvider {

  private static final String CLOUD_CONFIG_SLICE = "runtime/cloud-config";

  @Override
  public List<HostAssetContribution> contribute(Path synthesizedRoot) throws IOException {
    final Path slice = synthesizedRoot.resolve(CLOUD_CONFIG_SLICE);
    if (!Files.isDirectory(slice)) {
      return List.of();
    }
    final List<HostAssetEntry> entries = new ArrayList<>();
    try (Stream<Path> files = Files.list(slice)) {
      files
          .filter(Files::isRegularFile)
          .filter(CloudConfigHostAssetProvider::isYaml)
          .sorted()
          .forEach(file -> entries.add(readEntry(file)));
    }
    if (entries.isEmpty()) {
      return List.of();
    }
    return List.of(
        HostAssetContribution.fanOut(
            HostAssetSlot.CLOUD_SEED, HostAssetDeliveryKind.SEED_DIR, entries));
  }

  private static boolean isYaml(Path path) {
    final String name = path.getFileName().toString();
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private static HostAssetEntry readEntry(Path file) {
    try {
      return HostAssetEntry.file(file.getFileName().toString(), Files.readString(file));
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read cloud-config slice file: " + file, ex);
    }
  }
}
