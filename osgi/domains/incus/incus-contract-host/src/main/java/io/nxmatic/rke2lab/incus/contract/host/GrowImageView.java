package io.nxmatic.rke2lab.incus.contract.host;

/**
 * The flat IMAGE view the GROW poses on the Pulumi graph — the alias the reuse-lookup keys on, the
 * two NixOS-translated artefact paths a {@code new Image} sources from ({@code metadataPath} =
 * incus.tar.xz, {@code dataPath} = rootfs.squashfs), and the {@code buildChecksum} the host poses
 * on {@code user.rke2lab.imageBuildChecksum}.
 *
 * <p>The artefacts were already built by the scion ({@code the_image_is_built}); the only
 * irreducibly-host gesture left is {@code new Image(...)} (com.pulumi). The {@code buildChecksum}
 * is computed BY THE SCION — it is the sole place that naturally holds all five ingredients (the
 * {@code ImageBuildRequest} fields plus the edge {@code ImageBuilder}'s {@code recipeDigest}, which
 * is OSGi-side); recomputing it host-side would force re-resolving the edge host. The scion
 * computes it and projects it here; the host only poses it.
 */
public record GrowImageView(
    String imageAlias, String metadataPath, String dataPath, String buildChecksum) {}
