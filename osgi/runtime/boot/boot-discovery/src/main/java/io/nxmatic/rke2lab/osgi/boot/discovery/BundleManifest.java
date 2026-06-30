package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.io.IOException;
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
    String symbolicName,
    EmbedCapability embed,
    OsgiHeader exports,
    OsgiHeader imports,
    String fragmentHost) {

  /**
   * The embed {@code Provide-Capability} namespace a bundle self-declares — re-exposed from {@link
   * EmbedCapability} so callers on the boot model's surface need not reach into the {@code bnd}
   * jar.
   */
  public static final String EMBED_CAPABILITY_NAMESPACE =
      EmbedCapability.EMBED_CAPABILITY_NAMESPACE;

  /**
   * Read {@code location}'s manifest once and parse the headers the boot model needs. A bundle with
   * no manifest yields empty headers and a {@code null} symbolic name — it is simply not a bundle
   * we can install, and {@link BundleIndex} drops it.
   */
  public static BundleManifest from(BundleLocation location) throws IOException {
    final Manifest manifest = location.readManifest();
    if (manifest == null) {
      return new BundleManifest(
          null,
          null,
          new OsgiHeader(java.util.List.of()),
          new OsgiHeader(java.util.List.of()),
          null);
    }
    final Attributes main = manifest.getMainAttributes();
    final OsgiHeader provideCapability = OsgiHeader.parse(main.getValue("Provide-Capability"));
    return new BundleManifest(
        firstName(main.getValue("Bundle-SymbolicName")),
        EmbedCapability.of(provideCapability),
        OsgiHeader.parse(main.getValue("Export-Package")),
        OsgiHeader.parse(main.getValue("Import-Package")),
        firstName(main.getValue("Fragment-Host")));
  }

  /**
   * Whether this declares an embed capability — the "is this an embeddable bundle of ours" test.
   */
  public boolean declaresEmbed() {
    return embed != null;
  }

  /** The first clause's bare name of a header (BSN or Fragment-Host, stripped of attributes). */
  private static String firstName(String header) {
    return header == null ? null : header.split(";", 2)[0].trim();
  }
}
