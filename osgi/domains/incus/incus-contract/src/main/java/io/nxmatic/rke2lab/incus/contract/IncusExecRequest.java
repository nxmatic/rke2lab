package io.nxmatic.rke2lab.incus.contract;

/**
 * The flat coordinates an {@link IncusInstanceContact} needs to reach one instance over {@code ssh
 * … incus exec}: the ssh host that fronts the Incus daemon, the Incus project, and the instance
 * (node) name. A seam record — no host config type crosses; the host projects these three scalars
 * out of its own configuration at the call site.
 */
public record IncusExecRequest(String sshHost, String incusProject, String nodeName) {

  public IncusExecRequest {
    sshHost = normalize(sshHost);
    incusProject = normalize(incusProject);
    nodeName = normalize(nodeName);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
