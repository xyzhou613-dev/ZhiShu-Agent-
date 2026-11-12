package com.bemodel.compute;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComputeAccessPolicyTest {
    @Test
    void catalogCoversApplyQueryStopRetryAndAgentReads() {
        Set<String> names = ComputeOperation.catalog().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.containsAll(Set.of("REQUEST_SUBMIT", "REQUEST_QUERY", "REQUEST_CANCEL", "JOB_RETRY",
                "JOB_STOP", "WORKFLOW_QUERY", "ACTION_RETRY", "QUEUE_QUERY", "RUNTIME_QUERY", "AGENT_QUERY")));
    }

    @Test
    void noWriteOperationAllowsViewerAndOnlyTwoAllowReviewer() {
        for (ComputeOperation operation : ComputeOperation.catalog()) {
            assertFalse(operation.roles().isEmpty(), operation.name());
            assertFalse(operation.roles().contains("PUBLIC"), operation.name());
            if (operation.mutating()) {
                assertFalse(operation.allows("VIEWER"), operation.name());
                boolean reviewerException = operation == ComputeOperation.RELEASE_SYNC
                        || operation == ComputeOperation.RULE_CONFLICT_RESOLVE;
                assertEquals(reviewerException, operation.allows("REVIEWER"), operation.name());
            }
        }
    }

    @Test
    void sensitiveManualActionsAreNarrowerThanOrdinaryWrites() {
        assertTrue(ComputeOperation.ACTION_RETRY.allows("ADMIN"));
        assertFalse(ComputeOperation.ACTION_RETRY.allows("EDITOR"));
        assertTrue(ComputeOperation.RELEASE_SYNC.allows("REVIEWER"));
        assertFalse(ComputeOperation.RELEASE_SYNC.allows("EDITOR"));
    }

    @Test
    void sharedReadOnlyCatalogsAreVisibleToAllRoles() {
        for (ComputeOperation operation : new ComputeOperation[]{ComputeOperation.RESOURCE_QUERY,
                ComputeOperation.RUNTIME_QUERY, ComputeOperation.ACCESS_POLICY_QUERY,
                ComputeOperation.FIELD_POLICY_QUERY, ComputeOperation.WORKFLOW_QUERY}) {
            assertTrue(operation.allows("VIEWER"), operation.name());
            assertTrue(operation.allows("REVIEWER"), operation.name());
        }
        assertFalse(ComputeOperation.REQUEST_QUERY.allows("VIEWER"));
        assertFalse(ComputeOperation.QUEUE_QUERY.allows("REVIEWER"));
    }

    @Test
    void roleMatchingIsCaseInsensitiveAndRejectsNull() {
        assertTrue(ComputeOperation.REQUEST_SUBMIT.allows(" admin "));
        assertFalse(ComputeOperation.REQUEST_SUBMIT.allows("administer"));
        assertFalse(ComputeOperation.REQUEST_SUBMIT.allows(null));
    }
}
