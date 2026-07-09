package io.nxmatic.rke2lab.maven.staging;

import io.nxmatic.rke2lab.osgi.bnd.BootStackJar;
import io.nxmatic.rke2lab.osgi.bnd.EmbedCapability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionRunner;
import org.apache.maven.plugin.MojosExecutionStrategy;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-sources the OSGi bundle staging by reconfiguring, in place, the shade and
 * maven-dependency-plugin executions of each exec JAR — replacing the two hand-maintained pom lists
 * with one derivation ({@link StagingClosure}) from what the bundles declare.
 *
 * <p>It is a {@link MojosExecutionStrategy} because that is the only hook that can reconfigure a
 * plugin: Maven obtains the strategy by a SINGLE {@code container.lookup} (highest {@link Priority}
 * wins) and the strategy DRIVES the execution list, calling {@code runner.run(execution)} per mojo.
 * Mutating an execution's {@link Xpp3Dom} BEFORE we delegate the run lands BEFORE Maven
 * builds+configures the mojo ({@code getConfiguredMojo} runs inside {@code run}). A
 * MojoExecutionListener cannot do this (it fires AFTER getConfiguredMojo; proven), nor can mutating
 * project.getModel() (its copy is frozen into the plan; proven).
 *
 * <p>The dependency set must be resolved before we can derive — but {@code project.getArtifacts()}
 * is empty until a resolution-requiring mojo (shade) runs, which is too late to reconfigure it. So
 * we resolve EXPLICITLY via {@link ProjectDependenciesResolver} at the head of {@code execute}.
 *
 * <p>This {@code @Priority(20)} wins the lookup over Maven's default AND the build-cache strategy
 * ({@code @Priority(10)}); to preserve whatever was there, it DELEGATES the actual run to the
 * highest-priority OTHER strategy (build-cache if present, else Maven's default) — it decorates, it
 * does not replace.
 */
@Named
@Singleton
@Priority(20)
public class StagingExecutionStrategy implements MojosExecutionStrategy {

  private static final Logger log = LoggerFactory.getLogger(StagingExecutionStrategy.class);

  private final Map<String, MojosExecutionStrategy> strategies;
  private final ProjectDependenciesResolver dependenciesResolver;
  private final RepositorySystem repositorySystem;

  @Inject
  public StagingExecutionStrategy(
      Map<String, MojosExecutionStrategy> strategies,
      ProjectDependenciesResolver dependenciesResolver,
      RepositorySystem repositorySystem) {
    this.strategies = strategies;
    this.dependenciesResolver = dependenciesResolver;
    this.repositorySystem = repositorySystem;
  }

  @Override
  public void execute(
      List<MojoExecution> mojoExecutions,
      MavenSession session,
      MojoExecutionRunner mojoExecutionRunner)
      throws LifecycleExecutionException {
    final boolean staging = mojoExecutions.stream().anyMatch(StagingExecutionStrategy::isShade);
    final List<ResolvedBundle> resolved = staging ? resolveBundles(session) : null;
    if (staging) {
      // Inject the shade/staging config BEFORE delegating: the mojos read it as they are
      // configured.
      reconfigureStaging(session, mojoExecutions, resolved);
    }
    delegate().execute(mojoExecutions, session, mojoExecutionRunner);
    if (staging) {
      // Run the staging-law gate AFTER delegating: compile has now populated target/classes, so the
      // gate can read the exec's REALM_BOUNDARY governance AND self-scan its host classes. Gating
      // before compile saw an empty (cold) tree — no governance anchor, host classes unseen, and
      // the
      // build failed before compile could ever fill it (a self-deadlock). Running here also matches
      // the gate's declared fail-AT-end intent: collect every violation, fail once, at the true
      // end.
      enforceGates(session, resolved, locateDocsDir(session));
    }
  }

  /** Derive the staging closure from the resolved deps and inject both faces of the staging. */
  private void reconfigureStaging(
      MavenSession session, List<MojoExecution> mojoExecutions, List<ResolvedBundle> resolved) {
    final String module = session.getCurrentProject().getArtifactId();
    final StagingClosure closure = StagingClosure.compute(resolved);

    int shadeAdded = 0;
    int stageAdded = 0;
    for (MojoExecution execution : mojoExecutions) {
      if (isShade(execution)) {
        shadeAdded = injectShadeExcludes(execution, closure);
      } else if (isStageBundles(execution)) {
        stageAdded = injectStagingArtifactItems(execution, closure);
      }
    }
    log.info(
        "[osgi-staging] {}: derived {} bundles; +{} shade excludes, +{} staging items",
        module,
        closure.staged().size(),
        shadeAdded,
        stageAdded);
  }

  /**
   * Run every staging law over the whole bundle set in fail-AT-end mode: collect every violation
   * from every bundle, report each at the {@link EnforcementLevel} its bundle declares for that
   * {@link StagingGate} ({@code @GovernedBy}, default ERROR), then fail ONCE with the complete
   * ERROR list. A per-bundle throw would surface only the first offender, forcing a
   * fix-rebuild-repeat loop to discharge the debt one bundle at a time; accumulating shows the
   * whole debt in a single run so it can be cleared in one pass. WARN violations are logged (a
   * visible, shrinking backlog); IGNORE is silent.
   *
   * <p>The laws (each governable per bundle, default ERROR):
   *
   * <ul>
   *   <li>{@link StagingGate#RECORD_PURITY} — a {@code type=record} bundle may export only records
   *       / enums / sealed ADT roots. Delegated to a {@link RecordPurity} instance OF each record
   *       bundle.
   *   <li>{@link StagingGate#SPEC_COVERAGE} — a bundle may export only types named in a {@code
   *       docs/} spec or marked {@code @Transitional}. Delegated to a {@link SpecCoverage} instance
   *       OF each bundle. A {@code null} docs dir (not found) skips this law rather than failing
   *       spuriously.
   * </ul>
   */
  private void enforceGates(
      MavenSession session, List<ResolvedBundle> resolved, java.nio.file.Path docsDir)
      throws LifecycleExecutionException {
    if (docsDir == null) {
      log.warn(
          "[osgi-staging] docs/ not found from the reactor root — spec-coverage check skipped");
    }
    final GateReport report = new GateReport();
    for (ResolvedBundle bundle : resolved) {
      final Map<StagingGate, EnforcementLevel> governance = bundle.governance().levels();
      if (bundle.embed().map(EmbedCapability::isRecord).orElse(false)) {
        report.record(
            StagingGate.RECORD_PURITY,
            governance,
            bundle,
            bundle.recordPurity().violations(),
            "exports non-data types (only records / enums / sealed ADT roots allowed)");
      }
      if (docsDir != null && bundle.isBundle() && bundle.embed().isPresent()) {
        report.record(
            StagingGate.SPEC_COVERAGE,
            governance,
            bundle,
            bundle.specCoverage(docsDir).violations(),
            "exports types absent from docs/ specs and not @Transitional");
      }
      if (bundle.isBundle() && bundle.embed().isPresent()) {
        report.record(
            StagingGate.INSTANCE_DISCIPLINE,
            governance,
            bundle,
            bundle.instanceDiscipline().violations(),
            "exports public static behaviour helpers (pass instances; factories exempt)");
      }
    }

    // ---- REALM_BOUNDARY: no class references a type unreachable in its realm ----
    final Set<String> forbidden = new java.util.LinkedHashSet<>();
    for (ResolvedBundle b : resolved) {
      if (b.embed().map(EmbedCapability::isDomain).orElse(false)) {
        forbidden.addAll(b.ourExportedPackages());
      }
    }
    if (!forbidden.isEmpty()) {
      // Flat realm: the exec's own classes + the flat tail + the seams. Its visible set is every
      // package that loads flat (our flat packages + the seam exports); a forbidden (bundle-only)
      // package referenced from here cannot be loaded by the flat host classloader at runtime.
      final Set<String> flatVisible = new java.util.LinkedHashSet<>();
      final List<ResolvedBundle.ClassEntry> flatClasses = new ArrayList<>();
      final java.nio.file.Path ownClasses =
          java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
      flatClasses.addAll(ResolvedBundle.classEntriesOf(ownClasses));
      for (ResolvedBundle b : resolved) {
        final boolean domain = b.embed().map(EmbedCapability::isDomain).orElse(false);
        if (b.launcher() || domain) {
          continue; // the framework, and bundle-side domains, are not in the flat realm.
        }
        flatVisible.addAll(b.ourExportedPackages());
        flatClasses.addAll(b.classEntries()); // includes the seams (type=seam) — they are flat too.
      }
      final RealmBoundary flat = new RealmBoundary("flat", forbidden, flatVisible);
      final List<String> flatViolations = new ArrayList<>();
      for (ResolvedBundle.ClassEntry c : flatClasses) {
        flatViolations.addAll(flat.violations(c.binaryName(), c.bytes()));
      }
      // Attribute every flat-realm violation at the governance of the exec project (its
      // package-info).
      report.record(
          StagingGate.REALM_BOUNDARY,
          execGovernance(session),
          execPseudoBundle(session),
          flatViolations,
          "flat-realm classes reference bundle-only packages (host/seam leak)");

      // Bundle realms: each isDomain carrier may reference only its own + imported + system
      // packages.
      for (ResolvedBundle b : resolved) {
        if (!b.embed().map(EmbedCapability::isDomain).orElse(false)) {
          continue;
        }
        final Set<String> visible = new java.util.LinkedHashSet<>(b.ourExportedPackages());
        visible.addAll(b.imports().names());
        final RealmBoundary realm =
            new RealmBoundary("bundle:" + b.symbolicName().orElse(b.ga()), forbidden, visible);
        final List<String> v = new ArrayList<>();
        for (ResolvedBundle.ClassEntry c : b.classEntries()) {
          v.addAll(realm.violations(c.binaryName(), c.bytes()));
        }
        report.record(
            StagingGate.REALM_BOUNDARY,
            b.governance().levels(),
            b,
            v,
            "bundle-realm classes reference packages they do not import (OSGi-internal leak)");
      }
    }

    // ---- DUPLICATE_REALM_CLASS: no package lives flat AND in a staged bundle at once ----
    // The closure tells us which jars stage as bundles; everything else (non-launcher) shades flat.
    // A staged bundle's exported package that ALSO appears flat is one class in two realms — the
    // loader-constraint collision that surfaces as a LinkageError when an instance crosses the
    // seam. Attributed to the EXEC, not the bundle: a duplication is a property of THIS assembly
    // (the same bundle in an exec without the flat copy would not collide), so it is governed by
    // the
    // exec's package-info — exactly as REALM_BOUNDARY attributes its flat-realm leaks to the exec.
    final StagingClosure closure = StagingClosure.compute(resolved);
    final Set<String> stagedGas = closure.stagedGas();
    final Set<String> flatPackages = new java.util.LinkedHashSet<>();
    final java.nio.file.Path execClasses =
        java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
    for (ResolvedBundle.ClassEntry c : ResolvedBundle.classEntriesOf(execClasses)) {
      addPackageOf(flatPackages, c.binaryName());
    }
    for (ResolvedBundle b : resolved) {
      if (b.launcher() || stagedGas.contains(b.ga())) {
        continue; // the framework, and the staged bundles, are NOT flat.
      }
      for (ResolvedBundle.ClassEntry c : b.classEntries()) {
        addPackageOf(flatPackages, c.binaryName());
      }
    }
    final Set<String> seamSurface = new java.util.LinkedHashSet<>();
    for (ResolvedBundle b : resolved) {
      if (b.embed().map(EmbedCapability::isSeam).orElse(false)) {
        seamSurface.addAll(b.exports().names());
      }
    }
    final DuplicateRealmClass duplicate = new DuplicateRealmClass(flatPackages, seamSurface);
    final List<String> duplicateViolations = new ArrayList<>();
    for (ResolvedBundle b : closure.staged()) {
      for (String pkg : duplicate.violations(b)) {
        duplicateViolations.add(b.symbolicName().orElse(b.ga()) + " exports " + pkg);
      }
    }
    report.record(
        StagingGate.DUPLICATE_REALM_CLASS,
        execGovernance(session),
        execPseudoBundle(session),
        duplicateViolations,
        "staged bundles export packages that ALSO live flat in this assembly (one class in two"
            + " realms → LinkageError)");

    // ---- SCHEMA_CONCORD: every Document coordinate has a wire-record whose projected schema is
    // meta-schema-valid (records-as-contract; the record's components ARE the schema) ----
    // The seed-broker seam is system-exported (type=seam), so it is in `resolved` with the seam
    // marker — NOT in closure.staged(); find it by the export package it owns.
    final String seamPackage = "io.nxmatic.rke2lab.seed.broker.port";
    ResolvedBundle seedBrokerBundle = null;
    for (ResolvedBundle b : resolved) {
      if (b.embed().map(EmbedCapability::isSeam).orElse(false)
          && b.exports().names().contains(seamPackage)) {
        seedBrokerBundle = b;
        break;
      }
    }

    if (seedBrokerBundle != null) {
      // The seed-broker seam carries both the Coordinate enum and the wire-records; index the
      // coordinate slugs and the @DocumentContract-carrying records from its class entries, and
      // resolve nested wire-record / seam-enum bytes by internal name over the same bundle.
      final Map<String, byte[]> seamBytes = new LinkedHashMap<>();
      for (ResolvedBundle.ClassEntry c : seedBrokerBundle.classEntries()) {
        seamBytes.put(c.binaryName(), c.bytes());
      }

      // Same string selected the bundle above and derives the internal names here — one source, so
      // a
      // rename cannot drift one past the other. If the seam bundle is present but its Coordinate is
      // not, that is a broken seam, not an empty gate: fail loudly rather than validate nothing.
      final DocumentContractScan scan = new DocumentContractScan(seamPackage);
      final byte[] coordinateClass = seamBytes.get(scan.coordinateInternalName());
      if (coordinateClass == null) {
        throw new IllegalStateException(
            "seed-broker seam bundle exports "
                + seamPackage
                + " but carries no "
                + scan.coordinateInternalName()
                + " — the SCHEMA_CONCORD scan would validate nothing");
      }
      scan.indexCoordinate(coordinateClass);
      for (Map.Entry<String, byte[]> e : seamBytes.entrySet()) {
        scan.scan(e.getKey(), e.getValue());
      }

      final SchemaConcord schemaConcord =
          new SchemaConcord(
              scan.coordinateConstToSlug(), scan.slugToRecordInternalName(), seamBytes::get);
      report.record(
          StagingGate.SCHEMA_CONCORD,
          seedBrokerBundle.governance().levels(),
          seedBrokerBundle,
          schemaConcord.violations(),
          "Document coordinate without a wire-record, or a wire-record whose generated schema is"
              + " invalid against the JSON-Schema meta-schema");
    }

    // ---- PIPELINE_PATTERN: a fluent-pipeline topic implements Topic + exactly one nature ----
    // Topics live BOTH in bundle jars (manifests's internal *Topic classes) and in the exec's own
    // host classes (seed-master's stages), so the scan spans both surfaces exactly as
    // REALM_BOUNDARY
    // does. Exec-side violations attribute to the exec's package-info; bundle-side to the bundle.
    report.record(
        StagingGate.PIPELINE_PATTERN,
        execGovernance(session),
        execPseudoBundle(session),
        PipelinePattern.violations(
            ResolvedBundle.classEntriesOf(
                java.nio.file.Path.of(
                    session.getCurrentProject().getBuild().getOutputDirectory()))),
        "fluent-pipeline topics that implement Topic without exactly one nature"
            + " (Execution/Checkpoint/Pipeline)");
    for (ResolvedBundle b : resolved) {
      if (b.launcher()) {
        continue; // the framework carrier holds no topics.
      }
      report.record(
          StagingGate.PIPELINE_PATTERN,
          b.governance().levels(),
          b,
          PipelinePattern.violations(b.classEntries()),
          "fluent-pipeline topics that implement Topic without exactly one nature"
              + " (Execution/Checkpoint/Pipeline)");
    }

    report.flush();
  }

  /**
   * Add the package of a {@code com/foo/Bar} binary name to {@code packages} (dotted, no class).
   */
  private static void addPackageOf(Set<String> packages, String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    if (slash > 0) {
      packages.add(binaryName.substring(0, slash).replace('/', '.'));
    }
  }

  /**
   * Accumulates each gate's violations by the {@link EnforcementLevel} their bundle declares: ERROR
   * lines become the single aggregated build failure, WARN lines a visible backlog logged at the
   * end, IGNORE is dropped. The {@code @GovernedBy(gate, level)} default is ERROR, so an
   * unspecified domain is locked by default.
   */
  private final class GateReport {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<StagingGate, int[]> tally =
        new java.util.EnumMap<>(StagingGate.class); // [errors, warnings]

    void record(
        StagingGate gate,
        Map<StagingGate, EnforcementLevel> governance,
        ResolvedBundle bundle,
        List<String> violations,
        String what) {
      if (violations.isEmpty()) {
        return;
      }
      final EnforcementLevel level = governance.getOrDefault(gate, EnforcementLevel.ERROR);
      if (level == EnforcementLevel.IGNORE) {
        return;
      }
      final String line =
          "  [" + gateLabel(gate) + "] " + bundle.ga() + " " + what + ": " + violations;
      final int[] counts = tally.computeIfAbsent(gate, g -> new int[2]);
      if (level == EnforcementLevel.ERROR) {
        errors.add(line);
        counts[0] += violations.size();
      } else {
        warnings.add(line);
        counts[1] += violations.size();
      }
    }

    void flush() throws LifecycleExecutionException {
      logSummary();
      if (!warnings.isEmpty()) {
        log.warn(
            "[osgi-staging] {} staging-law violation(s) at WARN (@GovernedBy(...,WARN)) — visible"
                + " backlog, raise to the ERROR default once cleared:\n{}",
            warnings.size(),
            String.join("\n", warnings));
      }
      if (!errors.isEmpty()) {
        throw new LifecycleExecutionException(
            "osgi-staging: "
                + errors.size()
                + " staging-law violation(s) at ERROR — fix each, mark it @Transitional(to,spec), or"
                + " lower the gate to @GovernedBy(gate, WARN) on the package-info while the debt is"
                + " paid down:\n"
                + String.join("\n", errors));
      }
    }

    /**
     * One line per gate: how many violations it found, split ERROR vs WARN — the état des lieux.
     */
    private void logSummary() {
      final StringBuilder summary = new StringBuilder();
      for (StagingGate gate : StagingGate.values()) {
        final int[] counts = tally.getOrDefault(gate, new int[2]);
        summary
            .append("\n  ")
            .append(gateLabel(gate))
            .append(": ")
            .append(counts[0])
            .append(" error, ")
            .append(counts[1])
            .append(" warn");
      }
      log.info("[osgi-staging] gate summary (violations by gate):{}", summary);
    }

    private String gateLabel(StagingGate gate) {
      return gate.name().toLowerCase().replace('_', '-');
    }
  }

  /** The current exec project's governance, read from its own compiled package-info classes. */
  private Map<StagingGate, EnforcementLevel> execGovernance(MavenSession session) {
    final java.nio.file.Path classes =
        java.nio.file.Path.of(session.getCurrentProject().getBuild().getOutputDirectory());
    final Map<StagingGate, EnforcementLevel> levels = new java.util.EnumMap<>(StagingGate.class);
    for (ResolvedBundle.ClassEntry c : ResolvedBundle.classEntriesOf(classes)) {
      if (c.binaryName().endsWith("package-info")) {
        GovernanceReader.readInto(c.bytes(), levels);
      }
    }
    return levels;
  }

  /** A ResolvedBundle view of the exec module itself, for the report's ga() label. */
  private ResolvedBundle execPseudoBundle(MavenSession session) {
    final MavenProject p = session.getCurrentProject();
    return ResolvedBundle.read(p.getGroupId(), p.getArtifactId(), p.getVersion(), null);
  }

  /**
   * The repo's {@code docs/} directory, found by walking up from the current module's basedir — the
   * one tree a Mojo can reach (via the session) that a bundle classloader cannot. {@code null} if
   * no ancestor holds a {@code docs/} directory.
   */
  private static java.nio.file.Path locateDocsDir(MavenSession session) {
    java.nio.file.Path dir = session.getCurrentProject().getBasedir().toPath();
    while (dir != null) {
      final java.nio.file.Path docs = dir.resolve("docs");
      if (java.nio.file.Files.isDirectory(docs)) {
        return docs;
      }
      dir = dir.getParent();
    }
    return null;
  }

  /**
   * Resolve the project's full compile+runtime dependency set, with each jar's file on disk, PLUS
   * the boot stack re-resolved by coordinate.
   *
   * <p>The boot stack is {@code optional} in {@code osgi/runtime} so it stays off the host JCL
   * (bundle-on-jcl-is-wrong-classpath invariant) — which means it is ABSENT from this resolved
   * graph. But the closure must still see it to stage it. So we re-resolve each {@link
   * BootStackJar} directly by its coordinate (version from the project's managed-version map, i.e.
   * the BOM) and fold it into the set the closure seeds from. The graph gives us OUR embed bundles
   * + their flat tail; the registry gives us the third-party boot stack the optional scope hid.
   */
  private List<ResolvedBundle> resolveBundles(MavenSession session)
      throws LifecycleExecutionException {
    final MavenProject project = session.getCurrentProject();
    final DefaultDependencyResolutionRequest request =
        new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
    request.setResolutionFilter(
        new ScopeDependencyFilter(List.of(JavaScopes.COMPILE, JavaScopes.RUNTIME), List.of()));
    final DependencyResolutionResult result;
    try {
      result = dependenciesResolver.resolve(request);
    } catch (DependencyResolutionException ex) {
      throw new LifecycleExecutionException(
          "osgi-staging could not resolve dependencies of " + project.getArtifactId(), ex);
    }
    final List<ResolvedBundle> bundles = new ArrayList<>();
    for (Dependency dependency : result.getResolvedDependencies()) {
      final org.eclipse.aether.artifact.Artifact a = dependency.getArtifact();
      bundles.add(
          ResolvedBundle.read(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile()));
    }
    bundles.addAll(resolveBootStack(session, project));
    return bundles;
  }

  /**
   * Re-resolve the {@link BootStackJar} registry by coordinate. The version is the one the project
   * manages (the BOM line); resolution pulls the jar's file from the local/remote repo regardless
   * of the optional scope that kept it out of the graph above.
   */
  private List<ResolvedBundle> resolveBootStack(MavenSession session, MavenProject project)
      throws LifecycleExecutionException {
    final Map<String, org.apache.maven.artifact.Artifact> managed = project.getManagedVersionMap();
    final List<ResolvedBundle> stack = new ArrayList<>();
    for (BootStackJar jar : BootStackJar.values()) {
      final String key = jar.groupId() + ":" + jar.artifactId();
      final org.apache.maven.artifact.Artifact managedArtifact = managed.get(key + ":jar");
      if (managedArtifact == null) {
        throw new LifecycleExecutionException(
            "osgi-staging: boot-stack jar "
                + key
                + " has no managed version (expected a BOM line)");
      }
      stack.add(resolveByCoordinate(session, jar, managedArtifact.getVersion()));
    }
    return stack;
  }

  private ResolvedBundle resolveByCoordinate(MavenSession session, BootStackJar jar, String version)
      throws LifecycleExecutionException {
    final ArtifactRequest request =
        new ArtifactRequest()
            .setArtifact(new DefaultArtifact(jar.groupId(), jar.artifactId(), "jar", version))
            .setRepositories(session.getCurrentProject().getRemoteProjectRepositories());
    try {
      final ArtifactResult resolved =
          repositorySystem.resolveArtifact(session.getRepositorySession(), request);
      final org.eclipse.aether.artifact.Artifact a = resolved.getArtifact();
      return ResolvedBundle.read(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getFile());
    } catch (ArtifactResolutionException ex) {
      throw new LifecycleExecutionException(
          "osgi-staging could not resolve boot-stack jar "
              + jar.groupId()
              + ":"
              + jar.artifactId()
              + ":"
              + version,
          ex);
    }
  }

  /** Add a shade {@code <exclude>ga</exclude>} per staged bundle, skipping ones already listed. */
  private int injectShadeExcludes(MojoExecution execution, StagingClosure closure) {
    final Xpp3Dom config = configurationOf(execution);
    final Xpp3Dom excludes = child(child(config, "artifactSet"), "excludes");
    final Set<String> present = childValues(excludes);
    int added = 0;
    for (String ga : closure.shadeExcludeGas()) {
      if (present.add(ga)) {
        final Xpp3Dom exclude = new Xpp3Dom("exclude");
        exclude.setValue(ga);
        excludes.addChild(exclude);
        added++;
      }
    }
    return added;
  }

  /**
   * Add a dependency-plugin {@code <artifactItem>} per staged bundle, skipping ones already listed.
   */
  private int injectStagingArtifactItems(MojoExecution execution, StagingClosure closure) {
    final Xpp3Dom config = configurationOf(execution);
    final Xpp3Dom artifactItems = child(config, "artifactItems");
    final Set<String> present = new LinkedHashSet<>();
    for (Xpp3Dom item : artifactItems.getChildren("artifactItem")) {
      present.add(textOf(item, "groupId") + ":" + textOf(item, "artifactId"));
    }
    int added = 0;
    for (ResolvedBundle bundle : closure.staged()) {
      if (present.add(bundle.ga())) {
        artifactItems.addChild(artifactItem(bundle));
        added++;
      }
    }
    return added;
  }

  private static Xpp3Dom artifactItem(ResolvedBundle bundle) {
    final Xpp3Dom item = new Xpp3Dom("artifactItem");
    appendChild(item, "groupId", bundle.groupId());
    appendChild(item, "artifactId", bundle.artifactId());
    appendChild(item, "version", bundle.version());
    appendChild(item, "destFileName", bundle.stagedFileName());
    return item;
  }

  /** The strategy to actually run the executions — the best one that is NOT this decorator. */
  private MojosExecutionStrategy delegate() {
    return strategies.entrySet().stream()
        .filter(e -> !(e.getValue() instanceof StagingExecutionStrategy))
        .map(Map.Entry::getValue)
        .max(java.util.Comparator.comparingInt(StagingExecutionStrategy::priorityOf))
        .orElseThrow(
            () -> new IllegalStateException("no delegate MojosExecutionStrategy to run the build"));
  }

  private static int priorityOf(MojosExecutionStrategy strategy) {
    final Priority p = strategy.getClass().getAnnotation(Priority.class);
    return p == null ? 0 : p.value();
  }

  private static boolean isShade(MojoExecution execution) {
    return "maven-shade-plugin".equals(execution.getArtifactId())
        && "shade".equals(execution.getGoal());
  }

  private static boolean isStageBundles(MojoExecution execution) {
    return "maven-dependency-plugin".equals(execution.getArtifactId())
        && "stage-embedded-bundles".equals(execution.getExecutionId());
  }

  private static Xpp3Dom configurationOf(MojoExecution execution) {
    final Object config = execution.getConfiguration();
    if (config instanceof Xpp3Dom dom) {
      return dom;
    }
    final Xpp3Dom created = new Xpp3Dom("configuration");
    execution.setConfiguration(created);
    return created;
  }

  /**
   * Get-or-create a named child element (the shade {@code artifactSet}/{@code excludes} may be
   * absent).
   */
  private static Xpp3Dom child(Xpp3Dom parent, String name) {
    final Xpp3Dom existing = parent.getChild(name);
    if (existing != null) {
      return existing;
    }
    final Xpp3Dom created = new Xpp3Dom(name);
    parent.addChild(created);
    return created;
  }

  private static Set<String> childValues(Xpp3Dom parent) {
    final Set<String> values = new LinkedHashSet<>();
    for (Xpp3Dom c : parent.getChildren()) {
      values.add(c.getValue());
    }
    return values;
  }

  private static String textOf(Xpp3Dom parent, String name) {
    final Xpp3Dom c = parent.getChild(name);
    return c == null ? null : c.getValue();
  }

  private static void appendChild(Xpp3Dom parent, String name, String value) {
    final Xpp3Dom c = new Xpp3Dom(name);
    c.setValue(value);
    parent.addChild(c);
  }
}
