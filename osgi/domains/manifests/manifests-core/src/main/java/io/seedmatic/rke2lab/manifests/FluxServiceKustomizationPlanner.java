package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotations;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxRootManifestsUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the per-service Flux {@code Kustomization} objects the root Kustomization ({@link
 * FluxRootManifestsUnit}) reconciles from {@code ./flux}. Runs AFTER the exploder, so it knows the
 * exact set of non-empty {@code (layer, domain, package)} cells (from the written tree) and
 * cross-references the in-memory domain graph ({@link CoherentManifestsDomainRegistry}) to emit one
 * child Kustomization per cell whose {@code dependsOn} both:
 *
 * <ul>
 *   <li>keeps the reconcile-LAYER barrier — a cell depends on every cell of the
 *       immediately-previous non-empty layer (crds → foundation → operators → workloads), so a CR's
 *       CRD (rendered in {@code crds}, or registered by a {@code foundation}/{@code operators}
 *       provider) is applied first, transitively, without enumerating each CR→CRD edge; and
 *   <li>adds the SERVICE→service edges from each unit's {@code dependsOnManifestsUnitIds} — but
 *       only INTRA-layer (the dep has a cell in the SAME layer, e.g. headplane→headscale in {@code
 *       workloads}). A cross-layer unit dep is either already covered by the barrier (dep in an
 *       earlier layer) or would invert it (dep in a later layer — trust the layer assignment, as
 *       the pre-split model did); adding it would risk a dependsOn cycle. Barrier edges only ever
 *       point to a lower layer and intra-layer edges stay within one layer (the unit graph is a
 *       DAG), so the combined graph is acyclic by construction.
 * </ul>
 *
 * <p>Result: {@code flux get kustomizations} shows per-service progress; a failure stays local (one
 * service's Kustomization goes NotReady, not the whole layer); retries are independent.
 *
 * <p>Instance-passing: the synthesis service hands it the resolved registry + the YAML writer; its
 * one act is {@link #plan}. Child names are the bare {@code <domain>-<package>} (the branch +
 * flux-system namespace are already per-cluster), with a {@code -<layer>} suffix only for a service
 * whose resources span more than one layer (e.g. {@code runtime-flox-controller-crds} / {@code
 * -operators}).
 */
final class FluxServiceKustomizationPlanner {

  private static final Logger LOG = LoggerFactory.getLogger(FluxServiceKustomizationPlanner.class);

  /** Fixed reconcile-layer order; the barrier points a cell at the previous non-empty layer. */
  private static final List<String> LAYER_ORDER =
      List.of(
          ManifestAnnotations.LAYER_CRDS,
          ManifestAnnotations.LAYER_FOUNDATION,
          ManifestAnnotations.LAYER_OPERATORS,
          ManifestAnnotations.LAYER_WORKLOADS);

  private final CoherentManifestsDomainRegistry registry;
  private final YamlMapper yaml;

  FluxServiceKustomizationPlanner(
      final CoherentManifestsDomainRegistry registry, final YamlMapper yaml) {
    this.registry = registry;
    this.yaml = yaml;
  }

  /** A rendered service cell — the tree dir {@code <layer>/<domain>/<package>}. */
  private record Cell(String layer, String domain, String pkg) {
    String coord() {
      return domain + "/" + pkg;
    }

    String path() {
      return "./" + layer + "/" + domain + "/" + pkg;
    }
  }

  public void plan(final ManifestExplodeResult result) {
    final Path target = result.explodedTargetDir();

    // 1. The non-empty cells, from the written tree paths (<layer>/<domain>/<package>/<file>).
    final Set<Cell> cells = new LinkedHashSet<>();
    for (final Path file : result.writtenFiles()) {
      final Path rel = target.relativize(file);
      if (rel.getNameCount() < 4) {
        continue; // not a <layer>/<domain>/<package>/<file> leaf (e.g. the root .gitattributes)
      }
      cells.add(
          new Cell(
              rel.getName(0).toString(), rel.getName(1).toString(), rel.getName(2).toString()));
    }
    if (cells.isEmpty()) {
      return;
    }

    // 2. coord (<domain>/<package>) -> its unit's dependency ids, and unitId -> coord, from the
    //    in-memory graph. A unit is keyed <domainId>/<outputDir>, and outputDir() is exactly the
    //    package dir segment the exploder wrote the cell into.
    final Map<String, List<String>> depsByCoord = new HashMap<>();
    final Map<String, String> coordByUnitId = new HashMap<>();
    for (final ManifestsUnit unit : registry.visitOrder()) {
      final String unitId = unit.manifestUnitId();
      final String coord =
          registry.requireDomainIdForManifestsUnit(unitId) + "/" + unit.outputDir();
      depsByCoord.put(coord, unit.dependsOnManifestsUnitIds());
      coordByUnitId.put(unitId, coord);
    }

    // 3. Index cells: coord -> its layers (for multi-layer naming), and layer -> its cells.
    final Map<String, Set<String>> layersByCoord = new TreeMap<>();
    final Map<String, List<Cell>> cellsByLayer = new TreeMap<>();
    for (final Cell cell : cells) {
      layersByCoord.computeIfAbsent(cell.coord(), k -> new TreeSet<>()).add(cell.layer());
      cellsByLayer.computeIfAbsent(cell.layer(), k -> new ArrayList<>()).add(cell);
    }
    final List<String> presentLayers =
        LAYER_ORDER.stream().filter(cellsByLayer::containsKey).toList();

    // 4. Emit one child Kustomization per cell into ./flux.
    final Path fluxDir = target.resolve(FluxRootManifestsUnit.FLUX_DIR);
    try {
      Files.createDirectories(fluxDir);
      for (final Cell cell : cells) {
        final String name = cellName(cell, layersByCoord);
        final Set<String> dependsOn = new TreeSet<>();

        // (a) layer barrier: every cell of the immediately-previous non-empty layer.
        final int layerIdx = presentLayers.indexOf(cell.layer());
        if (layerIdx > 0) {
          for (final Cell prev :
              Objects.requireNonNull(cellsByLayer.get(presentLayers.get(layerIdx - 1)))) {
            dependsOn.add(cellName(prev, layersByCoord));
          }
        }
        // (b) INTRA-layer service->service edges from the unit graph (see class doc for why
        //     same-layer only). A dep with no cell (node-bootstrap unit, not in the tree) is
        // skipped.
        for (final String depUnitId : depsByCoord.getOrDefault(cell.coord(), List.of())) {
          final String depCoord = coordByUnitId.get(depUnitId);
          if (depCoord == null) {
            continue;
          }
          final Set<String> depLayers = layersByCoord.get(depCoord);
          if (depLayers != null && depLayers.contains(cell.layer())) {
            final int slash = depCoord.indexOf('/');
            dependsOn.add(
                cellName(
                    new Cell(
                        cell.layer(), depCoord.substring(0, slash), depCoord.substring(slash + 1)),
                    layersByCoord));
          }
        }
        dependsOn.remove(name); // never depend on self

        Files.writeString(
            fluxDir.resolve(name + ".yml"), yaml.dump(kustomization(cell, name, dependsOn)));
      }
      LOG.info("Planned {} per-service Flux Kustomizations under {}", cells.size(), fluxDir);
    } catch (final IOException ex) {
      throw new UncheckedIOException("cannot write per-service Flux Kustomizations", ex);
    }
  }

  /** {@code <domain>-<package>}, plus a {@code -<layer>} suffix when the service spans layers. */
  private String cellName(final Cell cell, final Map<String, Set<String>> layersByCoord) {
    final String base = cell.domain() + "-" + cell.pkg();
    return Objects.requireNonNull(layersByCoord.get(cell.coord())).size() > 1
        ? base + "-" + cell.layer()
        : base;
  }

  private Map<String, Object> kustomization(
      final Cell cell, final String name, final Set<String> dependsOn) {
    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("interval", "5m");
    spec.put("path", cell.path());
    spec.put("prune", true);
    spec.put("wait", true);
    spec.put(
        "sourceRef",
        Map.of("kind", "GitRepository", "name", FluxRootManifestsUnit.GIT_REPOSITORY_NAME));
    // Each child carries its own sops decryption — the root applies only these Kustomization
    // objects, but a child's own <layer>/<domain>/<package> dir may hold a sops-filtered Secret.
    spec.put(
        "decryption",
        Map.of(
            "provider",
            "sops",
            "secretRef",
            Map.of("name", FluxRootManifestsUnit.SOPS_AGE_SECRET)));
    if (!dependsOn.isEmpty()) {
      spec.put("dependsOn", dependsOn.stream().map(n -> Map.of("name", n)).toList());
    }
    final Map<String, Object> ks = new LinkedHashMap<>();
    ks.put("apiVersion", "kustomize.toolkit.fluxcd.io/v1");
    ks.put("kind", "Kustomization");
    ks.put("metadata", Map.of("name", name, "namespace", "flux-system"));
    ks.put("spec", spec);
    return ks;
  }
}
