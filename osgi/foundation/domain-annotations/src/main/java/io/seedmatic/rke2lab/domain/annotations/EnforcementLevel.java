package io.seedmatic.rke2lab.domain.annotations;

/**
 * How a build-time staging {@link StagingGate} reports the violations it finds — the graduated dial
 * carried by {@link GovernedBy}. It is the report level, not an on/off switch: the gate always
 * runs; this only decides what a non-empty report does to the build.
 *
 * <ul>
 *   <li>{@link #ERROR} — a violation breaks the build. The locked state: once a bundle's debt is
 *       cleared, the default keeps it clean forever (the anti-pattern can never silently reappear).
 *   <li>{@link #WARN} — violations are listed as a warning, build stays green. A visible, shrinking
 *       backlog: a bundle on its way to {@code ERROR}.
 *   <li>{@link #IGNORE} — violations are silent. Build infrastructure only (a module that powers a
 *       gate cannot be governed by it), never a way to hide a real domain's debt.
 * </ul>
 */
public enum EnforcementLevel {
  IGNORE,
  WARN,
  ERROR
}
