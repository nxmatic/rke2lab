package io.nxmatic.rke2lab.incus.contract;

import io.nxmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The incus scion's HARVEST — the intention of its prep, stored at the cellar so the host can FETCH
 * it and grow the instance (Shape C). The twin of {@code BboxHarvest}: on the Pulumi realisation
 * the scion's {@code Cellar.store} of this record PRODUCES the incus-prep resource (the dissolution
 * of the former host {@code ResourceManager} into the scion's own store — see
 * docs/architecture/osgi/host-cellar-realisation-spec.adoc § every-scion-contributes).
 *
 * <p>It carries the prep INTENTION, never the artefacts (fetch-not-push: the host fetches a
 * fingerprint and a plot, not the cultivated tree). Two facts suffice: {@link #recipeDigest} — the
 * {@code ImageBuilder}'s stable recipe digest the host folds into its image-cache key — and {@link
 * #soil} — the {@code materializationRoot} the manifests tree was cultivated under, the plot the
 * instance mounts. No {@code manifests-cultivated} flag: a blank soil that could not be
 * materialised fails the prep (the When throws), so a present soil IS the proof the tree is there.
 * No {@code dryRun} either: the cellar consults the RunGate itself to route conserve ({@code up})
 * vs pre-reserve ({@code preview}), so the scion never records the mode.
 *
 * <p>Like {@code BboxHarvest} it carries no {@code @Scion}/{@code @Rootstock}: the whole record is
 * the fruit, stored verbatim under its coordinate with no forward sowing to split. {@link
 * SeedContract} binds it to the {@code incus-prep} coordinate for the codec's decode guard.
 */
@SeedContract("incus-prep")
public record IncusHarvest(String recipeDigest, String soil) {}
