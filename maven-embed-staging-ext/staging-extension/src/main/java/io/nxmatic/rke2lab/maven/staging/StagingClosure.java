package io.nxmatic.rke2lab.maven.staging;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.bnd.Clause;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of dependency jars an exec-module must STAGE as OSGi bundles (copied intact under {@code
 * META-INF/bundles/} and excluded from the flat uber-jar), derived as a CLOSURE over what the
 * bundles declare — replacing the two hand-maintained pom lists. Mirrors the runtime install logic
 * ({@code BootPlanner.plan}) at build time, so the jars on disk match the bundles the runtime will
 * install.
 *
 * <p>The seed is the same two sources the runtime installs from:
 *
 * <ul>
 *   <li>OUR bundles — the {@link io.nxmatic.rke2lab.osgi.bnd.EmbedCapability#isDomain() model/edge}
 *       jars, recognised by the embed capability they self-declare.
 *   <li>The {@link BootStackJar boot-stack} — felix.scr/resolver + pax, recognised by symbolic
 *       name. The seed BY NATURE: nothing of ours imports felix.scr (DS wires by reflection), so no
 *       Import-Package closure reaches it; it must be named.
 * </ul>
 *
 * <p>From that seed we CLOSE over {@code Import-Package}, BOUNDED by what the host already provides
 * flat — the mirror of {@code BootPlanner.deriveSystemExports}. A package a staged bundle imports
 * pulls in its exporter(s) only when ALL of:
 *
 * <ul>
 *   <li>the import is MANDATORY — an {@code resolution:=optional} import does not force resolution
 *       in OSGi, so it does not force staging (this is what keeps felix.scr's optional {@code
 *       org.osgi.service.cm} from dragging in the whole compendium);
 *   <li>the package is NOT already in {@code system.packages.extra} — i.e. not imported by any of
 *       our model/edge bundles, since the runtime mirrors exactly those imports as host-flat
 *       exports. This is why fanning out from a model/edge stages nothing (their imports DEFINE the
 *       covered set) — only the BOOT-STACK's uncovered imports pull anything in.
 * </ul>
 *
 * <p>When several resolved bundles export a wired package we stage them ALL: multiple installed
 * exporters of a package is LEGAL in OSGi — the framework resolver selects the wire at resolution
 * time, by version and {@code uses:} constraints. We do not second-guess it, and we do not police
 * what the developer keeps on the classpath: a package that is both staged and left flat (e.g.
 * {@code org.slf4j}, deliberately host-flat AND provided to bundles by pax) is a legitimate shared
 * provider in one case and a leaked aggregate ({@code osgi.cmpn}) in another — the difference is
 * the developer's intent, which a derivation cannot read. Keeping the classpath clean (excluding
 * aggregates like {@code osgi.cmpn} in the BOM, backed by the build-parent {@code
 * bannedDependencies}) is the developer's job; this class only derives what to stage.
 */
