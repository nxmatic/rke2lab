package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.FelixFrameworkExtension;
import io.nxmatic.rke2lab.junit.testkit.OsgiSpike;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.metatype.AttributeDefinition;
import org.osgi.service.metatype.MetaTypeInformation;
import org.osgi.service.metatype.MetaTypeService;
import org.osgi.service.metatype.ObjectClassDefinition;

/**
 * P2 — an unknown client retrieves the schema (keys/type/required) of a domain by PID via {@code
 * MetaTypeService}, without knowing the provider. Proves the schema is introspectable and
 * structured, the owner's requirement.
 *
 * <p>Access is fully TYPED — no reflection. The trick is the framework's {@code
 * system.packages.extra} exporting {@code org.osgi.service.metatype} from the system bundle (= this
 * test's app classloader), so felix.metatype imports the SAME API copy the test holds; the
 * registered {@code MetaTypeService} is then castable to the test's type with no {@code
 * ClassCastException}. See {@link FelixFrameworkExtension}.
 */
@OsgiSpike
class MetatypeIntrospectionSpikeTest {

  // felix.log provides org.osgi.service.log that felix.metatype requires, in that order.
  @RegisterExtension
  static final FelixFrameworkExtension felix =
      FelixFrameworkExtension.builder()
          .systemPackages(
              "org.osgi.service.metatype;version=1.4", "org.osgi.service.log;version=1.4")
          .installFromClasspath("org.apache.felix.log", "org.apache.felix.metatype")
          .installBundles("schema")
          .build();

  @Test
  void unknownClientReadsTheSchemaByPid() throws Exception {
    Bundle schema = felix.bundle("schema");

    MetaTypeService mts = felix.awaitService(MetaTypeService.class, 5000);
    assertNotNull(mts, "MetaTypeService registered by the felix metatype runtime");

    MetaTypeInformation info = mts.getMetaTypeInformation(schema);
    ObjectClassDefinition ocd =
        info.getObjectClassDefinition("io.nxmatic.rke2lab.osgibench.incus", (String) null);
    assertNotNull(ocd, "OCD retrieved by PID");

    AttributeDefinition[] required = ocd.getAttributeDefinitions(ObjectClassDefinition.REQUIRED);
    AttributeDefinition[] optional = ocd.getAttributeDefinitions(ObjectClassDefinition.OPTIONAL);

    AttributeDefinition configDir =
        Arrays.stream(required)
            .filter(a -> a.getID().equals("configDir"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("configDir is discoverable as required"));
    assertEquals(AttributeDefinition.STRING, configDir.getType(), "configDir typed STRING");

    assertTrue(
        Arrays.stream(optional).anyMatch(a -> a.getID().equals("project")),
        "project is discoverable as optional");
  }
}
