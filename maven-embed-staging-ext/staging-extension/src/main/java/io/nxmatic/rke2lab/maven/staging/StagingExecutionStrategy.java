package io.nxmatic.rke2lab.maven.staging;

import java.util.ArrayList;
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
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

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

  private final Map<String, MojosExecutionStrategy> strategies;
  private final ProjectDependenciesResolver dependenciesResolver;

  @Inject
  public StagingExecutionStrategy(
      Map<String, MojosExecutionStrategy> strategies,
      ProjectDependenciesResolver dependenciesResolver) {
    this.strategies = strategies;
    this.dependenciesResolver = dependenciesResolver;
  }

  @Override
  public void execute(
      List<MojoExecution> mojoExecutions,
      MavenSession session,
      MojoExecutionRunner mojoExecutionRunner)
      throws LifecycleExecutionException {
    if (mojoExecutions.stream().anyMatch(StagingExecutionStrategy::isShade)) {
      reconfigureStaging(session, mojoExecutions);
    }
    delegate().execute(mojoExecutions, session, mojoExecutionRunner);
  }

  /** Derive the staging closure from the resolved deps and inject both faces of the staging. */
  private void reconfigureStaging(MavenSession session, List<MojoExecution> mojoExecutions)
      throws LifecycleExecutionException {
    final String module = session.getCurrentProject().getArtifactId();
    final StagingClosure closure = StagingClosure.compute(resolveBundles(session));

    int shadeAdded = 0;
    int stageAdded = 0;
    for (MojoExecution execution : mojoExecutions) {
      if (isShade(execution)) {
        shadeAdded = injectShadeExcludes(execution, closure);
      } else if (isStageBundles(execution)) {
        stageAdded = injectStagingArtifactItems(execution, closure);
      }
    }
    System.out.println(
        "[osgi-staging] "
            + module
            + ": derived "
            + closure.staged().size()
            + " bundles; +"
            + shadeAdded
            + " shade excludes, +"
            + stageAdded
            + " staging items");
  }

  /** Resolve the project's full compile+runtime dependency set, with each jar's file on disk. */
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
    return bundles;
  }

  /** Add a shade {@code <exclude>ga</exclude>} per staged bundle, skipping ones already listed. */
  private int injectShadeExcludes(MojoExecution execution, StagingClosure closure) {
    final Xpp3Dom config = configurationOf(execution);
    final Xpp3Dom excludes = child(child(config, "artifactSet"), "excludes");
    final Set<String> present = childValues(excludes);
    int added = 0;
    for (String ga : closure.stagedGas()) {
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
