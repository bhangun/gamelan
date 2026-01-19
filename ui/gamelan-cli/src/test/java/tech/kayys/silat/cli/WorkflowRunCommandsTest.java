package tech.kayys.gamelan.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class WorkflowRunCommandsTest {

    @Test
    void testCreateRunCommandHelp() {
        WorkflowRunCommands.Create createCmd = new WorkflowRunCommands.Create();
        CommandLine cmd = new CommandLine(createCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("create"));
        assertTrue(usage.contains("workflow run"));
    }

    @Test
    void testGetRunCommandHelp() {
        WorkflowRunCommands.Get getCmd = new WorkflowRunCommands.Get();
        CommandLine cmd = new CommandLine(getCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("get"));
        assertTrue(usage.contains("Run ID"));
    }

    @Test
    void testListRunCommandHelp() {
        WorkflowRunCommands.List listCmd = new WorkflowRunCommands.List();
        CommandLine cmd = new CommandLine(listCmd);
        
        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("list"));
        assertTrue(usage.contains("workflow runs"));
    }
}