package tech.kayys.gamelan.engine.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkflowMetadataTest {

    @Test
    void constructorNormalizesNullMapsToEmptyMaps() {
        WorkflowMetadata metadata = new WorkflowMetadata(null, null, Instant.EPOCH, "tester");

        assertFalse(metadata.labels().containsKey("anything"));
        assertFalse(metadata.annotations().containsKey("anything"));
    }

    @Test
    void constructorDefensivelyCopiesAndFreezesMaps() {
        Map<String, String> labels = new HashMap<>();
        labels.put("domain", "agentic-ai");

        WorkflowMetadata metadata = new WorkflowMetadata(labels, Map.of(), Instant.EPOCH, "tester");

        labels.put("domain", "mutated");

        assertFalse(metadata.labels().containsValue("mutated"));
        assertThrows(UnsupportedOperationException.class, () -> metadata.labels().put("x", "y"));
    }

    @Test
    void constructorDefaultsAuditFields() {
        WorkflowMetadata metadata = new WorkflowMetadata(null, null, null, " ");

        assertNotNull(metadata.createdAt());
        assertEquals("system", metadata.createdBy());
    }

    @Test
    void systemFactoryCreatesUsableMetadata() {
        WorkflowMetadata metadata = WorkflowMetadata.system(Map.of("profile", "agentic-local"));

        assertEquals("agentic-local", metadata.labels().get("profile"));
        assertEquals("system", metadata.createdBy());
        assertNotNull(metadata.createdAt());
    }
}
