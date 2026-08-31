// @codebase
package io.seedmatic.rke2lab.manifests.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.text.PlainTextScenarioWriter;
import io.seedmatic.rke2lab.manifests.cli.bdd.ManifestsCliRun;
import io.seedmatic.rke2lab.manifests.cli.bdd.ManifestsCliScenario;
import io.seedmatic.rke2lab.manifests.cli.bdd.PublishCliScenario;
import io.seedmatic.rke2lab.manifests.cli.bdd.VersionsCliRun;
import io.seedmatic.rke2lab.manifests.cli.bdd.VersionsCliScenario;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.LogFileSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The manifests CLI north-adapter. The {@code synthesize} verb drives {@link ManifestsCliScenario}
 * on the embedded JUnit launcher — the SAME BDD-as-engine machinery {@code seed-master} uses, minus
 * the Pulumi envelope. The scenario opens the gardening and sows the {@code manifests} coordinate
 * through the broker; that grows {@code ManifestSynthesisScenario} in-container, where its
 * {@code @OsgiService ManifestSynthesisService} resolves bundle-side.
 *
 * <p>It does NOT {@code awaitService(ManifestSynthesisService.class)} from the host: that class
 * lives in the non-seam {@code manifests-contract} bundle, so the flat host copy never matches the
 * bundle-registered service (the old boot+awaitService CLI was structurally broken). The host
 * speaks ONLY the {@code seed.broker.port} membrane — the broker sow — never a typed domain
 * service.
 */
public final class Main {

  private final Logger logger = LoggerFactory.getLogger(Main.class);

  private Main() {}

  public static void main(String[] args) throws IOException {
    try {
      new Main().execute(args);
    } catch (UncheckedIOException ex) {
      throw ex.getCause();
    }
  }

  void execute(String[] args) {
    resolveCommand(args).run();
  }

  private CliCommand resolveCommand(String[] args) {
    if (args.length == 0) {
      return commandOf(new HelpCommand.Builder(this).commands(availableCommands()));
    }

    final String command = args[0];
    switch (command) {
      case "help", "--help", "-h" -> {
        return commandOf(new HelpCommand.Builder(this).commands(availableCommands()));
      }
      case "synthesize" -> {
        return commandOf(new SynthesizeCommand.Builder(this).run(runFromSystemProperties()));
      }
      case "publish" -> {
        return commandOf(new PublishCommand.Builder(this).run(runForPublish()));
      }
      case "versions" -> {
        final java.util.Map<String, String> options = optionArgs(args);
        return commandOf(
            new VersionsCommand.Builder(this)
                .level(versionsOption(options, "level").orElse("minor"))
                .apply(versionsOption(options, "apply").map(Boolean::parseBoolean).orElse(false))
                .component(versionsOption(options, "component")));
      }
      default ->
          throw new IllegalArgumentException(
              "Unknown command: " + command + ". Run with 'help' for available commands.");
    }
  }

  private List<CliCommand> availableCommands() {
    return List.of(
        commandOf(new SynthesizeCommand.Builder(this).run(runFromSystemProperties())),
        commandOf(new PublishCommand.Builder(this).run(runFromSystemProperties())),
        commandOf(new VersionsCommand.Builder(this)),
        commandOf(new HelpCommand.Builder(this).commands(List.of())));
  }

  /**
   * Parse the verb's trailing {@code key=value} arguments (everything after {@code args[0]}) into a
   * map, so {@code versions apply=true level=major} works as typed — alongside the {@code
   * -Drke2lab.manifests.versions.<key>} system-property form.
   */
  private java.util.Map<String, String> optionArgs(String[] args) {
    final java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
    for (int i = 1; i < args.length; i++) {
      final int eq = args[i].indexOf('=');
      if (eq > 0) {
        options.put(args[i].substring(0, eq).trim(), args[i].substring(eq + 1).trim());
      }
    }
    return options;
  }

