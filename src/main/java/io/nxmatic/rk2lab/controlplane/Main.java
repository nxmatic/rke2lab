package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Pulumi;

/**
 * Entry point for the Pulumi management-cluster bootstrap program.
 */
public final class Main {

    private Main() {
        // Utility class
    }

    public static void main(String[] args) {
        Pulumi.run(context -> {
            // Stage A contract placeholders.
            context.export("managementClusterName", "bioskop");
            context.export("bootstrapPhase", "contract-scaffold");
            context.export("nextStep", "implement-incus-provisioning-and-readiness-gates");
        });
    }
}
