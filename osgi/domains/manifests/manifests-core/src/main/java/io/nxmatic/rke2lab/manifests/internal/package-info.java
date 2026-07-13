/**
 * manifests-core's broker-facing internals — the {@code @Component} handlers manifests contributes
 * to the seed broker (the shape reflector; the runbook handler + scion as they land). Not exported:
 * they are SCR-registered services reached only through the broker door, never a compiled API.
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.manifests.internal;
