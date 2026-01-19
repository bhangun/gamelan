package tech.kayys.gamelan.runtime.resource;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    WorkflowDefinitionResourceTest.class,
    WorkflowRunResourceTest.class,
    ExecutorRegistryResourceTest.class,
    PluginResourceTest.class,
    CallbackResourceTest.class
})
public class GamelanRuntimeTestSuite {
    // Test suite for all Gamelan Runtime standalone API tests
}