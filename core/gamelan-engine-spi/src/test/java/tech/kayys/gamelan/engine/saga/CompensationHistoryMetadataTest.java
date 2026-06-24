package tech.kayys.gamelan.engine.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompensationHistoryMetadataTest {

    @Test
    void keys_areUniqueAndNonBlank() {
        assertEquals(
                CompensationHistoryMetadata.keys().size(),
                new HashSet<>(CompensationHistoryMetadata.keys()).size());
        assertFalse(CompensationHistoryMetadata.keys().isEmpty());
        assertFalse(CompensationHistoryMetadata.keys().stream().anyMatch(String::isBlank));
    }

    @Test
    void stableValues_areUniqueAndNonBlank() {
        List<String> values = List.of(
                CompensationHistoryMetadata.TAKEOVER_REASON_EXPIRED_CLAIM,
                CompensationHistoryMetadata.SKIP_REASON_ACTIVE_CLAIM,
                CompensationHistoryMetadata.SKIP_REASON_ALREADY_COMPENSATED,
                CompensationHistoryMetadata.FAILURE_SOURCE_RESULT,
                CompensationHistoryMetadata.FAILURE_SOURCE_EXCEPTION);

        assertEquals(values.size(), new HashSet<>(values).size());
        assertFalse(values.stream().anyMatch(String::isBlank));
    }
}
