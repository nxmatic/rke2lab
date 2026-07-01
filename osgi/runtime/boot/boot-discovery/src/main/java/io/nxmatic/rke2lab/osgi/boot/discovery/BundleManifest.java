package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.io.IOException;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The headers a bundle DECLARES, read ONCE from its {@link BundleLocation} and held as parsed value
 * objects — an immutable record we query, never a manifest we re-read header by header. The OSGi
 * clause parsing is the shared {@code bnd} jar's ({@link OsgiHeader} / {@link EmbedCapability});
 * this record is the boot model's binding of that parsing to a located bundle, so {@link
 * BundleIndex} indexes instances and an executor reads {@code imports().asSystemExports()} off one.
 */
public record BundleManifest(
    Optional<String> symbolicName,
    Optional<EmbedCapability> embed,
    OsgiHeader exports,
    OsgiHeader imports,
    Optional<String> fragmentHost) {

  /**
   * The embed {@code Provide-Capability} namespace a bundle self-declares — re-exposed from {@link
   * EmbedCapability} so callers on the boot model's surface need not reach into the {@code bnd}
   * jar.
   */
  public static final String EMBED_CAPABILITY_NAMESPACE =
      EmbedCapability.EMBED_CAPABILITY_NAMESPACE;

  /**
   * Read {@code location}'s manifest once and parse the headers the boot model needs. Returns empty
   * when the location is not a bundle we can install: no manifest at all, or a manifest that
   * declares neither a {@code Bundle-SymbolicName} nor an embed capability (a plain library jar).
   */
  public static Optional<BundleManifest> from(BundleLocation location) throws IOException {
    return location.readManifest().map(Manifest::getMainAttributes).flatMap(BundleManifest::parse);
  }

  private static Optional<BundleManifest> parse(Attributes main) {
    final OsgiHeader provideCapability = OsgiHeader.parse(main.getValue("Provide-Capability"));
    final Optional<String> symbolicName = firstName(main.getValue("Bundle-SymbolicName"));
    final Optional<EmbedCapability> embed =
        Optional.ofNullable(EmbedCapability.of(provideCapability));
    if (symbolicName.isEmpty() && embed.isEmpty()) {
      return Optional.empty(); // not a bundle we can install.
    }
    return Optional.of(
        new BundleManifest(
            symbolicName,
            embed,
            OsgiHeader.parse(main.getValue("Export-Package")),
            OsgiHeader.parse(main.getValue("Import-Package")),
            firstName(main.getValue("Fragment-Host"))));
  }

  /**
   * Whether this declares an embed capability — the "is this an embeddable bundle of ours" test.
   */
  public boolean declaresEmbed() {
    return embed.isPresent();
  }

  /** The first clause's bare name of a header (BSN or Fragment-Host, stripped of attributes). */
  private static Optional<String> firstName(String header) {
    return Optional.ofNullable(header).map(h -> h.split(";", 2)[0].trim());
  }
}
