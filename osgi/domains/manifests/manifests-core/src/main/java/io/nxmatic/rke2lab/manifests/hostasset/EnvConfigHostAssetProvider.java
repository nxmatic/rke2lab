package io.nxmatic.rke2lab.manifests.hostasset;

import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetContribution;
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
 * Contributes the synthesised env-config sections as ONE shell file the boot sources. Reads its own
 * slice ({@code runtime/env-config}) — whose sections are HIDDEN {@code
 * .configmap-env-section-*.yml} dotfiles (skipped by the RKE2 auto-deploy, and missed by the
 * loader's visible-{@code *.yml} glob) — and yields them raw. Incus's {@link
 * io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetDeliveryKind#SHELL_ENV_FILE} strategy
 * extracts each ConfigMap's data and emits the single {@link #ENV_FILE}, closing the read gap: the
 * loader sources ONE visible file instead of globbing the dotfiles.
 */
@Component(service = HostAssetProvider.class)
public final class EnvConfigHostAssetProvider implements HostAssetProvider {

  private static final String ENV_CONFIG_SLICE = "runtime/env-config";

  /** The single env file the boot loader ({@code rke2lab-env-load.sh}) sources. */
  static final String ENV_FILE = "rke2lab-environment.sh";

  /**
   * The env sections are exactly the {@code .configmap-env-section-*.yml} dotfiles. Whitelisting
   * the prefix excludes the sibling {@code .configmap-*.group.yml} inventory marker (its {@code
   * data.members} would otherwise serialise into the env file as a bogus {@code members} variable).
   */
  private static final String SECTION_PREFIX = ".configmap-env-section-";

  @Override
  public List<HostAssetContribution> contribute(Path synthesizedRoot) throws IOException {
    final Path slice = synthesizedRoot.resolve(ENV_CONFIG_SLICE);
    if (!Files.isDirectory(slice)) {
      return List.of();
    }
    final List<HostAssetEntry> entries = new ArrayList<>();
    try (Stream<Path> files = Files.list(slice)) {
      files
          .filter(Files::isRegularFile)
          .filter(EnvConfigHostAssetProvider::isEnvSection)
          .sorted()
          .forEach(file -> entries.add(readEntry(file)));
    }
    if (entries.isEmpty()) {
      return List.of();
    }
    return List.of(HostAssetContribution.shellEnvFile(HostAssetSlot.ENV_CONFIG, entries, ENV_FILE));
  }

  private static boolean isEnvSection(Path path) {
    final String name = path.getFileName().toString();
    return name.startsWith(SECTION_PREFIX) && (name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  private static HostAssetEntry readEntry(Path file) {
    try {
      return HostAssetEntry.file(file.getFileName().toString(), Files.readString(file));
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read env-config slice file: " + file, ex);
    }
  }
}
