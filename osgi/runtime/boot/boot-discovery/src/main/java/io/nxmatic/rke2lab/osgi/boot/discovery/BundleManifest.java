package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.nxmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    Optional<String> fragmentHost,
    Set<String> requiredServices,
    Set<String> providedServices) {

  /**
   * The osgi.service capability namespace bnd writes for an SCR {@code @Reference} (as a {@code
   * Require-Capability}) and an SCR {@code @Component}'s service (as a {@code Provide-Capability}).
   */
  private static final String SERVICE_NAMESPACE = "osgi.service";

  /**
   * Pull the {@code objectClass=FQCN} out of a require clause's {@code filter:="(objectClass=…)"}.
   */
  private static final Pattern OBJECT_CLASS_FILTER = Pattern.compile("\\(objectClass=([^)]+)\\)");

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
            firstName(main.getValue("Fragment-Host")),
            requiredServices(OsgiHeader.parse(main.getValue("Require-Capability"))),
            providedServices(provideCapability)));
  }

  /**
   * The service FQCNs this bundle MANDATORILY requires — the {@code objectClass} of every {@code
   * Require-Capability: osgi.service} clause, pulled out of its {@code filter:="(objectClass=…)"},
   * skipping {@code resolution:=optional} ones (an SCR {@code @Reference(OPTIONAL)} does not gate
   * activation). This is the runtime dependency the resolver deliberately ignores (bnd marks these
   * {@code effective:=active}), so a service-closure must follow them to install the providers.
   */
  private static Set<String> requiredServices(OsgiHeader requireCapability) {
    final Set<String> services = new LinkedHashSet<>();
    for (Clause clause : requireCapability.clauses()) {
      if (!SERVICE_NAMESPACE.equals(clause.name())
          || "optional".equals(clause.attributes().get("resolution"))) {
        continue;
      }
      final String filter = clause.attributes().get("filter");
      if (filter == null) {
        continue;
      }
      final Matcher matcher = OBJECT_CLASS_FILTER.matcher(filter);
      if (matcher.find()) {
        services.add(matcher.group(1));
      }
    }
    return services;
  }

  /**
   * The service FQCNs this bundle PROVIDES — the {@code objectClass} of every {@code
   * Provide-Capability: osgi.service} clause (what its SCR {@code @Component}s publish). The
   * matching side of {@link #requiredServices}: a service-closure pulls the bundle whose provided
   * set contains a seed's required service.
   */
  private static Set<String> providedServices(OsgiHeader provideCapability) {
    final Set<String> services = new LinkedHashSet<>();
    for (Clause clause : provideCapability.clauses()) {
      if (!SERVICE_NAMESPACE.equals(clause.name())) {
        continue;
      }
      // bnd writes the typed form objectClass:List<String>="…"; Clause strips the ':' from the key,
      // leaving "objectClassList<String>", so match by the objectClass PREFIX, not an exact key.
      for (var attribute : clause.attributes().entrySet()) {
        if (attribute.getKey().startsWith("objectClass")) {
          services.add(attribute.getValue());
        }
      }
    }
    return services;
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
