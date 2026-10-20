/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.starter.cli;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * An executable named {@code mn-<name>} on {@code PATH} that extends {@code mn} with a
 * {@code <name>} command, following the {@code git-<name>} and {@code kubectl-<name>}
 * convention.
 *
 * <p>The lookup runs after root options when the first command name is neither a built-in
 * command nor a valid Picocli command, that is, when Picocli would reject it anyway;
 * built-in commands therefore always win. The name must match {@code [a-z][a-z0-9-]*},
 * so path separators, dots and upper-case names are never looked up. Only the directories
 * listed in {@code PATH} are searched; empty entries (the current directory on some
 * shells) are skipped. On Windows the extensions in {@code PATHEXT} are tried in order.</p>
 *
 * <p>The external command inherits the standard streams and its exit code becomes the
 * exit code of {@code mn}. Only {@link ProcessBuilder} is used, so the lookup works in the
 * native {@code mn} image without further configuration. On Windows, batch files run
 * through the command processor; arguments containing command-shell metacharacters are
 * rejected to keep the command literal.</p>
 *
 * @since 5.2.3
 */
@Internal
final class ExternalCommand {

    static final String PREFIX = "mn-";
    static final int CANNOT_RUN = 127;

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final String DEFAULT_PATHEXT = ".EXE;.CMD;.BAT";

    private final String name;
    private final Path executable;
    private final List<String> arguments;

    private ExternalCommand(String name, Path executable, List<String> arguments) {
        this.name = name;
        this.executable = executable;
        this.arguments = arguments;
    }

    /**
     * @return the command name without the {@code mn-} prefix
     */
    @NonNull
    String getName() {
        return name;
    }

    /**
     * @return the executable that will run
     */
    @NonNull
    Path getExecutable() {
        return executable;
    }

    /**
     * @return the arguments passed to the executable
     */
    @NonNull
    List<String> getArguments() {
        return arguments;
    }

    /**
     * Finds the external command for an invocation.
     *
     * @param root        the {@code mn} command line with every built-in subcommand registered
     * @param args        the arguments passed to {@code mn}
     * @param environment the process environment ({@code PATH} and, on Windows, {@code PATHEXT})
     * @return the external command, or empty when {@code args} name an option, a built-in
     * command, an invalid name, or a name with no executable on {@code PATH}
     */
    @NonNull
    static Optional<ExternalCommand> find(@NonNull CommandLine root, @NonNull String[] args, @NonNull Map<String, String> environment) {
        int commandIndex = commandIndex(root, args);
        if (commandIndex < 0) {
            return Optional.empty();
        }
        String candidate = args[commandIndex];
        if (!NAME.matcher(candidate).matches() || root.getSubcommands().containsKey(candidate)) {
            return Optional.empty();
        }
        return locate(candidate, environment)
                .map(executable -> new ExternalCommand(candidate, executable,
                        Arrays.asList(args).subList(commandIndex + 1, args.length)));
    }

    /**
     * Finds the first token that can be a subcommand after parsing root options.
     * Picocli's help and version options must retain their usual precedence, while
     * boolean root options such as {@code --verbose} may precede an external command.
     */
    private static int commandIndex(@NonNull CommandLine root, @NonNull String[] args) {
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--".equals(argument)) {
                return i + 1 < args.length ? i + 1 : -1;
            }
            if (!argument.startsWith("-")) {
                return i;
            }

