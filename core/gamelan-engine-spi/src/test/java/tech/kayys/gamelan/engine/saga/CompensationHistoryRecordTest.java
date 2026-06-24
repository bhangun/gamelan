package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CompensationHistoryRecordTest {

    @Test
    void record_rejectsBlankEventType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompensationHistoryRecord("", "message", Map.of()));
    }

    @Test
    void record_freezesMetadataAndNormalizesNullMessage() {
        CompensationHistoryRecord record = new CompensationHistoryRecord(
                CompensationEventTypes.COMPENSATION_NODE_FAILED,
                null,
                Map.of(CompensationHistoryMetadata.NODE_ID, "node-1"));

        assertEquals("", record.message());
        assertThrows(
                UnsupportedOperationException.class,
                () -> record.metadata().put(CompensationHistoryMetadata.CLAIM_ID, "claim-1"));
    }
}
