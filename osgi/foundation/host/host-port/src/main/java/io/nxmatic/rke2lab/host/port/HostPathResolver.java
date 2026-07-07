package io.nxmatic.rke2lab.host.port;

import java.nio.file.Path;

/**
 * The host-access seam for path topology: rewrites an absolute path between the two worktree hosts
 * the lab runs on. On DARWIN (or when NFS automount is off) a path is used as-is; on NIXOS behind
 * an NFS automount, a local absolute path is rewritten under the {@code /net/<cluster>.local}
 * prefix so the NIXOS side reaches the same tree the DARWIN side sees. The {@code host-edge}
 * provides it (the live mapping consults the process working directory to absolutise a relative
 * input); the caller supplies the raw path and the target host and gets a flat, resolved {@link
 * Path} back.
 *
 * <p>This isolates the ONE piece of host-filesystem knowledge the config record carried inline —
 * the {@code /net}↔{@code /private} automount rewriting — so the record itself becomes a plain data
 * carrier.
 */
public interface HostPathResolver {

  /** The worktree host a path is being resolved FOR — the automount rewriting applies to NIXOS. */
  enum WorktreeHost {
    DARWIN,
    NIXOS
  }

  /**
   * {@code rawPath} resolved for {@code host}: absolutised and normalised, then (on NIXOS with
   * automount) rewritten under {@code netPrefix} so both hosts name the same tree. {@code
   * netPrefix} is the cluster's automount root (e.g. {@code /net/<cluster>.local}); {@code
   * automount} is false to disable the rewriting entirely (the path is only
   * absolutised/normalised).
   */
  Path resolve(WorktreeHost host, Path rawPath, String netPrefix, boolean automount);
}
