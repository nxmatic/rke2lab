package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The CELLAR-level {@link Breadcrumb} — a value's SOURCE COORDINATE: the cellar coordinate ({@code
 * domain}/{@code coordinate}) a value was filed under, PLUS the git source it was cultivated from
 * (the commit {@code sha}, and whether the worktree was {@code dirty} at the time). The git fields
 * are RAW strings, deliberately NOT a domain {@code Provenance} (the seam depends on no domain). An
 * empty {@code sha} is a legitimate root — a store filed before the worktree crossing harvested
 * HEAD, or a run with no {@code .git}.
 */
public record SourceCrumb(String domain, String coordinate, String sha, boolean dirty)
    implements Breadcrumb {}
