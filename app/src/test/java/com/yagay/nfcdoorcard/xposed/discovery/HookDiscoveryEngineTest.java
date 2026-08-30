package com.yagay.nfcdoorcard.xposed.discovery;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class HookDiscoveryEngineTest {
    private static class Samples {
        int one(byte[] data) { return 0; }
        void oneVoid(byte[] data) { }
        boolean second(int flags, byte[] data) { return true; }
        long fourth(String a, int b, boolean c, byte[] data) { return 0; }
        String badReturn(byte[] data) { return "x"; }
        int noBytes(int x) { return 0; }
        int twoBytes(byte[] a, byte[] b) { return 0; }
        int tooMany(int a, int b, int c, int d, byte[] data) { return 0; }
        int changeRfParamsByConfig(byte[] data) { return 0; }
        boolean sendRawFrame(byte[] data) { return true; }
    }

    private abstract static class AbstractSamples {
        abstract int changeRfParamsByConfig(byte[] data);
    }

    private Method m(String name, Class<?>... p) throws Exception { return Samples.class.getDeclaredMethod(name, p); }

    @Test public void acceptsSupportedRfShapes() throws Exception {
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("one", byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("oneVoid", byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("second", int.class, byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("fourth", String.class, int.class, boolean.class, byte[].class)));
    }

    @Test public void rejectsAbstractRfMethods() throws Exception {
        Method method = AbstractSamples.class.getDeclaredMethod("changeRfParamsByConfig", byte[].class);
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(method));
    }

    @Test public void semanticFilterRejectsUnrelatedByteArrayMethods() throws Exception {
        assertTrue(HookDiscoveryEngine.isRfSemanticCandidate(m("changeRfParamsByConfig", byte[].class)));
        assertFalse(HookDiscoveryEngine.isRfSemanticCandidate(m("sendRawFrame", byte[].class)));
    }

    @Test public void rejectsAmbiguousOrUnsupportedShapes() throws Exception {
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("badReturn", byte[].class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("noBytes", int.class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("twoBytes", byte[].class, byte[].class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("tooMany", int.class, int.class, int.class, int.class, byte[].class)));
    }
}
