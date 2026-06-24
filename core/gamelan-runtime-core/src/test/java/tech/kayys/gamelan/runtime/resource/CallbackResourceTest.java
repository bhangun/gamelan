package tech.kayys.gamelan.runtime.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.signal.dto.ExternalSignalRequest;

class CallbackResourceTest {

    @Test
    void resolveCallbackToken_prefersBearerAuthorization() {
        assertEquals(
                "bearer-token",
                CallbackResource.resolveCallbackToken(
                        "Bearer bearer-token",
                        "header-token",
                        "query-token"));
    }

    @Test
    void resolveCallbackToken_acceptsBearerCaseInsensitively() {
        assertEquals("bearer-token", CallbackResource.resolveCallbackToken("bearer bearer-token", null, null));
    }

    @Test
    void resolveCallbackToken_fallsBackToCallbackHeader() {
        assertEquals("header-token", CallbackResource.resolveCallbackToken(null, "  header-token  ", "query-token"));
    }

    @Test
    void resolveCallbackToken_fallsBackToQueryForCompatibility() {
        assertEquals("query-token", CallbackResource.resolveCallbackToken(null, null, "  query-token  "));
    }

    @Test
    void resolveCallbackToken_ignoresNonBearerAuthorization() {
        assertEquals("header-token", CallbackResource.resolveCallbackToken("Basic abc", "header-token", null));
    }

    @Test
    void resolveCallbackToken_returnsNullWhenNoUsableTokenExists() {
        assertNull(CallbackResource.resolveCallbackToken("Bearer   ", "  ", ""));
    }

    @Test
    void validateSignal_acceptsWellFormedSignal() {
        ExternalSignalRequest signal = new ExternalSignalRequest(
                "human_approval",
                "approval-node",
                "approval-service",
                null,
                null,
                null);

        assertNull(CallbackResource.validateSignal(signal));
        assertEquals("human_approval", signal.getSignalType());
        assertEquals("approval-node", signal.getTargetNodeId().value());
        assertNotNull(signal.getTimestamp());
        assertEquals(0, signal.getPayload().size());
    }

    @Test
    void validateSignal_rejectsMissingBody() {
        assertEquals(400, CallbackResource.validateSignal(null).getResponse().getStatus());
    }

    @Test
    void validateSignal_rejectsMissingSignalType() {
        ExternalSignalRequest signal = new ExternalSignalRequest(null, "approval-node", null, null, null, null);

        assertEquals(400, CallbackResource.validateSignal(signal).getResponse().getStatus());
    }

    @Test
    void validateSignal_rejectsMissingTargetNodeId() {
        ExternalSignalRequest signal = new ExternalSignalRequest("human_approval", " ", null, null, null, null);

        assertEquals(400, CallbackResource.validateSignal(signal).getResponse().getStatus());
    }
}
