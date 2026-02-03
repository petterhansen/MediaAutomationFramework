package com.framework.test;

import com.framework.core.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all unit tests.
 * Provides common setup and teardown functionality.
 */
public abstract class TestBase {
    protected static final Logger logger = LoggerFactory.getLogger(TestBase.class);
    protected Kernel kernel;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        logger.info("🧪 Starting test: {}", testInfo.getDisplayName());
        kernel = Kernel.getInstance();
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        logger.info("✅ Finished test: {}", testInfo.getDisplayName());
        // Cleanup if needed
    }
}
