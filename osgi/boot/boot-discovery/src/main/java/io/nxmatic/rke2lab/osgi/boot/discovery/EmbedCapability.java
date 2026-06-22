package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.osgi.framework.Filter;

/**
 * The {@link BundleManifest#EMBED_CAPABILITY_NAMESPACE embed capability} a bundle self-declares,
 * parsed into its attributes — the single place the embed {@code Provide-Capability} clause is
 * read. A bundle marks both that it is embeddable AND its nature, in one declaration:
 *
 * <pre>{@code
 * Provide-Capability: io.nxmatic.rke2lab.embed; type=model;   model=manifests
 * Provide-Capability: io.nxmatic.rke2lab.embed; type=edge;    edge=ssh-to-age
 * Provide-Capability: io.nxmatic.rke2lab.embed; type=fixture; suite=scr; role=provider
 * }</pre>
 *
 * <p>The attributes are no longer decorative: a consumer SELECTS bundles by an LDAP {@link Filter}
 * over them ({@code (&(type=fixture)(suite=scr)(role=provider))}), so a test installs exactly the
 * fixtures its proof needs by what they DECLARE — never by a file name it keeps in sync. This is
 * the capability counterpart of identifying third-party jars by their {@code Bundle-SymbolicName}:
 * both read the artifact's own manifest, neither guesses from the Maven file name.
 */
public record EmbedCapability(Map<String, String> attributes) {

  /** The {@code type} attribute key — the carrier's boot face (its role at install time). */
  public static final String TYPE = "type";

  /**
   * A domain bundle: pure model logic, installed as a bundle, its exports never system-exported.
   */
  public static final String TYPE_MODEL = "model";

  /** An external edge: an adapter contacting one target, installed as a bundle like a model. */
  public static final String TYPE_EDGE = "edge";

  /**
   * The seam (a {@code -port}): the membrane the flat host shares with the framework. NOT installed
   * as a bundle — system-exported so the host's JCL copy and the bundles' copy are ONE class. The
   * one {@code type} that legitimately appears in {@code system.packages.extra}. See {@code
   * docs/architecture/patterns/port-edge-domain-ownership.adoc} § "The boot face".
   */
  public static final String TYPE_SEAM = "seam";

  /** A test-only fixture (with {@code suite}/{@code role}); installed only by the test harness. */
  public static final String TYPE_FIXTURE = "fixture";

  /**
   * The bundles a runtime INSTALLS into the framework: domain {@code model} + {@code edge}, both
   * loading on the bundle side. Excludes {@code seam} (system-exported, not installed) and {@code
   * fixture} (test-only). The single source for the prod discovery filter — replaces the former
   * {@code (type=*)}, which now also matches the seam.
   */
  public static final String INSTALL_FILTER = "(|(type=model)(type=edge))";

  /** The carrier's {@code type} ({@link #TYPE_MODEL} / {@link #TYPE_EDGE} / …), or {@code null}. */
  public String type() {
    return attributes.get(TYPE);
  }

  /**
   * Whether this carrier loads on the BUNDLE side of the seam — a {@code model} or an {@code edge}.
   * Its exported packages are owned by its own bundle classloader and must NEVER reach {@code
   * system.packages.extra} (a second exporter there would split the class). The discriminator the
   * leak guard turns on.
   */
  public boolean isDomain() {
    return TYPE_MODEL.equals(type()) || TYPE_EDGE.equals(type());
  }

  /** Whether this carrier is the seam — system-exported for the flat host, never installed. */
  public boolean isSeam() {
    return TYPE_SEAM.equals(type());
  }

  /**
   * Parse the embed clause out of a {@code Provide-Capability} header, or {@code null} if the
   * header does not declare {@link BundleManifest#EMBED_CAPABILITY_NAMESPACE}. Reads only the embed
   * clause; other capability namespaces in the same header are ignored.
   */
  public static EmbedCapability parse(String provideCapabilityHeader) {
    if (provideCapabilityHeader == null) {
      return null;
    }
    for (String clause : BundleManifest.splitClauses(provideCapabilityHeader)) {
      final String[] parts = clause.split(";");
      if (parts.length == 0 || !parts[0].trim().equals(BundleManifest.EMBED_CAPABILITY_NAMESPACE)) {
        continue;
      }
      final Map<String, String> attrs = new LinkedHashMap<>();
      for (int i = 1; i < parts.length; i++) {
        final String p = parts[i].trim();
        final int eq = p.indexOf('=');
        if (eq > 0) {
          // Strip a directive ':' (key:=value) and any quotes — only plain attributes select.
          final String key = p.substring(0, eq).replace(":", "").trim();
          final String value = p.substring(eq + 1).replace("\"", "").trim();
          attrs.put(key, value);
        }
      }
      return new EmbedCapability(Map.copyOf(attrs));
    }
    return null;
  }

  /** Whether {@code filter} (an LDAP filter over the embed attributes) selects this capability. */
  public boolean matches(Filter filter) {
    return filter.matches(attributes);
  }
}
