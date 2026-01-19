///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS tech.kayys.gamelan:gamelan-grpc:1.0.0-SNAPSHOT
//DEPS io.quarkus:quarkus-grpc:3.8.3
//DEPS io.quarkus:quarkus-jackson:3.8.3
//GraalVM native-image: jbang -Djava.awt.headless=true --native-image -Dquarkus.native.enable-jni=false gamelan-cli.java

/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) The original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package main;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tech.kayys.gamelan.cli.*;

@Command(
    name = "gamelan",
    description = "Gamelan Workflow Engine CLI",
    subcommands = {
        WorkflowDefinitionCommands.class,
        WorkflowRunCommands.class,
        ExecutorCommands.class
    },
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
public class gamelan_cli implements Runnable {

    @Option(
        names = {"-s", "--server"},
        description = "gRPC server address (host:port)",
        defaultValue = "localhost:9090"
    )
    private String serverAddress;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new gamelan_cli()).execute(args);
        System.exit(exitCode);
    }
}