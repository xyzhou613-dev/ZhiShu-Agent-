package com.bemodel.compute;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/**
 * Node/notification adapters implement this boundary. No production executor is registered by N-12.
 * Both calls must honor the timeout, deduplicate by operationKey across processes/restarts and return
 * an authoritative receipt for that key. Query must never send the operation again. A FAILED receipt
 * may allow retry only when repeating the SAME key cannot duplicate an earlier side effect.
 * Adapters validate their own receipt identity and must never infer success from command acceptance.
 */
public interface ComputeActionExecutor {
    enum State { SUCCEEDED, FAILED, UNKNOWN }
    record Command(long actionId, long workflowId, long decisionId, String operationKey,
                   String actionType, String targetKind, long targetId, JsonNode input) {}
    record Reply(State state, JsonNode receipt, String errorCode, boolean retryAllowed) {}

    /** Stable adapter/configuration identity; changing this value cannot take over an old operation. */
    String key();
    boolean supports(String actionType);
    Reply execute(Command command, Duration timeout) throws Exception;
    Reply query(Command command, Duration timeout) throws Exception;
}
