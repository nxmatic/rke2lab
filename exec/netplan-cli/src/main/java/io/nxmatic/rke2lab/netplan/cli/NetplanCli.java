package io.nxmatic.rke2lab.netplan.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line interface for netplan operations.
 *
 * <p>Supported commands:
 *
 * <ul>
 *   <li><b>synthesis</b> (default) — derive netplan for a single cluster/node pair
 *   <li><b>yamlExport</b> — export complete network blueprint metadata as YAML
 *   <li><b>jsonSchemaExport</b> — export JSON schema for blueprint validation
 * </ul>
 *
 * <p>Usage examples:
 *
 * <pre>
 * # Runtime synthesis (default command)
 * java -jar rke2lab-netplan.jar synthesis -Drke2lab.netplan.cluster=bioskop -Drke2lab.netplan.node=master
 * java -jar rke2lab-netplan.jar -Drke2lab.netplan.cluster=bioskop
 *
 * # Build-time metadata export
 * java -jar rke2lab-netplan.jar yamlExport > blueprint.yaml
 *
 * # Schema generation
 * java -jar rke2lab-netplan.jar jsonSchemaExport > blueprint-schema.json
 * </pre>
 */
public final class NetplanCli {

  private static final Logger LOG = LoggerFactory.getLogger(NetplanCli.class);

  /** Command contract for netplan CLI operations. */
  public interface Command {
    void execute(String[] args) throws Exception;
  }

  private NetplanCli() {}

  public static void main(String[] args) throws Exception {
    final String commandName = args.length > 0 ? args[0] : "synthesis";

    final Command command =
        switch (commandName) {
          case "synthesis" -> new SynthesisCommand();
          case "yamlExport" -> new BlueprintExportCommand();
          case "jsonSchemaExport" -> {
            LOG.error("jsonSchemaExport not yet implemented");
            System.exit(1);
            yield null;
          }
          default -> {
            LOG.error(
                "unknown command: {} — supported: synthesis (default), yamlExport, jsonSchemaExport",
                commandName);
            System.exit(1);
            yield null;
          }
        };

    if (command != null) {
      command.execute(args);
    }
  }
}
