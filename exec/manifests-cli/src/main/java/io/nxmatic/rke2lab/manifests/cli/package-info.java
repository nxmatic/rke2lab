/**
 * The manifests CLI exec. {@code DUPLICATE_REALM_CLASS} is build-enforced at its locked ERROR
 * default. cdk8s is a legitimate dual-realm library: the host shades it flat while the OSGi
 * manifests world stages it as a bundle copy — cdk8s objects never cross the seam, so the
 * seam-purity derivation exempts the flat∧staged duplication (no seam exports org.cdk8s /
 * software.constructs).
 */
package io.nxmatic.rke2lab.manifests.cli;