  /**
   * A {@code versions} option: the trailing {@code key=value} arg wins, else the {@code
   * -Drke2lab.manifests.versions.<key>} system property, else empty.
   */
  private Optional<String> versionsOption(java.util.Map<String, String> options, String key) {
    final String fromArg = options.get(key);
    if (fromArg != null && !fromArg.isBlank()) {
      return Optional.of(fromArg.trim());
    }
    return Optional.ofNullable(System.getProperty("rke2lab.manifests.versions." + key))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  /**
   * The facts the CLI seeds so it sows a COMPLETE manifests runbook input: the {@code SOIL} (from
   * {@code -Drke2lab.manifests.outdir}, absent → a temp survey), the {@code IDENTITY}
   * (cluster/node, absent → the blank {@code unknown} cluster), and the mandatory {@code FACET} —
   * the CLI is the sower, so it builds the posture itself (no door default).
   */
  private ManifestsCliRun runFromSystemProperties() {
    return ManifestsCliRun.of(
        sysProperty("rke2lab.manifests.outdir"),
        identityFrom(
            sysProperty("rke2lab.manifests.cluster"), sysProperty("rke2lab.manifests.node")),
        manifestsFacet());
  }

  /**
   * The facts for {@code publish} — {@code synthesize} PLUS an armed delivery. It REQUIRES the plot
   * ({@code -Drke2lab.manifests.outdir}) and the identity ({@code .cluster}/{@code .node}): without
   * them the delivery worktree never prepares and the push is a silent no-op, so a missing one is a
   * hard fail here, not a survey. The FACET carries {@code delivery.push=true}, the git-push gate
   * {@code ManifestSynthesisScenario} reads.
   */
  private ManifestsCliRun runForPublish() {
    final Optional<String> outdir = sysProperty("rke2lab.manifests.outdir");
    if (outdir.isEmpty()) {
      throw new IllegalArgumentException(
          "publish needs -Drke2lab.manifests.outdir (the plot to render + deliver from)");
    }
    final Optional<ManifestsCliRun.Identity> identity =
        identityFrom(
            sysProperty("rke2lab.manifests.cluster"), sysProperty("rke2lab.manifests.node"));
    if (identity.isEmpty()) {
      throw new IllegalArgumentException(
          "publish needs -Drke2lab.manifests.cluster and -Drke2lab.manifests.node (the branch"
              + " manifests/<cluster> the render delivers to)");
    }
    // The publish FACET = the operator posture plus delivery.push=true (the git-push gate
    // ManifestSynthesisScenario reads); armed inline so no separate static helper is minted.
    final ObjectNode facet = (ObjectNode) manifestsFacet();
    facet.putObject("delivery").put("push", true);
    return ManifestsCliRun.of(outdir, identity, facet);
  }

  private Optional<String> sysProperty(String key) {
    return Optional.ofNullable(System.getProperty(key))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  /**
   * The manifests {@code FACET} the CLI sows — the {@code {publish, debug}} concern the seed flow
   * reads from Pulumi, built here instead. Publish defaults to the operator posture (every layer on
   * except {@code mesh}); {@code debug} defaults off. Each toggle is overridable via {@code
   * -Drke2lab.manifests.publish.<layer>} / {@code -Drke2lab.manifests.debug.<toggle>}, so a
   * management render can e.g. sow {@code -Drke2lab.manifests.publish.mesh=true} for the Tailscale
   * client. The shape mirrors {@code ManifestsRunbookInput.Facets} — the membrane carries only
   * JSON.
   */
  private JsonNode manifestsFacet() {
    final ObjectNode facet = JsonNodeFactory.instance.objectNode();
    final ObjectNode publish = facet.putObject("publish");
    publish.put("gitops", publishToggle("gitops", true));
    publish.put("networking", publishToggle("networking", true));
    publish.put("clusterApi", publishToggle("clusterApi", true));
    publish.put("storage", publishToggle("storage", true));
    publish.put("mesh", publishToggle("mesh", false));
    publish.put("highAvailability", publishToggle("highAvailability", true));
    publish.put("cicd", publishToggle("cicd", true));
    final ObjectNode debug = facet.putObject("debug");
    debug.putObject("mesh").put("enabled", debugToggle("mesh"));
    debug.putObject("networking").put("enabled", debugToggle("networking"));
    debug.putObject("nriPlugins").putObject("flox").put("enabled", debugToggle("nriPlugins.flox"));
    return facet;
  }

  private boolean publishToggle(String layer, boolean fallback) {
    return sysProperty("rke2lab.manifests.publish." + layer)
        .map(Boolean::parseBoolean)
        .orElse(fallback);
  }

  private boolean debugToggle(String toggle) {
    return sysProperty("rke2lab.manifests.debug." + toggle)
        .map(Boolean::parseBoolean)
        .orElse(false);
  }

  /**
   * The cluster/node identity the render is keyed on — the CLI's alignment on {@code
   * ClusterSeedScenario}, where the identity rides the incus FACET. Both {@code -D} properties or
   * neither: a partial identity is a misuse, not a survey, so it fails loud rather than silently
   * rendering the blank {@code unknown} cluster.
   */
  private Optional<ManifestsCliRun.Identity> identityFrom(
      Optional<String> clusterName, Optional<String> nodeName) {
    if (clusterName.isPresent() != nodeName.isPresent()) {
      throw new IllegalArgumentException(
          "manifests identity needs BOTH -Drke2lab.manifests.cluster and"
              + " -Drke2lab.manifests.node, or neither (a bare survey render)");
    }
    return clusterName.flatMap(
        cluster -> nodeName.map(node -> new ManifestsCliRun.Identity(cluster, node)));
  }

  private final class HelpCommand implements CliCommand {

    private final List<CliCommand> commands;

    @SuppressWarnings("unused")
    HelpCommand(Builder builder) {
      this.commands = builder.commands;
    }

    @Override
    public String name() {
      return "help";
    }

    @Override
    public String description() {
      return "Show available commands and usage";
    }

    @Override
    public String usage() {
      return "help";
    }

    @Override
    public void run() {
      logger.info("Usage: rke2lab-manifests <command> [args]");
      logger.info("Available commands:");
      for (CliCommand command : commands) {
        logger.info("{}", String.format("  %-24s %s", command.usage(), command.description()));
      }
    }

    static final class Builder implements CommandBuilder<HelpCommand> {
      private final Main main;

      private List<CliCommand> commands = List.of();

      Builder(Main main) {
        this.main = main;
      }

      Builder commands(List<CliCommand> commands) {
        this.commands = List.copyOf(commands);
        return this;
      }

      public HelpCommand build() {
        return main.commandOf(this);
      }

      public Class<HelpCommand> commandClass() {
        return HelpCommand.class;
      }
    }
  }

  private final class SynthesizeCommand implements CliCommand {

    final ManifestsCliRun run;

    @SuppressWarnings("unused")
    SynthesizeCommand(Builder builder) {
      this.run = builder.run;
    }

    @Override
    public String name() {
      return "synthesize";
    }

    @Override
    public String description() {
      return "Synthesize manifests by sowing the manifests coordinate through the broker";
    }

    @Override
    public String usage() {
      return "synthesize";
    }

    @Override
    public void run() {
      // Drive the host-side ManifestsCliScenario on the embedded launcher — the SAME BDD engine
      // seed-master uses. The scenario opens the gardening (boots the staged bundles + resolves the
      // broker) and sows the manifests coordinate; the broker grows ManifestSynthesisScenario
      // in-container, materialising into the SOIL this run carries. No host-typed awaitService.
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(ManifestsCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the manifests-cli scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    ManifestsCliScenario.SEED
                        .into(run)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-cli.log")));
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "manifest synthesis did not complete (" + broken.size() + " failed/aborted)");
        }
        run.materializationRoot()
            .ifPresentOrElse(
                root -> logger.info("Manifests synthesized into {}", root),
                () -> logger.info("Manifests synthesized into a temporary survey directory"));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("the manifests-cli run was interrupted", interrupted);
      }
    }

    static final class Builder implements CommandBuilder<SynthesizeCommand> {
      private final Main main;

      private ManifestsCliRun run;

      Builder(Main main) {
        this.main = main;
        this.run = ManifestsCliRun.of(Optional.empty(), Optional.empty(), main.manifestsFacet());
      }

      Builder run(ManifestsCliRun run) {
        this.run = run;
        return this;
      }

      public SynthesizeCommand build() {
        return main.commandOf(this);
      }

      public Class<SynthesizeCommand> commandClass() {
        return SynthesizeCommand.class;
      }
    }
  }

