package io.nxmatic.rk2lab.controlplane.incus;

/**
 * Stage A runtime network bindings required to launch the management seed node.
 *
 * <p>This model is intentionally minimal and only captures host/runtime prerequisites
 * validated by the local bootstrap executor.</p>
 */
public record SeedNetworkBindings(
        String lanBridgeParent,
        String vmnetNetworkName
) {

    /**
     * Fluent builder entrypoint.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Stable ref/id for contract exports.
     */
    public String ref() {
        return "lan-parent:" + lanBridgeParent + ",vmnet:" + vmnetNetworkName;
    }

    /**
     * Fluent builder for seed runtime bindings.
     */
    public static final class Builder {
        private String lanBridgeParent;
        private String vmnetNetworkName;

        public Builder lanBridgeParent(String lanBridgeParent) {
            this.lanBridgeParent = lanBridgeParent;
            return this;
        }

        public Builder vmnetNetworkName(String vmnetNetworkName) {
            this.vmnetNetworkName = vmnetNetworkName;
            return this;
        }

        public SeedNetworkBindings build() {
            if (lanBridgeParent == null || lanBridgeParent.isBlank()) {
                throw new IllegalArgumentException("lanBridgeParent must be set");
            }
            if (vmnetNetworkName == null || vmnetNetworkName.isBlank()) {
                throw new IllegalArgumentException("vmnetNetworkName must be set");
            }
            return new SeedNetworkBindings(lanBridgeParent, vmnetNetworkName);
        }
    }
}
