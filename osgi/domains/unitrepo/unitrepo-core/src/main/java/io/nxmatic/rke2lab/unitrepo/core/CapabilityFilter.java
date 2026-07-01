package io.nxmatic.rke2lab.unitrepo.core;

import java.util.Map;
import java.util.Optional;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Matches a requirement's {@code filter:} directive (RFC-1960 LDAP) against a capability's
 * attributes. Uses {@link FrameworkUtil#createFilter} — a pure parser usable WITHOUT a running
 * framework, so it fits the standalone-resolver model. An absent/blank filter matches anything.
 */
final class CapabilityFilter {

  private CapabilityFilter() {}

  static boolean matches(Optional<String> filter, Map<String, Object> attributes) {
    return filter
        .filter(f -> !f.isBlank())
        .map(
            f -> {
              try {
                return FrameworkUtil.createFilter(f).matches(attributes);
              } catch (InvalidSyntaxException e) {
                throw new IllegalArgumentException("invalid capability filter: " + f, e);
              }
            })
        .orElse(true);
  }
}