public record StagingClosure(
    List<ResolvedBundle> staged, List<String> trace, Set<String> realmLibraryGas) {

  /** Compute the staging closure over an exec-module's resolved dependency jars. */
  public static StagingClosure compute(List<ResolvedBundle> resolved) {
    return new Computation(resolved).run();
  }

  /** The {@code groupId:artifactId} keys of the staged jars, in discovery order. */
  public Set<String> stagedGas() {
    return staged.stream()
        .map(ResolvedBundle::ga)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * The staged jars to EXCLUDE from the flat uber-jar — staged minus the realm libraries (a realm
   * library is staged as a bundle AND kept flat in the host).
   */
  public Set<String> shadeExcludeGas() {
    final Set<String> excludes = new LinkedHashSet<>(stagedGas());
    excludes.removeAll(realmLibraryGas);
    return excludes;
  }

  /** Drives the fixpoint; one instance per computation so the state stays local and explicit. */
  private static final class Computation {

    private final List<ResolvedBundle> resolved;
    private final Map<String, List<ResolvedBundle>> exportersByPackage = new LinkedHashMap<>();
    private final Set<String> hostFlatPackages = new LinkedHashSet<>();
    private final Set<String> bootStackSymbolicNames =
        java.util.Arrays.stream(BootStackJar.values())
            .map(BootStackJar::symbolicName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private final Map<String, ResolvedBundle> stagedByGa = new LinkedHashMap<>();
    private final Map<String, ResolvedBundle> realmLibraryByGa = new LinkedHashMap<>();
    private final Set<String> stagedExportedPackages = new LinkedHashSet<>();
    private final Deque<ResolvedBundle> frontier = new ArrayDeque<>();
    private final List<String> trace = new ArrayList<>();

    Computation(List<ResolvedBundle> resolved) {
      this.resolved = resolved;
      indexExporters();
      indexHostFlatPackages();
    }

    StagingClosure run() {
      seed();
      seedRealmLibraries();
      close();
      return new StagingClosure(
          new ArrayList<>(stagedByGa.values()), trace, Set.copyOf(realmLibraryByGa.keySet()));
    }

    /** Map every exported package to ALL the resolved bundles that export it. */
    private void indexExporters() {
      for (ResolvedBundle bundle : resolved) {
        if (!bundle.isBundle()) {
          continue;
        }
        for (String pkg : bundle.exports().names()) {
          exportersByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(bundle);
        }
      }
    }

    /**
     * The packages the host already serves flat — the build-time mirror of {@code
     * system.packages.extra}: every package any model/edge bundle imports. An import whose package
     * is here resolves against the host classloader, so its exporter stays flat, never staged.
     */
    private void indexHostFlatPackages() {
      for (ResolvedBundle bundle : resolved) {
        if (bundle.embed() != null && bundle.embed().isDomain()) {
          hostFlatPackages.addAll(bundle.imports().names());
        }
      }
    }

    /** Seed: our model/edge bundles (by capability) + the boot-stack (by symbolic name). */
    private void seed() {
      for (ResolvedBundle bundle : resolved) {
        if (bundle.embed() != null && bundle.embed().isDomain()) {
          stage(bundle, "seed: embed type=" + bundle.embed().type());
        } else if (bundle.symbolicName() != null
            && bootStackSymbolicNames.contains(bundle.symbolicName())) {
          stage(bundle, "seed: boot-stack");
        }
      }
    }

    /**
     * Third-party OSGi bundles exporting a package a domain bundle imports — staged AND kept flat.
     */
    private void seedRealmLibraries() {
      final Set<String> domainImports = new LinkedHashSet<>();
      for (ResolvedBundle b : resolved) {
        if (b.embed() != null && b.embed().isDomain()) {
          domainImports.addAll(b.imports().names());
        }
      }
      for (ResolvedBundle b : resolved) {
        if (isRealmLibrary(b, domainImports)) {
          realmLibraryByGa.put(b.ga(), b);
          stage(b, "seed: realm library (a domain bundle imports its export)");
        }
      }
    }

    /**
     * A third-party OSGi bundle (not ours, not a seam, not the launcher) exporting a domain import.
     */
    private static boolean isRealmLibrary(ResolvedBundle b, Set<String> domainImports) {
      if (!b.isBundle() || b.launcher()) {
        return false;
      }
      if (b.embed() != null) {
        return false; // ours (model/edge/record/seam) — not a third-party library.
      }
      for (String exported : b.exports().names()) {
        if (!ResolvedBundle.isOurs(exported) && domainImports.contains(exported)) {
          return true;
        }
      }
      return false;
    }

    /** Close over MANDATORY, host-uncovered Import-Package until no new bundle is pulled in. */
    private void close() {
      while (!frontier.isEmpty()) {
        final ResolvedBundle bundle = frontier.removeFirst();
        for (Clause imported : bundle.imports().clauses()) {
          final String pkg = imported.name();
          if (isOptional(imported)
              || hostFlatPackages.contains(pkg)
              || stagedExportedPackages.contains(pkg)) {
            // optional, served host-flat via system.packages.extra, or already provided by an
            // already-staged bundle — nothing new to pull in.
            continue;
          }
          for (ResolvedBundle exporter : exportersByPackage.getOrDefault(pkg, List.of())) {
            if (isStageable(exporter)) {
              stage(exporter, "closure: " + bundle.symbolicName() + " imports " + pkg);
            }
          }
        }
      }
    }

    private static boolean isStageable(ResolvedBundle exporter) {
      if (exporter.launcher()) {
        return false; // the framework itself — system bundle 0, never staged.
      }
      return exporter.embed() == null || !exporter.embed().isSeam(); // a seam is host-flat.
    }

    private static boolean isOptional(Clause imported) {
      return "optional".equals(imported.attributes().get("resolution"));
    }

    private void stage(ResolvedBundle bundle, String reason) {
      if (stagedByGa.putIfAbsent(bundle.ga(), bundle) == null) {
        stagedExportedPackages.addAll(bundle.exports().names());
        frontier.addLast(bundle);
        trace.add(bundle.ga() + "  <-  " + reason);
      }
    }
  }
}
