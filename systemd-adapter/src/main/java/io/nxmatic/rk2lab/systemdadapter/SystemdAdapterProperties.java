package io.nxmatic.rk2lab.systemdadapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rk2lab.systemd.adapter")
public record SystemdAdapterProperties(String mandatoryTarget, int commandTimeoutSeconds) {

  public SystemdAdapterProperties {
    if (mandatoryTarget == null || mandatoryTarget.isBlank()) {
      mandatoryTarget = "rke2lab.target";
    }
    if (commandTimeoutSeconds <= 0) {
      commandTimeoutSeconds = 5;
    }
  }
}
