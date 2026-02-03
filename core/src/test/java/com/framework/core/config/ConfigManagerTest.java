package com.framework.core.config;

import com.framework.core.Kernel;
import com.framework.test.TestBase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ConfigManager
 */
class ConfigManagerTest extends TestBase {

    @Test
    void testConfigLoad() {
        assertNotNull(kernel.getConfigManager(), "ConfigManager should not be null");
        assertNotNull(kernel.getConfigManager().getConfig(), "Configuration should not be null");
    }

    @Test
    void testPluginSetting() {
        var config = kernel.getConfigManager().getConfig();

        // Set a test value
        config.setPluginSetting("TestPlugin", "test_key", "test_value");

        // Retrieve it
        String value = config.getPluginSetting("TestPlugin", "test_key", "");

        assertEquals("test_value", value, "Plugin setting should match");
    }

    @Test
    void testPluginSettingDefaultValue() {
        var config = kernel.getConfigManager().getConfig();

        String value = config.getPluginSetting("NonExistentPlugin", "nonexistent_key", "default_value");

        assertEquals("default_value", value, "Should return default value for missing setting");
    }
}
