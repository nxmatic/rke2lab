package io.nxmatic.rke2lab.netplan.contract;

import java.util.Locale;
import java.util.regex.Pattern;

/** Strongly typed MAC address value. */
public record MacAddress(String value) {

  private static final Pattern MAC_PATTERN =
      Pattern.compile("^[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}$");

  public MacAddress {
    if (value == null || !MAC_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid MAC address: " + value);
    }
  }

  /** Parse and normalize MAC address to lowercase canonical form. */
  public static MacAddress parse(String value) {
    return new MacAddress(value.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return value;
  }
}
