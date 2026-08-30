package com.yagay.nfcdoorcard.xposed;

/** Immutable Provider command/configuration snapshot for one processing decision. */
final class SimConfig {
    final boolean initialized;
    final boolean active;
    final boolean diagnostics;
    final String uid;
    final String commandAction;
    final String commandStatus;
    final long generation;
    final long consumedGeneration;
    final long handledGeneration;
    final long controllerEpoch;
    final int commandPid;

    SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,
              long consumedGeneration, long handledGeneration, String commandAction,
              String commandStatus, int commandPid, long controllerEpoch) {
        this.initialized = initialized;
        this.active = active;
        this.uid = uid;
        this.diagnostics = diagnostics;
        this.generation = generation;
        this.consumedGeneration = consumedGeneration;
        this.handledGeneration = handledGeneration;
        this.commandAction = commandAction;
        this.commandStatus = commandStatus;
        this.commandPid = commandPid;
        this.controllerEpoch = controllerEpoch;
    }

    SimConfig withControllerEpoch(long epoch) {
        return new SimConfig(initialized, active, uid, diagnostics, generation, consumedGeneration,
                handledGeneration, commandAction, commandStatus, commandPid, epoch);
    }

    static SimConfig uninitialized() {
        return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE,
                Long.MIN_VALUE, "", "", 0, 0L);
    }
}
