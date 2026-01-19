package tech.kayys.gamelan.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class ExecutorCommandsTest {

    @Test
    void testRegisterCommandHelp() {
        ExecutorCommands.Register registerCmd = new ExecutorCommands.Register();
        CommandLine cmd = new CommandLine(registerCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("register"));
        assertTrue(usage.contains("executor"));
    }

    @Test
    void testUnregisterCommandHelp() {
        ExecutorCommands.Unregister unregisterCmd = new ExecutorCommands.Unregister();
        CommandLine cmd = new CommandLine(unregisterCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("unregister"));
        assertTrue(usage.contains("executor"));
    }

    @Test
    void testHeartbeatCommandHelp() {
        ExecutorCommands.Heartbeat heartbeatCmd = new ExecutorCommands.Heartbeat();
        CommandLine cmd = new CommandLine(heartbeatCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("heartbeat"));
        assertTrue(usage.contains("executor"));
    }
}