            String optionName = argument;
            int valueSeparator = argument.indexOf('=');
            if (valueSeparator > 0) {
                optionName = argument.substring(0, valueSeparator);
            }
            OptionSpec option = root.getCommandSpec().findOption(optionName);
            if (option == null || option.usageHelp() || option.versionHelp()) {
                return -1;
            }
            if (valueSeparator > 0) {
                // Boolean flags accept an attached true/false value. Let Picocli
                // produce its normal usage error for every other attached value.
                if (option.arity().max() == 0) {
                    String value = argument.substring(valueSeparator + 1);
                    if (!isBooleanOption(option) || !isBooleanValue(value)) {
                        return -1;
                    }
                }
                continue;
            }
            int optionValues = option.arity().max();
            if (optionValues > 0) {
                if (i + optionValues >= args.length) {
                    return -1;
                }
                i += optionValues;
            }
        }
        return -1;
    }

    private static boolean isBooleanOption(OptionSpec option) {
        return option.type() == boolean.class || option.type() == Boolean.class;
    }

    private static boolean isBooleanValue(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    /**
     * Locates the executable for a command name.
     *
     * @param name        the command name without the {@code mn-} prefix
     * @param environment the process environment
     * @return the first matching executable on {@code PATH}
     */
    @NonNull
    static Optional<Path> locate(@NonNull String name, @NonNull Map<String, String> environment) {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        for (Path directory : searchPath(environment)) {
            for (String extension : extensions(environment)) {
                Path candidate = directory.resolve(PREFIX + name + extension);
                try {
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (SecurityException ignored) {
                    // An inaccessible PATH entry behaves like a missing executable.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Lists the external commands available on {@code PATH}, for {@code mn --help}.
     *
     * @param environment the process environment
     * @return the sorted command names, without the {@code mn-} prefix
     */
    @NonNull
    static List<String> available(@NonNull Map<String, String> environment) {
        TreeSet<String> names = new TreeSet<>();
        List<String> extensions = extensions(environment);
        for (Path directory : searchPath(environment)) {
            try {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, PREFIX + "*")) {
                    for (Path entry : entries) {
                        try {
                            externalName(entry, extensions).ifPresent(names::add);
                        } catch (RuntimeException ignored) {
                            // An inaccessible entry does not hide other commands in this directory.
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // An unreadable PATH entry does not affect help output.
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Runs the external command with inherited standard streams.
     *
     * @return the command's exit code, or {@value #CANNOT_RUN} when it cannot be started
     */
    int run() {
        if (isWindowsBatchFile(executable)
                && (containsBatchShellCharacters(executable.toString())
                || arguments.stream().anyMatch(ExternalCommand::containsBatchShellCharacters))) {
            System.err.println("mn: cannot pass command-shell metacharacters to batch external command " + executable);
            return CANNOT_RUN;
        }
        List<String> commandLine = processCommand();
        Process process = null;
        try {
            process = new ProcessBuilder(commandLine).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("mn: cannot run " + executable + ": " + e.getMessage());
            return CANNOT_RUN;
        } catch (SecurityException e) {
            System.err.println("mn: cannot run " + executable + ": " + e.getMessage());
            return CANNOT_RUN;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            System.err.println("mn: interrupted while running " + executable);
            return CANNOT_RUN;
        }
    }

    private List<String> processCommand() {
        if (isWindowsBatchFile(executable)) {
            // CreateProcess cannot execute a .cmd/.bat file directly. Pass one
            // quoted command string so cmd.exe preserves a path containing spaces.
            List<String> commandLine = new ArrayList<>(4);
            commandLine.add(commandProcessor());
            commandLine.add("/d");
            commandLine.add("/s");
            commandLine.add("/c");
            StringBuilder command = new StringBuilder("\"");
            appendBatchArgument(command, executable.toString());
            for (String argument : arguments) {
                command.append(' ');
                appendBatchArgument(command, argument);
            }
            command.append('"');
            commandLine.add(command.toString());
            return commandLine;
        }
        List<String> commandLine = new ArrayList<>(arguments.size() + 1);
        commandLine.add(executable.toString());
        commandLine.addAll(arguments);
        return commandLine;
    }

    private static void appendBatchArgument(StringBuilder command, String argument) {
        command.append('"').append(argument).append('"');
    }

    private static String commandProcessor() {
        String commandProcessor = System.getenv("ComSpec");
        if (commandProcessor == null || commandProcessor.isBlank()) {
            commandProcessor = System.getenv("COMSPEC");
        }
        return commandProcessor == null || commandProcessor.isBlank() ? "cmd.exe" : commandProcessor;
    }

    private static boolean isWindowsBatchFile(Path path) {
        if (File.separatorChar != '\\') {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".cmd") || fileName.endsWith(".bat");
    }

    private static boolean containsBatchShellCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 || switch (character) {
                case '"', '%', '&', '(', ')', '<', '>', '^', '|', '!' -> true;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> externalName(Path entry, List<String> extensions) {
        if (!Files.isRegularFile(entry) || !Files.isExecutable(entry)) {
            return Optional.empty();
        }
        String fileName = entry.getFileName().toString();
        boolean prefixMatches = File.separatorChar == '\\'
                ? fileName.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                : fileName.startsWith(PREFIX);
        if (!prefixMatches) {
            return Optional.empty();
        }
        for (String extension : extensions) {
            if (extension.isEmpty() ? prefixMatches
                    : fileName.toLowerCase(Locale.ROOT).endsWith(extension) && fileName.length() > extension.length()) {
                String name = fileName.substring(PREFIX.length(), fileName.length() - extension.length());
                if (NAME.matcher(name).matches()) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    private static List<Path> searchPath(Map<String, String> environment) {
        List<Path> directories = new ArrayList<>();
        String path = environment.get("PATH");
        if (path == null) {
            path = environment.get("Path");
        }
        if (path == null) {
            path = "";
        }
        for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                directories.add(Path.of(entry));
            } catch (RuntimeException invalidPath) {
                // Skip entries the file system rejects.
            }
        }
        return directories;
    }

    private static List<String> extensions(Map<String, String> environment) {
        if (File.separatorChar != '\\') {
            return List.of("");
        }
        List<String> extensions = new ArrayList<>();
        for (String extension : environment.getOrDefault("PATHEXT", DEFAULT_PATHEXT).split(";")) {
            if (!extension.isBlank()) {
                extensions.add(extension.trim().toLowerCase(Locale.ROOT));
            }
        }
        return extensions.isEmpty() ? List.of("") : extensions;
    }
}
