package io.nxmatic.rke2lab.netplan.port;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Strongly typed CIDR value backed by IPAddress validation. */
public record Cidr(InetAddress networkAddress, int prefixLength) {

  /** Parse CIDR notation (example: {@code 10.80.0.0/21}). */
  public static Cidr parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CIDR value must not be blank");
    }

    final IPAddressString addressString = new IPAddressString(value.trim());
    if (!addressString.isValid()) {
      throw new IllegalArgumentException(
          "Invalid CIDR value: " + value + " (" + addressString.getAddressStringException() + ")");
    }

    if (addressString.getAddress() == null || !addressString.getAddress().isPrefixed()) {
      throw new IllegalArgumentException("CIDR prefix is required: " + value);
    }

    if (!addressString.getAddress().isIPv4()) {
      throw new IllegalArgumentException(
          "Only IPv4 CIDRs are supported for netplan stage: " + value);
    }

    final IPv4Address ipv4Address = addressString.getAddress().toIPv4();
    final Integer prefix = ipv4Address.getNetworkPrefixLength();
    if (prefix == null || prefix < 0 || prefix > 32) {
      throw new IllegalArgumentException("CIDR prefix out of range (0..32): " + value);
    }

    return new Cidr(parseAddress(ipv4Address.toInetAddress().getHostAddress()), prefix);
  }

  /** Parse IPv4/IPv6 address into {@link InetAddress} with consistent exception semantics. */
  public static InetAddress parseAddress(String value) {
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException exception) {
      throw new IllegalArgumentException("Invalid inet address: " + value, exception);
    }
  }

  @Override
  public String toString() {
    return networkAddress.getHostAddress() + "/" + prefixLength;
  }
}
