package io.nxmatic.rke2lab.world.gateway.port;

/**
 * The org/project/stack identity of a Pulumi stack being diagnosed — the patient in the
 * medical-record model.
 */
public record Patient(String org, String project, String stack) {

  public String qualifiedName() {
    return org + "/" + project + "/" + stack;
  }
}
