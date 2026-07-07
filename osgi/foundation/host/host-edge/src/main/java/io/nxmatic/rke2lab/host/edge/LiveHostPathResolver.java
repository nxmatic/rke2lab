package io.nxmatic.rke2lab.host.edge;

import io.nxmatic.rke2lab.host.port.HostPathResolver;
import java.nio.file.Path;
import org.osgi.service.component.annotations.Component;

/**
 * The live {@link HostPathResolver}: rewrites an absolute path for the NFS-automount topology, as
 * the control-plane config record did inline. On DARWIN (or with automount off) the path is only
 * absolutised and normalised; on NIXOS a local absolute path is rebased under the cluster's {@code
 * /net/<cluster>.local} prefix so both hosts name the same tree.
 */
@Component(service = HostPathResolver.class)
public final class LiveHostPathResolver implements HostPathResolver {

  @Override
  public Path resolve(WorktreeHost host, Path rawPath, String netPrefix, boolean automount) {
    final Path normalized = rawPath.toAbsolutePath().normalize();
    if (host == WorktreeHost.DARWIN || !automount) {
      return normalized;
    }

    final String path = normalized.toString();
    if (path.startsWith("/net/")) {
      return normalized;
    }
    if (path.startsWith("/private/")) {
      return Path.of(netPrefix + path).normalize();
    }
    if (path.startsWith("/")) {
      return Path.of(netPrefix + "/private" + path).normalize();
    }
    return Path.of(netPrefix + "/private/" + path).normalize();
  }
}
