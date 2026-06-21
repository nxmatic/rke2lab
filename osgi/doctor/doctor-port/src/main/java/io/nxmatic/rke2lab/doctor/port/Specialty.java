package io.nxmatic.rke2lab.doctor.port;

/**
 * The domain a {@link Specialist} covers. The Generalist routes a symptom to the specialists whose
 * domain is relevant. Kept a small closed set the doctor owns; the config subsystem's infra-domain
 * model can map onto it later (the config↔doctor seam) without the doctor depending on config.
 */
public enum Specialty {
  SYSTEMD,
  NETWORK,
  INCUS,
  CLUSTER
}
