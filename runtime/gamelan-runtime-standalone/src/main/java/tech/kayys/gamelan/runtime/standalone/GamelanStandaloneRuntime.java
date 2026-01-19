package tech.kayys.gamelan.runtime.standalone;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Main entry point for the Gamelan Standalone Runtime
 * This is a pure server runtime that hosts the workflow engine
 */
@QuarkusMain
public class GamelanStandaloneRuntime {

    public static void main(String[] args) {
        System.out.println("Starting Gamelan Standalone Runtime Server...");
        System.out.println("Server will listen on HTTP port (configured in application.properties)");
        System.out.println("gRPC server will listen on port (configured in application.properties)");

        Quarkus.run();
    }
}