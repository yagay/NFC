package com.yagay.nfcdoorcard.xposed;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HookConfigStoreTest {
    @Test public void decodesApplyGenerationWithoutChangingSemantics() {
        Map<String, String> values = new HashMap<>();
        values.put("simulation_enabled", "true");
        values.put("uid", "04A1B2C3");
        values.put("command_generation", "100");
        values.put("command_consumed_generation", "100");
        values.put("command_handled_generation", "100");
        values.put("command_status", "SUCCESS");
        values.put("command_pid", "1234");
        values.put("controller_epoch", "50");
        SimConfig config = HookConfigStore.decode(values);
        assertTrue(config.initialized);
        assertTrue(config.active);
        assertEquals("APPLY", config.commandAction);
        assertEquals(100L, config.generation);
        assertEquals(1234, config.commandPid);
        assertEquals(50L, config.controllerEpoch);
    }

    @Test public void decodesStopAndSeedsMissingEpochInMemory() {
        Map<String, String> values = new HashMap<>();
        values.put("simulation_enabled", "false");
        SimConfig config = HookConfigStore.decode(values);
        assertFalse(config.active);
        assertEquals("STOP", config.commandAction);
        assertEquals(1L, config.controllerEpoch);
    }
}