  private final class PublishCommand implements CliCommand {

    final ManifestsCliRun run;

    @SuppressWarnings("unused")
    PublishCommand(Builder builder) {
      this.run = builder.run;
    }

    @Override
    public String name() {
      return "publish";
    }

    @Override
    public String description() {
      return "Render the manifests into the plot AND deliver them — commit (signed) + push the"
          + " manifests/<cluster> branch, so the Flux webhook reconciles at once";
    }

    @Override
    public String usage() {
      return "publish  (-Drke2lab.manifests.outdir=… -Drke2lab.manifests.cluster=…"
          + " -Drke2lab.manifests.node=…)";
    }

    @Override
    public void run() {
      // Drive PublishCliScenario on the embedded launcher — synthesize + delivery. It sows ghapp →
      // auth → manifests through the broker: ghapp rehydrates the App credentials from .secrets,
      // auth seals the WRITER token, manifests renders into the SOIL and pushes
      // manifests/<cluster>.
      // The same in-container operation the grow drives, minus the Pulumi envelope.
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(PublishCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the manifests-cli publish scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    PublishCliScenario.SEED
                        .into(run)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-publish.log")));
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "the manifests publish did not complete (" + broken.size() + " failed/aborted)");
        }
        run.identity()
            .ifPresent(
                id ->
                    logger.info(
                        "Manifests rendered into {} and pushed to manifests/{}",
                        run.materializationRoot().orElse("?"),
                        id.clusterName()));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "the manifests-cli publish run was interrupted", interrupted);
      }
    }

    static final class Builder implements CommandBuilder<PublishCommand> {
      private final Main main;

      private ManifestsCliRun run;

      Builder(Main main) {
        this.main = main;
        this.run = ManifestsCliRun.of(Optional.empty(), Optional.empty(), main.manifestsFacet());
      }

      Builder run(ManifestsCliRun run) {
        this.run = run;
        return this;
      }

      public PublishCommand build() {
        return main.commandOf(this);
      }

      public Class<PublishCommand> commandClass() {
        return PublishCommand.class;
      }
    }
  }

  private final class VersionsCommand implements CliCommand {

    final String level;
    final boolean apply;
    final Optional<String> component;

    @SuppressWarnings("unused")
    VersionsCommand(Builder builder) {
      this.level = builder.level;
      this.apply = builder.apply;
      this.component = builder.component;
    }

    @Override
    public String name() {
      return "versions";
    }

    @Override
    public String description() {
      return "Report — or with apply=true, apply — the pinned component versions against their"
          + " latest upstream GitHub release, bumping (and committing as the rke2lab bot) in place";
    }

    @Override
    public String usage() {
      return "versions [level=major|minor|micro] [apply=true] [component=<id>]";
    }

    /**
     * Resolve the {@code component=<slug>} filter to a typed {@link Component} — fail-loud on an
     * unknown slug (listing the valid ids) rather than silently bumping everything. Empty = all.
     */
    private Optional<Component> resolveComponent() {
      if (component.isEmpty()) {
        return Optional.empty();
      }
      final String slug = component.orElseThrow();
      return Optional.of(
          Component.fromSlug(slug)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "unknown component '"
                              + slug
                              + "'; valid: "
                              + java.util.Arrays.stream(Component.values())
                                  .map(Component::slug)
                                  .toList())));
    }

    @Override
    public void run() {
      // The bump is a scion: this host root scenario sows the `manifests-versions` coordinate
      // through the broker and grows VersionBumpScenario in-container (where Worktree /
      // AuthTokenContact / NdhKeystoreReader resolve bundle-side, unreachable to the flat host).
      // The reaped runbook — the per-component report, and on apply the bumps + the bot commit — is
      // grafted into this host runbook and rendered to the console below.
      final VersionsCliRun cliRun =
          VersionsCliRun.of(BumpLevel.fromSlug(level), apply, resolveComponent());
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(VersionsCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the versions scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    VersionsCliScenario.SEED
                        .into(cliRun)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-versions.log")));
        renderRunbook(runbook);
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "the version bump did not complete (" + broken.size() + " failed/aborted)");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("the versions run was interrupted", interrupted);
      }
    }

    /**
     * Render the reaped runbook to the console with jGiven's own plain-text writer, on {@code
     * System.out} — NOT the SLF4J logger, which rides the JUL bus pax-logging drains into a file
     * once the scenario boots the framework (that is why the report was invisible). A CLI's
     * user-facing report belongs on stdout; {@code System.out} is not flushed-closed (it is the
     * process stream).
     */
    private void renderRunbook(ReportModel runbook) {
      final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
      runbook.accept(new PlainTextScenarioWriter(out, false));
      out.flush();
    }

    static final class Builder implements CommandBuilder<VersionsCommand> {
      private final Main main;

      private String level = "minor";
      private boolean apply = false;
      private Optional<String> component = Optional.empty();

      Builder(Main main) {
        this.main = main;
      }

      Builder level(String level) {
        this.level = level;
        return this;
      }

      Builder apply(boolean apply) {
        this.apply = apply;
        return this;
      }

      Builder component(Optional<String> component) {
        this.component = component;
        return this;
      }

      public VersionsCommand build() {
        return main.commandOf(this);
      }

      public Class<VersionsCommand> commandClass() {
        return VersionsCommand.class;
      }
    }
  }

  interface CommandBuilder<T extends Runnable> {
    Class<T> commandClass();

    Runnable build();

    default T cast(Runnable command) {
      if (!commandClass().isInstance(command)) {
        throw new IllegalArgumentException(
            "Expected command of type "
                + commandClass().getName()
                + ", got "
                + command.getClass().getName());
      }
      return commandClass().cast(command);
    }
  }

  interface CliCommand extends Runnable {
    String name();

    String description();

    String usage();
  }

  public <T extends Runnable> T commandOf(CommandBuilder<T> builder) {
    try {
      final Constructor<T> constructor = findCommandConstructor(builder);
      constructor.setAccessible(true);
      return builder.cast(
          constructor.getParameterCount() == 2
              ? constructor.newInstance(this, builder)
              : constructor.newInstance(builder));
    } catch (NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException e) {
      throw new RuntimeException(
          "Failed to build command of type " + builder.commandClass().getName(), e);
    }
  }

  private <T extends Runnable> Constructor<T> findCommandConstructor(CommandBuilder<T> builder)
      throws NoSuchMethodException {
    final Class<T> commandType = builder.commandClass();
    final Class<?> builderType = builder.getClass();
    for (Constructor<?> candidate : commandType.getDeclaredConstructors()) {
      final Class<?>[] parameterTypes = candidate.getParameterTypes();
      if (parameterTypes.length == 2
          && parameterTypes[0].equals(Main.class)
          && parameterTypes[1].isAssignableFrom(builderType)) {
        @SuppressWarnings("unchecked")
        final Constructor<T> constructor = (Constructor<T>) candidate;
        return constructor;
      }
      if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(builderType)) {
        @SuppressWarnings("unchecked")
        final Constructor<T> constructor = (Constructor<T>) candidate;
        return constructor;
      }
    }

    throw new NoSuchMethodException(
        "No suitable constructor found for "
            + commandType.getName()
            + " with builder type "
            + builderType.getName());
  }
}
