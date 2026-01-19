package tech.kayys.gamelan.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class WorkflowDefinitionCommandsTest {

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUpOutput() {
        System.setOut(new PrintStream(outputStream));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    void testCreateCommandHelp() {
        WorkflowDefinitionCommands.Create createCmd = new WorkflowDefinitionCommands.Create();
        CommandLine cmd = new CommandLine(createCmd);

        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("create"));
        assertTrue(usage.contains("workflow definition"));
    }

    @Test
    void testGetCommandHelp() {
        WorkflowDefinitionCommands.Get getCmd = new WorkflowDefinitionCommands.Get();
        CommandLine cmd = new CommandLine(getCmd);

        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("get"));
        assertTrue(usage.contains("Definition ID") || usage.contains("definition ID"));
    }

    @Test
    void testListCommandHelp() {
        WorkflowDefinitionCommands.List listCmd = new WorkflowDefinitionCommands.List();
        CommandLine cmd = new CommandLine(listCmd);

        String usage = cmd.getUsageMessage();
        assertNotNull(usage);
        assertTrue(usage.contains("list"));
        assertTrue(usage.contains("workflow definitions"));
    }
}