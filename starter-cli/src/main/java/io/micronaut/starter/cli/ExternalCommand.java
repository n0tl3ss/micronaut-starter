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
import io.micronaut.core.annotation.NonNull;
import picocli.CommandLine;

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
 * <p>The lookup only runs when the first argument is neither an option nor a built-in
 * command, that is, when Picocli would reject it anyway; built-in commands therefore
 * always win. The name must match {@code [a-z][a-z0-9-]*}, so path separators, dots and
 * upper-case names are never looked up. Only the directories listed in {@code PATH} are
 * searched; empty entries (the current directory on some shells) are skipped. On Windows
 * the extensions in {@code PATHEXT} are tried in order.</p>
 *
 * <p>The external command inherits the standard streams and its exit code becomes the
 * exit code of {@code mn}. Only {@link ProcessBuilder} is used, so the lookup works in the
 * native {@code mn} image without further configuration.</p>
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
        if (args.length == 0) {
            return Optional.empty();
        }
        String candidate = args[0];
        if (!NAME.matcher(candidate).matches() || root.getSubcommands().containsKey(candidate)) {
            return Optional.empty();
        }
        return locate(candidate, environment)
                .map(executable -> new ExternalCommand(candidate, executable, Arrays.asList(args).subList(1, args.length)));
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
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
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
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, PREFIX + "*")) {
                for (Path entry : entries) {
                    externalName(entry, extensions).ifPresent(names::add);
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
        List<String> commandLine = new ArrayList<>(arguments.size() + 1);
        commandLine.add(executable.toString());
        commandLine.addAll(arguments);
        try {
            return new ProcessBuilder(commandLine).inheritIO().start().waitFor();
        } catch (IOException e) {
            System.err.println("mn: cannot run " + executable + ": " + e.getMessage());
            return CANNOT_RUN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("mn: interrupted while running " + executable);
            return CANNOT_RUN;
        }
    }

    private static Optional<String> externalName(Path entry, List<String> extensions) {
        if (!Files.isRegularFile(entry) || !Files.isExecutable(entry)) {
            return Optional.empty();
        }
        String fileName = entry.getFileName().toString();
        for (String extension : extensions) {
            if (extension.isEmpty() ? fileName.startsWith(PREFIX)
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
        String path = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
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
