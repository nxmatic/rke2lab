/**
 * The flat host control-plane. {@code REALM_BOUNDARY} and {@code DUPLICATE_REALM_CLASS} are both
 * build-enforced at their locked ERROR default — the host holds no bundle-only doctor.records type.
 * cdk8s is a legitimate dual-realm library: the host synthesizes its incus host-slot manifests with
 * its own flat cdk8s copy while the OSGi manifests world synthesizes the k8s cluster manifests with
 * its own bundle copy — cdk8s objects never cross the seam, so the seam-purity derivation exempts
 * the flat∧staged duplication (no seam exports org.cdk8s / software.constructs). The world-exchange
 * migration crosses the boundary only as opaque Documents (see
 * docs/architecture/osgi/world-exchange-spec.adoc).
 */
package io.nxmatic.rke2lab.controlplane;
