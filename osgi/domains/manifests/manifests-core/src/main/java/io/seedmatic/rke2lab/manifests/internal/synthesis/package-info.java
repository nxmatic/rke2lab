/**
 * The manifests synthesis grammar — the fluent {@code during/then} machinery the synthesis
 * pipelines are told in. A {@link Phase} does a gesture and pushes its output through its {@link
 * Phase.Sink}; {@link PhaseRunner} drives each phase's body and logs its entry/exit boundary. This
 * is manifests-core's OWN grammar (not a shared foundation seam): the two surviving synthesis
 * pipelines — the top-level {@code DefaultManifestSynthesisService} and the systemd infrastructure
 * synthesizer — are its only consumers. The word {@code Phase} is deliberately neither {@code
 * Topic} (the former pipeline vocabulary) nor {@code Stage} (jGiven's, the BDD engine), so the two
 * engines never share a term.
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.internal.synthesis;
