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

    return new Cidr(ipv4Address.toInetAddress(), prefix);
  }

  /**
   * The host at {@code offset} from this network's base address — derived from the CIDR we already
   * hold, so callers ask the network for its hosts instead of rebuilding and re-parsing an address
   * string. Full integer addition, so an offset that crosses an octet boundary is handled
   * correctly.
   */
  public InetAddress host(int offset) {
    return new IPAddressString(networkAddress.getHostAddress())
        .getAddress()
        .toIPv4()
        .increment(offset)
        .toInetAddress();
  }

  /** The conventional gateway of this network: the first host (offset 1). */
  public InetAddress gateway() {
    return host(1);
  }

  /**
   * Resolve a foreign address into an {@link InetAddress} with this type's consistent exception
   * semantics — manipulating inet addresses is part of a network value-type's role. Used for an
   * address that does not derive from this network's own range (e.g. a fixed LAN gateway outside
   * the allocated slice), asked of the {@code Cidr} in whose address space it lives.
   */
  public InetAddress address(String value) {
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
