// @codebase
package io.nxmatic.rke2lab.manifests.cli;

import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisService;
import io.nxmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.nxmatic.rke2lab.osgi.runtime.framework.FrameworkLaunch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
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
      // Boot the embedded Felix from the bundles staged in this exec-jar (the shared boot seam),
      // resolve the one manifests-world service from the registry, drive it, then close. There is
      // no flat-classpath fallback: since the Resolver became an @Reference, manifests-core's
      // @Component activates only under a framework, so off-framework ServiceLoader yielded a null
      // Resolver — the bug this migration fixes.
      try (BootedFramework framework = FrameworkLaunch.embedded().launch()) {
        final ManifestSynthesisService synthesisService =
            framework.awaitService(ManifestSynthesisService.class, 30_000);
        if (synthesisService == null) {
          throw new IllegalStateException(
              "no ManifestSynthesisService in the OSGi registry within 30s");
        }
        synthesize(synthesisService);
      }
    }

    private void synthesize(ManifestSynthesisService synthesisService) {
      try {
        final ManifestSynthesisResult result = synthesisService.synthesize(request);
        logger.info("Manifest synthesis completed by provider '{}'", synthesisService.providerId());
        logger.info("Consolidated manifest output written to {}", result.manifestFile());
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
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
