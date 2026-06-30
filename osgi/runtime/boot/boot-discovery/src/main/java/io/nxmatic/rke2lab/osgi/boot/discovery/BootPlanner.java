package io.nxmatic.rke2lab.osgi.boot.discovery;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.bnd.Clause;
import io.nxmatic.rke2lab.osgi.boot.discovery.BootPlan.Installable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Computes the {@link BootPlan} — the boot DECISION — from a {@link BootRequest} and a {@link
 * BundleIndex}, without touching a framework. Pure host-world logic: it reads each bundle's
 * bnd-computed manifest, pins each to the start level its ROLE maps to, mirrors the model bundles'
 * {@code Import-Package} into {@code system.packages.extra}, guards the seam (a domain package must
 * never be system-exported), and closes over the boot stack's mandatory imports (the passive DS-API
 * trio felix.scr needs). The launcher in {@code osgi/runtime} then just installs what this decided.
 *
 * <p>The one effect this logic depends on — "does the HOST flat classpath carry this package" — is
 * INJECTED as a {@link Predicate} ({@code hostResolves}), so the planner stays pure and a test can
 * feed a deterministic predicate instead of probing a real classloader.
 */
public final class BootPlanner {

  private final BundleIndex discovery;
  private final Predicate<String> hostResolves;

  /**
   * @param discovery the index the embedded topology selects from and the seam guard attributes
   *     packages against (the classpath index for a reactor boot, the staged index for an
   *     exec-jar).
   * @param hostResolves whether the host flat classpath carries a package — injected so this module
   *     needs no classloader of its own; the executor supplies its own classloader's view.
   */
  public BootPlanner(BundleIndex discovery, Predicate<String> hostResolves) {
    this.discovery = discovery;
    this.hostResolves = hostResolves;
  }

