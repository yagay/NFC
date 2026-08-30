package com.yagay.nfcdoorcard.xposed;

import com.yagay.nfcdoorcard.xposed.discovery.Capability;
import com.yagay.nfcdoorcard.xposed.discovery.HookTarget;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class RefreshTriggerEngineTest {
    private static final class SampleService {
        boolean last;
        public boolean enableNfcShareMode(boolean enabled) { last = enabled; return true; }
    }

    private static final class RejectingService {
        public boolean enableNfcShareMode(boolean enabled) { return false; }
    }

    private HookTarget target(Method m, int score) {
        return HookTarget.fromMethod(Capability.RF_REFRESH_TRIGGER, m, score, "test");
    }

    @Test public void observedInstanceCanBeReused() throws Exception {
        RefreshTriggerEngine engine = new RefreshTriggerEngine();
        SampleService service = new SampleService();
        Method method = SampleService.class.getDeclaredMethod("enableNfcShareMode", boolean.class);
        assertTrue(engine.observe(target(method, 300), method, service, true));
        RefreshTriggerEngine.Invocation result = engine.invoke(true);
        assertTrue(result.success);
        assertTrue(service.last);
        assertEquals("JAVA_TRIGGERED", result.stage);
    }

    @Test public void rejectedObservationDoesNotBind() throws Exception {
        RefreshTriggerEngine engine = new RefreshTriggerEngine();
        RejectingService service = new RejectingService();
        Method method = RejectingService.class.getDeclaredMethod("enableNfcShareMode", boolean.class);
        assertFalse(engine.observe(target(method, 300), method, service, false));
        assertFalse(engine.invoke(true).success);
    }
}
