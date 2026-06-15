package io.nxmatic.rke2lab.unitrepo.core;

import java.util.Map;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Matches a requirement's {@code filter:} directive (RFC-1960 LDAP) against a capability's
 * attributes. Uses {@link FrameworkUtil#createFilter} — a pure parser usable WITHOUT a running
 * framework, so it fits the standalone-resolver model. A null/blank filter matches anything.
 */
final class CapabilityFilter {

  private CapabilityFilter() {}

  static boolean matches(String filter, Map<String, Object> attributes) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    try {
      Filter parsed = FrameworkUtil.createFilter(filter);
      return parsed.matches(attributes);
    } catch (InvalidSyntaxException e) {
      throw new IllegalArgumentException("invalid capability filter: " + filter, e);
    }
  }
}