  /** Compute the plan for {@code request}. Throws if the request would leak a domain package. */
  public BootPlan plan(BootRequest request) {
    try {
      return computePlan(request);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "failed to read a bundle manifest while planning the boot", ex);
    }
  }

  private BootPlan computePlan(BootRequest request) throws IOException {
    final List<Installable> stack = new ArrayList<>();
    // The domain (model/edge) bundles whose Import-Package feeds deriveSystemExports —
    // builder-given
    // ones plus those discovered in the embedded topology.
    final List<BundleLocation> models = new ArrayList<>(request.modelBundles());
    for (BundleLocation pax : request.paxLoggingBundles()) {
      stack.add(new Installable(pax, BootPlan.START_LEVEL_LOGGING));
    }
    for (BundleLocation runtime : request.runtimeBundles()) {
      stack.add(new Installable(runtime, BootPlan.START_LEVEL_FRAMEWORK_RUNTIME));
    }
    // Classpath topology: the request named its model/edge bundles explicitly.
    for (BundleLocation model : request.modelBundles()) {
      stack.add(new Installable(model, BootPlan.START_LEVEL_BUNDLES));
    }
    // Embedded topology: discover from the index via the policy. Each bundle installs at the start
    // level its role maps to; domain bundles also feed the system-export derivation.
    if (request.embedsBootStack()) {
      for (BundleLocation location : request.discoveryPolicy().select(discovery)) {
        final BundleManifest manifest = discovery.manifestOf(location);
        stack.add(new Installable(location, startLevelOf(manifest)));
        if (manifest.embed() != null && manifest.embed().isDomain()) {
          models.add(location);
        }
      }
    }

    final Set<String> exports = deriveSystemExports(request, models, stack);

    // Close over the stack's MANDATORY imports: a package a stacked bundle imports that no stacked
    // bundle exports and the system bundle does not export either (so it cannot resolve host-flat),
    // but a jar in the index does, pulls that jar in as a passive bundle — the DS-API trio
    // felix.scr
    // imports. The runtime mirror of the build-time StagingClosure, so a classpath boot resolves
    // the
    // same set the embedded boot stages, without naming the trio. Passing the system-export set
    // keeps
    // a host-flat package (jackson, mirrored from a model import) from being pulled as a bundle.
    closeOverImports(
        stack,
        exports.stream()
            .map(e -> Clause.parse(e).name())
            .collect(Collectors.toCollection(LinkedHashSet::new)));

    final boolean paxPresent = !request.paxLoggingBundles().isEmpty() || request.embedsBootStack();
    return new BootPlan(stack, exports, paxPresent);
  }

  /**
   * The start level a discovered bundle installs at, by its ROLE: a boot-stack jar (felix.scr /
   * resolver / pax, matched by symbolic name) takes its {@link BootStackJar.Layer}; a model/edge
   * bundle (embed capability) takes the bundle level — it activates last; anything else is a
   * PASSIVE spec/library jar (the DS-API trio): no activator, must only RESOLVE before the bundle
   * that imports it, so it sits at the lowest level.
   */
  private static int startLevelOf(BundleManifest manifest) {
    final String bsn = manifest.symbolicName();
    for (BootStackJar jar : BootStackJar.values()) {
      if (jar.symbolicName().equals(bsn)) {
        return startLevelFor(jar.layer());
      }
    }
    if (manifest.embed() != null && manifest.embed().isDomain()) {
      return BootPlan.START_LEVEL_BUNDLES;
    }
    return BootPlan
        .START_LEVEL_PASSIVE; // a passive spec/library jar — resolvable before importers.
  }

  private static int startLevelFor(BootStackJar.Layer layer) {
    return switch (layer) {
      case PASSIVE -> BootPlan.START_LEVEL_PASSIVE;
      case LOGGING -> BootPlan.START_LEVEL_LOGGING;
      case FRAMEWORK_RUNTIME -> BootPlan.START_LEVEL_FRAMEWORK_RUNTIME;
    };
  }

  /**
   * The {@code system.packages.extra} set: each model bundle's {@code Import-Package} mirrored as a
   * system export, minus packages an installed bundle exports itself (it is the sole provider
   * inside the framework — re-exporting from the system bundle would split the class). Fails fast
   * on a remaining package the host classloader cannot resolve — a real missing dependency,
   * surfaced here rather than as an opaque NoClassDefFoundError once SCR injects.
   */
  private Set<String> deriveSystemExports(
      BootRequest request, List<BundleLocation> models, List<Installable> stack)
      throws IOException {
    final Set<String> exports = new LinkedHashSet<>(request.explicitSystemPackages());
    final List<BundleManifest> manifests = new ArrayList<>();
    for (BundleLocation model : models) {
      manifests.add(BundleManifest.from(model));
    }
    final Set<String> installedExportedPackages = new LinkedHashSet<>();
    for (Installable installable : stack) {
      installedExportedPackages.addAll(
          discovery.manifestOf(installable.location()).exports().names());
    }
    for (BundleManifest manifest : manifests) {
      exports.addAll(manifest.imports().asSystemExports());
    }
    // A package an installed bundle exports is provided bundle-to-bundle inside the framework —
    // re-exporting it from the system bundle would split the class. This covers domain bundles
    // (their own exports) AND staged realm libraries (jackson): the same rule, one source.
    exports.removeIf(e -> installedExportedPackages.contains(Clause.parse(e).name()));
    // The seam guard. A package owned by a domain bundle (type=model/edge) loads on the BUNDLE side
    // of the seam: its bundle is the sole exporter, never the system bundle. If such a package
    // still
    // sits in `exports` here, its exporter is NOT in the install set, so it would leak into
    // system.packages.extra and split the class against the bundle's own copy. The packages removed
    // just above (exported by an installed bundle) are the wired-bundle-to-bundle case and are
    // gone;
    // what remains and resolves to a domain exporter is the leak. Seam packages (type=seam, the
    // -port membrane) resolve to null here and stay — that is exactly what belongs in the seam.
    final List<String> leaked =
        exports.stream()
            .map(e -> Clause.parse(e).name())
            .flatMap(
                p -> {
                  final String exporter = discovery.domainExporterOf(p);
                  return exporter == null
                      ? Stream.empty()
                      : Stream.of(p + " (owned by domain bundle " + exporter + ")");
                })
            .toList();
    if (!leaked.isEmpty()) {
      throw new IllegalStateException(
          "system.packages.extra would leak domain packages whose owning bundle is not installed — "
              + "install the owning bundle (wire it bundle-to-bundle) instead of system-exporting it: "
              + leaked);
    }
    final List<String> unresolved =
        exports.stream()
            .map(e -> Clause.parse(e).name())
            .filter(p -> !hostResolves.test(p))
            .toList();
    if (!unresolved.isEmpty()) {
      throw new IllegalStateException(
          "system.packages.extra would export packages absent from the host classpath: "
              + unresolved);
    }
    // pax-logging-api provides org.slf4j to bundles; a second provider (the system bundle
    // re-exporting it off the flat classpath) would split the slf4j binder — the R1 scar. Drop
    // org.slf4j so pax is the sole provider inside the framework.
    if (!request.paxLoggingBundles().isEmpty() || request.embedsBootStack()) {
      exports.removeIf(e -> e.equals("org.slf4j") || e.startsWith("org.slf4j;"));
    }
    return exports;
  }

  /**
   * Pull every passive jar the stack's MANDATORY imports need into the stack — the runtime mirror
   * of the build-time {@code StagingClosure}. Delegates the graph walk to the shared {@link
   * BundleIndex#closeOverImports} (the same frame the test executor drives), seeding it with the
   * current stack and the system-bundle exports as already-provided, and CONTRIBUTING the per-jar
   * action: pin each pulled jar to the start level its role maps to. The system-exported set keeps
   * a host-flat package (jackson, mirrored from a model import) from being pulled as a bundle; a
   * package the system bundle does NOT export (the DS-API trio) is genuinely missing and gets wired
   * bundle-to-bundle.
   */
  private void closeOverImports(List<Installable> stack, Set<String> systemExported) {
    final List<BundleLocation> seeds = stack.stream().map(Installable::location).toList();
    discovery.closeOverImports(
        seeds,
        systemExported,
        pulled -> stack.add(new Installable(pulled, startLevelOf(discovery.manifestOf(pulled)))));
  }
}
