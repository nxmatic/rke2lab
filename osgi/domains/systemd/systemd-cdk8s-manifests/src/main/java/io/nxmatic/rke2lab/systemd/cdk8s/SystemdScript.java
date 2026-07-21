package io.nxmatic.rke2lab.systemd.cdk8s;

import java.util.Objects;
import software.constructs.Construct;

/**
 * A host script the systemd units invoke (an {@code ExecStart} target). A construct in the {@link
 * SystemdChart} tree so the chart owns the COUPLED pair — the units and the scripts they call — as
 * one bundle. The chart owns only the COLLECTION; the content is injected by the domain that owns
 * the resources (rke2lab manifests), keeping this generic systemd library free of rke2lab
 * specifics.
 *
 * <p>Discovered from the construct tree at synthesis time (like {@link SystemdUnit}), never via
 * constructor self-registration.
 */
public final class SystemdScript extends Construct {

  private final String scriptFileName;
  private final String content;

  public SystemdScript(Construct scope, String id, String scriptFileName, String content) {
    super(scope, id);
    this.scriptFileName = Objects.requireNonNull(scriptFileName, "scriptFileName");
    this.content = Objects.requireNonNull(content, "content");
  }

  /** The script's file name (e.g. {@code "rke2lab-env-load.sh"}). */
  public String getScriptFileName() {
    return scriptFileName;
  }

  /** The script's verbatim content. */
  public String getContent() {
    return content;
  }
}
