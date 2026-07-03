/**
 * The cluster domain's internal model. The bundle exports no package — the cluster diagnostician
 * crosses to the doctor as the {@code Specialist} SERVICE (published by SCR), never as a shared
 * package — so it carries no spec-coverage surface and sits at the locked {@code ERROR} default.
 */
@org.jspecify.annotations.NullMarked
package io.nxmatic.rke2lab.cluster.internal;
