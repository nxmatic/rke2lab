/**
 * The systemd domain's internal model. The bundle exports no package — the systemd diagnostician
 * crosses to the doctor as the {@code Specialist} SERVICE (published by SCR), never as a shared
 * package — so it carries no spec-coverage surface and sits at the locked {@code ERROR} default.
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.systemd.internal;
