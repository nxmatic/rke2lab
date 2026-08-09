// @codebase
package io.nxmatic.rke2lab.manifests.cli;

import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import io.nxmatic.rke2lab.manifests.cli.bdd.ManifestsCliRun;
import io.nxmatic.rke2lab.manifests.cli.bdd.ManifestsCliScenario;
import io.nxmatic.rke2lab.manifests.cli.versions.SemanticVersion;
import io.nxmatic.rke2lab.manifests.cli.versions.VersionBumper;
import io.nxmatic.rke2lab.manifests.cli.versions.VersionReport;
import io.nxmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.LogFileSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
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
      case "versions" -> {
        final java.util.Map<String, String> options = optionArgs(args);
        return commandOf(
            new VersionsCommand.Builder(this)
                .level(SemanticVersion.Level.parse(versionsOption(options, "level").orElse(null)))
                .apply(versionsOption(options, "apply").map(Boolean::parseBoolean).orElse(false))
                .component(versionsOption(options, "component"))
                .repoRoot(resolveRepoRoot(versionsOption(options, "repoRoot"))));
      }
      default ->
          throw new IllegalArgumentException(
              "Unknown command: " + command + ". Run with 'help' for available commands.");
    }
  }

  private List<CliCommand> availableCommands() {
    return List.of(
        commandOf(new SynthesizeCommand.Builder(this).run(runFromSystemProperties())),
        commandOf(new VersionsCommand.Builder(this)),
        commandOf(new HelpCommand.Builder(this).commands(List.of())));
  }

  /**
   * Parse the verb's trailing {@code key=value} arguments (everything after {@code args[0]}) into a
   * map, so {@code versions apply=true level=major} works as typed — alongside the {@code
   * -Drke2lab.manifests.versions.<key>} system-property form.
   */
  private static java.util.Map<String, String> optionArgs(String[] args) {
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
   * The repository checkout the {@code versions apply} verb writes into — the explicit value if
   * given, else discovered by walking up from the working directory to the dir that holds {@code
   * osgi/domains/manifests/manifests-ingress-contract}. Empty when neither hits.
   */
  private Optional<Path> resolveRepoRoot(Optional<String> explicit) {
    if (explicit.isPresent()) {
      return explicit.map(root -> Path.of(root).toAbsolutePath().normalize());
    }
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (dir != null) {
      if (Files.isDirectory(dir.resolve("osgi/domains/manifests/manifests-ingress-contract"))) {
        return Optional.of(dir);
      }
      dir = dir.getParent();
    }
    return Optional.empty();
  }

  /**
   * The one driver-captured fact: the plot to materialise into, from {@code
   * -Drke2lab.manifests.outdir} (the SOIL the sow carries). Absent → an empty soil (the scion
   * surveys into a temp dir). Every other input falls to {@code ManifestsRunbookInput.defaults()}.
   */
  private ManifestsCliRun runFromSystemProperties() {
    return ManifestsCliRun.of(
        Optional.ofNullable(System.getProperty("rke2lab.manifests.outdir"))
            .map(String::trim)
            .filter(root -> !root.isEmpty()));
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

      private ManifestsCliRun run = ManifestsCliRun.of(Optional.empty());

      Builder(Main main) {
        this.main = main;
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

  private final class VersionsCommand implements CliCommand {

    private static final String INGRESS_SOURCE =
        "osgi/domains/manifests/manifests-ingress-contract/src/main/java/io/nxmatic/rke2lab/"
            + "manifests/ingress/ComponentVersions.java";
    private static final String CORE_RESOURCES =
        "osgi/domains/manifests/manifests-core/src/main/resources";

    final SemanticVersion.Level level;
    final boolean apply;
    final Optional<String> component;
    final Optional<Path> repoRoot;

    @SuppressWarnings("unused")
    VersionsCommand(Builder builder) {
      this.level = builder.level;
      this.apply = builder.apply;
      this.component = builder.component;
      this.repoRoot = builder.repoRoot;
    }

    @Override
    public String name() {
      return "versions";
    }

    @Override
    public String description() {
      return "Report — or with -Drke2lab.manifests.versions.apply=true, apply — the pinned"
          + " component versions against their latest upstream GitHub release";
    }

    @Override
    public String usage() {
      return "versions";
    }

    @Override
    public void run() {
      final VersionBumper bumper = new VersionBumper(level);
      if (apply) {
        runApply(bumper);
      } else {
        runReport(bumper);
      }
    }

    private void runReport(VersionBumper bumper) {
      logger.info(
          "Component versions — bump gate: {} "
              + "(-Drke2lab.manifests.versions.level=major|minor|micro, default minor; "
              + "-Drke2lab.manifests.versions.apply=true to write)",
          level);
      logger.info(
          "{}",
          String.format(
              "  %-22s %-12s %-12s %s", "component", "pinned", "bump-to", "upstream / note"));
      for (final VersionReport report : bumper.report()) {
        if (component.isPresent() && !component.get().equals(report.componentId())) {
          continue;
        }
        logger.info("{}", formatRow(report));
      }
    }

    private void runApply(VersionBumper bumper) {
      if (repoRoot.isEmpty()) {
        logger.error(
            "versions apply: repository root not found — run from the checkout or pass"
                + " -Drke2lab.manifests.versions.repoRoot=<checkout>");
        return;
      }
      final Path root = repoRoot.orElseThrow();
      final Path source = root.resolve(INGRESS_SOURCE);
      final Path resources = root.resolve(CORE_RESOURCES);
      logger.info(
          "Bumping component versions in place — gate: {}, {}",
          level,
          component.map(id -> "component " + id).orElse("all components"));
      for (final String outcome : bumper.apply(component, source, resources)) {
        logger.info("  {}", outcome);
      }
      logger.info("Review the changes (git diff) before committing.");
    }

    private String formatRow(VersionReport report) {
      final String bumpTo =
          report.bumpAvailable() ? report.allowedTarget().orElseThrow().toString() : "—";
      final String tail;
      if (!report.note().isEmpty()) {
        tail = report.note();
      } else if (report.heldByGate()) {
        tail = "latest " + report.upstreamLatest().orElseThrow() + " (beyond " + level + " gate)";
      } else {
        tail = report.upstreamLatest().map(latest -> "latest " + latest).orElse("");
      }
      return String.format(
          "  %-22s %-12s %-12s %s", report.componentId(), report.currentPin(), bumpTo, tail);
    }

    static final class Builder implements CommandBuilder<VersionsCommand> {
      private final Main main;

      private SemanticVersion.Level level = SemanticVersion.Level.MINOR;
      private boolean apply = false;
      private Optional<String> component = Optional.empty();
      private Optional<Path> repoRoot = Optional.empty();

      Builder(Main main) {
        this.main = main;
      }

      Builder level(SemanticVersion.Level level) {
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

      Builder repoRoot(Optional<Path> repoRoot) {
        this.repoRoot = repoRoot;
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
