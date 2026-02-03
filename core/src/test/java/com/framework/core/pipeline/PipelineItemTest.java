package com.framework.core.pipeline;

import com.framework.test.TestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PipelineItem retry mechanism
 */
class PipelineItemTest extends TestBase {

    @Test
    void testRetryCountInitialization() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        assertEquals(0, item.getRetryCount(), "Retry count should start at 0");
        assertEquals(3, item.getMaxRetries(), "Max retries should default to 3");
        assertTrue(item.shouldRetry(), "Should allow retries initially");
    }

    @Test
    void testRetryIncrement() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        item.incrementRetry();
        assertEquals(1, item.getRetryCount(), "Retry count should be 1 after increment");

        item.incrementRetry();
        assertEquals(2, item.getRetryCount(), "Retry count should be 2 after second increment");
    }

    @Test
    void testShouldRetryLogic() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        // Initially should retry
        assertTrue(item.shouldRetry(), "Should retry at count 0");

        item.incrementRetry(); // count = 1
        assertTrue(item.shouldRetry(), "Should retry at count 1");

        item.incrementRetry(); // count = 2
        assertTrue(item.shouldRetry(), "Should retry at count 2");

        item.incrementRetry(); // count = 3
        assertFalse(item.shouldRetry(), "Should NOT retry at count 3 (max reached)");
    }

    @Test
    void testExponentialBackoff() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        // Initial delay (2^0 = 1 second)
        assertEquals(1000L, item.getNextRetryDelay(), "First retry delay should be 1s");

        item.incrementRetry();
        // Second delay (2^1 = 2 seconds)
        assertEquals(2000L, item.getNextRetryDelay(), "Second retry delay should be 2s");

        item.incrementRetry();
        // Third delay (2^2 = 4 seconds)
        assertEquals(4000L, item.getNextRetryDelay(), "Third retry delay should be 4s");

        item.incrementRetry();
        // Fourth delay (2^3 = 8 seconds)
        assertEquals(8000L, item.getNextRetryDelay(), "Fourth retry delay should be 8s");
    }

    @Test
    void testErrorTracking() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        assertNull(item.getLastError(), "Last error should be null initially");

        Exception testException = new RuntimeException("Test error");
        item.setLastError(testException);

        assertEquals(testException, item.getLastError(), "Last error should be stored");
        assertEquals("Test error", item.getLastError().getMessage(), "Error message should match");
    }

    @Test
    void testCustomMaxRetries() {
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);

        item.setMaxRetries(5);
        assertEquals(5, item.getMaxRetries(), "Max retries should be customizable");

        // Test with custom max
        item.incrementRetry(); // 1
        item.incrementRetry(); // 2
        item.incrementRetry(); // 3
        item.incrementRetry(); // 4
        assertTrue(item.shouldRetry(), "Should still retry at count 4 when max is 5");

        item.incrementRetry(); // 5
        assertFalse(item.shouldRetry(), "Should NOT retry at count 5 when max is 5");
    }

    @Test
    void testFirstAttemptTime() {
        long beforeCreation = System.currentTimeMillis();
        PipelineItem item = new PipelineItem("http://example.com/test.jpg", "test.jpg", null);
        long afterCreation = System.currentTimeMillis();

        long firstAttempt = item.getFirstAttemptTime();

        assertTrue(firstAttempt >= beforeCreation && firstAttempt <= afterCreation,
                "First attempt time should be set at creation");
    }
}
