package tech.kayys.gamelan.engine.signal;

public interface SignalHandler {

    String signalType();

    void handle(SignalContext ctx);
}