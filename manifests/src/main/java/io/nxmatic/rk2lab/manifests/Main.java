// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import io.nxmatic.rk2lab.manifests.layers.runtime.flox.FloxContainerdShimAssets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return commandOf(
            new SynthesizeCommand.Builder(this)
                .request(ManifestSynthesisRequest.fromSystemProperties()));
      }
      case "shim-build" -> {
        final String mode = args.length > 1 ? args[1] : "guest";
        final Path descriptor = args.length > 2 ? Paths.get(args[2]) : null;
        return commandOf(
            new ShimBuildCommand.Builder(this)
                .invocationName("shim-build")
                .description("Build shim packages from descriptor")
                .usage("shim-build [host|guest|pod] [descriptor-file]")
                .mode(mode)
                .descriptor(descriptor));
      }
      case "nix-flake-update" -> {
        final String mode = args.length > 1 ? args[1] : "guest";
        final Path descriptor = args.length > 2 ? Paths.get(args[2]) : null;
        return commandOf(
            new ShimBuildCommand.Builder(this)
                .invocationName("nix-flake-update")
                .description("Update flake locks only (skip package builds)")
                .usage("nix-flake-update [host|guest|pod] [descriptor-file]")
                .mode(mode)
                .descriptor(descriptor)
                .lockOnly(true));
      }
      case "materialize-shim-assets" -> {
        final Path outputDir = args.length > 1 ? Paths.get(args[1]) : Paths.get(".");
        return commandOf(
            new MaterializeShimAssetsCommand.Builder(this)
                .outputDir(outputDir)
                .assets(FloxContainerdShimAssets.materializationAssets()));
      }
      default ->
          throw new IllegalArgumentException(
              "Unknown command: " + command + ". Run with 'help' for available commands.");
    }
  }

  private List<CliCommand> availableCommands() {
    return List.of(
        commandOf(
            new SynthesizeCommand.Builder(this)
                .request(ManifestSynthesisRequest.fromSystemProperties())),
        commandOf(
            new ShimBuildCommand.Builder(this)
                .invocationName("shim-build")
                .description("Build shim packages from descriptor")
                .usage("shim-build [host|guest|pod] [descriptor-file]")),
        commandOf(
            new ShimBuildCommand.Builder(this)
                .invocationName("nix-flake-update")
                .description("Update flake locks only (skip package builds)")
                .usage("nix-flake-update [host|guest|pod] [descriptor-file]")
                .lockOnly(true)),
        commandOf(
            new MaterializeShimAssetsCommand.Builder(this)
                .outputDir(Paths.get("."))
                .assets(FloxContainerdShimAssets.materializationAssets())),
        commandOf(new HelpCommand.Builder(this).commands(List.of())));
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

  private final class ShimBuildCommand implements CliCommand {

    private static final Path WORKTREE_SHIM_ASSETS_RELATIVE_PATH =
        FloxContainerdShimAssets.worktreeShimAssetsRelativePath();

    private final String mode;

    private final Path descriptor;

    private final boolean lockOnly;

    private final String invocationName;

    private final String description;

    private final String usage;

    @SuppressWarnings("unused")
    ShimBuildCommand(Builder builder) {
      this.mode = builder.mode;
      this.descriptor = builder.descriptor;
      this.lockOnly = builder.lockOnly;
      this.invocationName = builder.invocationName;
      this.description = builder.description;
      this.usage = builder.usage;
    }

    @Override
    public String name() {
      return invocationName;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public String usage() {
      return usage;
    }

    @Override
    public void run() {
      if (!"host".equals(mode) && !"guest".equals(mode) && !"pod".equals(mode)) {
        throw new IllegalArgumentException(
            "Unsupported shim-build mode: " + mode + ". Supported modes: host, guest, pod");
      }

      try {
        final Path scriptRoot =
            lockOnly ? resolveWorktreeShimAssetsRoot() : materializeShimAssetsToTemporaryRoot();

        final Path buildScript = scriptRoot.resolve("shim-build.sh");
        if (!Files.isRegularFile(buildScript)) {
          throw new IllegalStateException("Shim build script not found at: " + buildScript);
        }

        final Path descriptorPath =
            (descriptor == null ? scriptRoot.resolve("shim-build.yaml") : descriptor)
                .toAbsolutePath()
                .normalize();
        final String bashInterpreter = resolveBashInterpreter();

        logger.info("Running shim-build in mode '{}' using descriptor {}", mode, descriptorPath);
        logger.info("Using bash interpreter for shim-build: {}", bashInterpreter);
        if (lockOnly) {
          logger.info("Lock-only mode enabled for shim-build; package builds will be skipped.");
        }

        final ProcessBuilder processBuilder =
            new ProcessBuilder(bashInterpreter, buildScript.toString(), descriptorPath.toString())
                .directory(scriptRoot.toFile())
                .inheritIO();

        processBuilder.environment().put("DAEMONLESS_EXEC_MODE", mode);

        if (lockOnly) {
          processBuilder.environment().put("FLOX_SHIM_UPDATE_LOCKS", "true");
          processBuilder.environment().put("FLOX_SHIM_ONLY_UPDATE_LOCKS", "true");
        }

        final Process process = processBuilder.start();

        final int exitCode = process.waitFor();
        if (exitCode != 0) {
          throw new IllegalStateException("shim-build failed with exit code " + exitCode);
        }
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("shim-build was interrupted", ex);
      }
    }

    private Path materializeShimAssetsToTemporaryRoot() throws IOException {
      final Path workDir =
          Files.createTempDirectory("rke2lab-shim-build-").toRealPath().normalize();
      new MaterializeShimAssetsCommand.Builder(Main.this)
          .outputDir(workDir)
          .assets(FloxContainerdShimAssets.materializationAssets())
          .build()
          .run();
      return workDir;
    }

    private Path resolveWorktreeShimAssetsRoot() {
      final Path cwd = Paths.get("").toAbsolutePath().normalize();
      Path current = cwd;
      while (current != null) {
        final Path candidate = current.resolve(WORKTREE_SHIM_ASSETS_RELATIVE_PATH).normalize();
        if (Files.isDirectory(candidate)
            && Files.isRegularFile(candidate.resolve("shim-build.sh"))) {
          logger.info("Using worktree shim assets for lock update: {}", candidate);
          return candidate;
        }
        current = current.getParent();
      }

      throw new IllegalStateException(
          "Unable to locate worktree shim assets at relative path '"
              + WORKTREE_SHIM_ASSETS_RELATIVE_PATH
              + "' from current working directory: "
              + cwd);
    }

    private String resolveBashInterpreter() {
      final String override = System.getenv("RK2LAB_SHIM_BASH");
      if (override != null && !override.isBlank()) {
        return override;
      }

      if (isExecutableOnPath("bash")) {
        return "bash";
      }

      final List<Path> candidates =
          List.of(
              Paths.get("/run/current-system/sw/bin/bash"),
              Paths.get("/opt/homebrew/bin/bash"),
              Paths.get("/usr/local/bin/bash"),
              Paths.get("/bin/bash"));

      for (Path candidate : candidates) {
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return candidate.toString();
        }
      }

      return "bash";
    }

    private boolean isExecutableOnPath(String executableName) {
      final String path = System.getenv("PATH");
      if (path == null || path.isBlank()) {
        return false;
      }

      for (String pathEntry : path.split(Pattern.quote(File.pathSeparator))) {
        if (pathEntry == null || pathEntry.isBlank()) {
          continue;
        }
        final Path candidate = Paths.get(pathEntry, executableName);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return true;
        }
      }

      return false;
    }

    static final class Builder implements CommandBuilder<ShimBuildCommand> {
      private final Main main;

      private String mode = "guest";

      private Path descriptor;

      private boolean lockOnly;

      private String invocationName = "shim-build";

      private String description = "Build shim packages from descriptor";

      private String usage = "shim-build [host|guest] [descriptor-file]";

      Builder(Main main) {
        this.main = main;
      }

      Builder mode(String mode) {
        this.mode = mode;
        return this;
      }

      Builder descriptor(Path descriptor) {
        this.descriptor = descriptor;
        return this;
      }

      Builder lockOnly(boolean lockOnly) {
        this.lockOnly = lockOnly;
        return this;
      }

      Builder invocationName(String invocationName) {
        this.invocationName = invocationName;
        return this;
      }

      Builder description(String description) {
        this.description = description;
        return this;
      }

      Builder usage(String usage) {
        this.usage = usage;
        return this;
      }

      public ShimBuildCommand build() {
        return main.commandOf(this);
      }

      public Class<ShimBuildCommand> commandClass() {
        return ShimBuildCommand.class;
      }
    }
  }

  private final class SynthesizeCommand implements CliCommand {

    final ManifestSynthesisRequest request;

    @SuppressWarnings("unused")
    SynthesizeCommand(Builder builder) {
      this.request = builder.request;
    }

    @Override
    public String name() {
      return "synthesize";
    }

    @Override
    public String description() {
      return "Run manifest synthesis with system-property request settings";
    }

    @Override
    public String usage() {
      return "synthesize";
    }

    @Override
    public void run() {
      try {
        final ManifestSynthesisService synthesisService = loadService();
        final ManifestSynthesisResult result = synthesisService.synthesize(request);
        logger.info("Manifest synthesis completed by provider '{}'", synthesisService.providerId());
        logger.info("Consolidated manifest output written to {}", result.manifestFile());
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }

    ManifestSynthesisService loadService() {
      final List<ManifestSynthesisService> providers =
          ServiceLoader.load(ManifestSynthesisService.class).stream()
              .map(ServiceLoader.Provider::get)
              .toList();
      if (providers.isEmpty()) {
        throw new IllegalStateException(
            "No ManifestSynthesisService provider found via ServiceLoader.");
      }
      if (providers.size() > 1) {
        throw new IllegalStateException(
            "Expected exactly one ManifestSynthesisService provider, found "
                + providers.size()
                + ": "
                + providers.stream().map(ManifestSynthesisService::providerId).toList());
      }
      return providers.getFirst();
    }

    static final class Builder implements CommandBuilder<SynthesizeCommand> {
      private final Main main;

      Builder(Main main) {
        this.main = main;
      }

      private ManifestSynthesisRequest request = ManifestSynthesisRequest.fromSystemProperties();

      Builder request(ManifestSynthesisRequest request) {
        this.request = request;
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

  private final class MaterializeShimAssetsCommand implements CliCommand {

    final Path outputDir;

    final List<EmbeddedAsset> assets;

    @SuppressWarnings("unused")
    MaterializeShimAssetsCommand(Builder builder) {
      this.outputDir = builder.outputDir;
      this.assets = builder.assets;
    }

    @Override
    public String name() {
      return "materialize-shim-assets";
    }

    @Override
    public String description() {
      return "Materialize embedded shim assets to a local directory";
    }

    @Override
    public String usage() {
      return "materialize-shim-assets [output-dir]";
    }

    @Override
    public void run() {
      try {
        final Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedOutputDir);

        for (EmbeddedAsset asset : assets) {
          final Path targetPath = normalizedOutputDir.resolve(asset.relativePath()).normalize();
          Files.createDirectories(targetPath.getParent());

          try (InputStream in = Main.class.getResourceAsStream(asset.classpathResource())) {
            if (in == null) {
              throw new IllegalStateException(
                  "Missing embedded resource: " + asset.classpathResource());
            }
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
          }

          if (asset.executable()) {
            try {
              Files.setPosixFilePermissions(
                  targetPath,
                  Set.of(
                      PosixFilePermission.OWNER_READ,
                      PosixFilePermission.OWNER_WRITE,
                      PosixFilePermission.OWNER_EXECUTE,
                      PosixFilePermission.GROUP_READ,
                      PosixFilePermission.GROUP_EXECUTE,
                      PosixFilePermission.OTHERS_READ,
                      PosixFilePermission.OTHERS_EXECUTE));
            } catch (UnsupportedOperationException ex) {
              // Non-POSIX filesystem; best effort only.
            }
          }
        }

        FloxContainerdShimAssets.materializeSupplementaryAssetsTo(normalizedOutputDir);

        logger.info("Materialized {} shim assets to {}", assets.size(), normalizedOutputDir);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }

    static final class Builder implements CommandBuilder<MaterializeShimAssetsCommand> {
      private final Main main;

      private Path outputDir = Paths.get(".");

      private List<EmbeddedAsset> assets = List.of();

      Builder(Main main) {
        this.main = main;
      }

      Builder outputDir(Path outputDir) {
        this.outputDir = outputDir;
        return this;
      }

      Builder assets(List<EmbeddedAsset> assets) {
        this.assets = List.copyOf(assets);
        return this;
      }

      public MaterializeShimAssetsCommand build() {
        return main.commandOf(this);
      }

      public Class<MaterializeShimAssetsCommand> commandClass() {
        return MaterializeShimAssetsCommand.class;
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
