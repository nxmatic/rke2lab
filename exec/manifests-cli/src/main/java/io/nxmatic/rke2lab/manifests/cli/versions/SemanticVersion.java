package io.nxmatic.rke2lab.manifests.cli.versions;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal semver for the version bumper: {@code major.minor.patch}, tolerant of an optional leading
 * {@code v} and of trailing pre-release/build metadata (which it ignores). Enough to rank GitHub
 * release tags and gate a bump to a {@link Level}.
 */
public record SemanticVersion(int major, int minor, int patch)
    implements Comparable<SemanticVersion> {

  /** The bump level the operator authorises. */
  public enum Level {
    MAJOR,
    MINOR,
    MICRO;

    public static Level parse(final String raw) {
      return switch (raw == null ? "" : raw.trim().toLowerCase()) {
        case "major" -> MAJOR;
        case "micro", "patch" -> MICRO;
        default -> MINOR;
      };
    }
  }

  private static final Pattern PATTERN =
      Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+].*)?$");

  public static Optional<SemanticVersion> parse(final String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    final Matcher m = PATTERN.matcher(raw.trim());
    if (!m.matches()) {
      return Optional.empty();
    }
    final int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
    return Optional.of(
        new SemanticVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), patch));
  }

  @Override
  public int compareTo(final SemanticVersion o) {
    if (major != o.major) {
      return Integer.compare(major, o.major);
    }
    if (minor != o.minor) {
      return Integer.compare(minor, o.minor);
    }
    return Integer.compare(patch, o.patch);
  }

  /**
   * Is {@code candidate} a strictly-higher version reachable from {@code this} within {@code
   * level}?
   */
  public boolean allows(final SemanticVersion candidate, final Level level) {
    if (candidate.compareTo(this) <= 0) {
      return false;
    }
    return switch (level) {
      case MAJOR -> true;
      case MINOR -> candidate.major == major;
      case MICRO -> candidate.major == major && candidate.minor == minor;
    };
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
