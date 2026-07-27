package io.nxmatic.rke2lab.seed.broker.port;

/**
 * One link of a value's fil d'Ariane — a self-describing SOURCE COORDINATE: the cellar coordinate
 * ({@code domain}/{@code coordinate}) a value was filed under, and the git source it was cultivated
 * from (the commit {@code sha}, and whether the worktree was {@code dirty} at the time). Flat
 * (String + boolean) so it rides the seam inside a {@link SeedEnvelope} with no bundle-owned type;
 * the git fields are RAW strings, deliberately NOT a domain {@code Provenance} (the seam depends on
 * no domain). An empty {@code sha} is a legitimate root — a store filed before the worktree
 * crossing harvested HEAD, or a run with no {@code .git}.
 */
public record Breadcrumb(String domain, String coordinate, String sha, boolean dirty) {}
