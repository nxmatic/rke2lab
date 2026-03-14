package io.nxmatic.rk2lab.controlplane.incus;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Strongly typed CIDR value.
 */
public record Cidr(InetAddress networkAddress, int prefixLength) {

    /**
     * Parse CIDR notation (example: {@code 10.80.0.0/21}).
     */
    public static Cidr parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CIDR value must not be blank");
        }

        final String[] parts = value.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR format: " + value);
        }

        final InetAddress inet = parseAddress(parts[0]);
        final int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid CIDR prefix: " + value, exception);
        }

        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("CIDR prefix out of range (0..32): " + value);
        }

        return new Cidr(inet, prefix);
    }

    /**
     * Parse IPv4/IPv6 address into {@link InetAddress} with consistent exception semantics.
     */
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
