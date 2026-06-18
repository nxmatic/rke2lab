package io.nxmatic.rke2lab.pulumi.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulumi.automation.StackDeployment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("host")
class StackSnapshotTest {

  @Test
  void outputsNamed_collectsNamedOutputAcrossResources() throws Exception {
    // Envelope JSON with three resources: two have "foo" with different values, one has "bar"
    String envelopeJson =
        "{"
            + "\"version\":3,"
            + "\"deployment\":{"
            + "\"resources\":["
            + "{\"type\":\"a\",\"outputs\":{\"foo\":\"first\"}},"
            + "{\"type\":\"b\",\"outputs\":{\"foo\":\"second\"}},"
            + "{\"type\":\"c\",\"outputs\":{\"bar\":\"unrelated\"}}"
            + "]"
            + "}"
            + "}";

    StackDeployment deployment = StackDeployment.fromJson(envelopeJson);
    StackSnapshot snapshot = StackSnapshot.of(deployment);

    // (a) Named output exists in multiple resources → collected in order
    List<Object> fooOutputs = snapshot.outputsNamed("foo");
    assertEquals(2, fooOutputs.size());
    assertEquals("first", fooOutputs.get(0));
    assertEquals("second", fooOutputs.get(1));

    // (b) Absent key → empty list
    List<Object> absent = snapshot.outputsNamed("absent");
    assertTrue(absent.isEmpty());
  }

  @Test
  void outputsNamed_handlesEmptyDeploymentGracefully() throws Exception {
    // Malformed/empty deployment: no resources key
    String envelopeJson = "{\"version\":3,\"deployment\":{}}";

    StackDeployment deployment = StackDeployment.fromJson(envelopeJson);
    StackSnapshot snapshot = StackSnapshot.of(deployment);

    // (c) Empty deployment → empty list, never throws
    List<Object> result = snapshot.outputsNamed("anything");
    assertTrue(result.isEmpty());
  }

  @Test
  void deployment_returnsWrappedDeploymentMap() throws Exception {
    String envelopeJson = "{\"version\":3,\"deployment\":{\"resources\":[]}}";

    StackDeployment deployment = StackDeployment.fromJson(envelopeJson);
    StackSnapshot snapshot = StackSnapshot.of(deployment);

    assertTrue(snapshot.deployment().isPresent());
    Map<String, Object> deploymentMap = snapshot.deployment().get();
    assertTrue(deploymentMap.containsKey("resources"));
  }
}
