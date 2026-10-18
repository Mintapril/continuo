package dev.dsh.pocket.cowork.broker;

import java.util.concurrent.atomic.AtomicReference;

/** Cancellation is attached atomically to one execution; an old request cannot cancel its successor. */
public final class ExecutionCancellation {
    private record State(String execution, boolean cancelled) {}
    private final AtomicReference<State> state=new AtomicReference<>(new State("",false));
    public void begin(String execution) {
        if(execution==null||execution.isBlank())throw new IllegalArgumentException("Execution required");
        state.set(new State(execution,false));
    }
    public void request(String execution) {
        state.updateAndGet(old -> !old.execution().isEmpty()&&old.execution().equals(execution)?new State(execution,true):old);
    }
    public void clear(String execution) {
        state.updateAndGet(old -> old.execution().equals(execution)?new State(execution,false):old);
    }
    public boolean cancelled(){return state.get().cancelled();}
    /** True while `execution` is still the live scope (a later claim replaces it). */
    public boolean current(String execution){return !execution.isEmpty()&&state.get().execution().equals(execution);}
    public void end(){state.set(new State("",false));}
}
