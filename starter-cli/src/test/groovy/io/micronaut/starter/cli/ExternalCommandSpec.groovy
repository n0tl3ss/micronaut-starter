package io.micronaut.starter.cli

import picocli.CommandLine
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.environment.OperatingSystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ExternalCommandSpec extends Specification {

    @TempDir
    Path bin

    CommandLine root = MicronautStarter.createCommandLine()

    void "a built-in command is never dispatched to an external executable"() {
        given:
        executable("mn-create-app")

        expect:
        ExternalCommand.find(root, ["create-app", "demo"] as String[], environment()).isEmpty()
    }

    void "names that are not plain lower-case words are never looked up"() {
        given:
        executable("mn-hello")

        expect:
        ExternalCommand.find(root, [name] as String[], environment()).isEmpty()

        where:
        name << ["../hello", "hello.sh", "Hello", "-hello", "hello/x", "", ".", "hello world"]
    }

    void "an unknown name without an executable is left to picocli"() {
        expect:
        ExternalCommand.find(root, ["nothing-here"] as String[], environment()).isEmpty()
        ExternalCommand.locate("nothing-here", environment()).isEmpty()
    }

    void "the executable on PATH is found with the remaining arguments"() {
        given:
        Path hello = executable("mn-hello")

        when:
        Optional<ExternalCommand> command = ExternalCommand.find(root, ["hello", "--flag", "value"] as String[], environment())

        then:
        command.present
        command.get().name == "hello"
        command.get().executable == hello
        command.get().arguments == ["--flag", "value"]
    }

    void "empty PATH entries and non-executable files are ignored"() {
        given:
        Path plain = bin.resolve("mn-plain")
        Files.writeString(plain, "not executable")
        if (!OperatingSystem.current.windows) {
            Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-r--r--"))
        }

        expect:
        OperatingSystem.current.windows || ExternalCommand.locate("plain", environment()).isEmpty()
        ExternalCommand.locate("hello", [PATH: ""]).isEmpty()
        ExternalCommand.locate("hello", [PATH: File.pathSeparator + File.pathSeparator]).isEmpty()
    }

    void "help lists the external commands found on PATH"() {
        given:
        executable("mn-hello")
        executable("mn-migrations")
        Files.writeString(bin.resolve("mn-Not.Valid"), "")

        expect:
        ExternalCommand.available(environment()) == ["hello", "migrations"]
        ExternalCommand.available([PATH: bin.resolve("missing").toString()]).isEmpty()
    }

    @Requires({ !OperatingSystem.current.windows })
    void "mn runs the external executable, passes its arguments through and returns its exit code"() {
        given:
        Path marker = bin.resolve("marker.txt")
        script("mn-hello", "#!/bin/sh\nprintf '%s|' \"\$@\" > \"${marker}\"\nexit 3\n")

        when:
        int exitCode = MicronautStarter.execute(["hello", "one", "--two", "three four"] as String[], environment())

        then:
        exitCode == 3
        Files.readString(marker) == "one|--two|three four|"
    }

    @Requires({ !OperatingSystem.current.windows })
    void "a built-in command still runs even when a same-named executable is on PATH"() {
        given:
        Path marker = bin.resolve("marker.txt")
        script("mn-create-app", "#!/bin/sh\necho hijacked > \"${marker}\"\nexit 3\n")

        when:
        int exitCode = MicronautStarter.execute(["create-app", "--help"] as String[], environment())

        then:
        exitCode == 0
        !Files.exists(marker)
    }

    void "an unknown command without an executable still fails the picocli way"() {
        expect:
        MicronautStarter.execute(["definitely-unknown"] as String[], environment()) != 0
    }

    private Map<String, String> environment() {
        [PATH: bin.toString(), PATHEXT: ".EXE;.CMD;.BAT"]
    }

    private Path executable(String name) {
        String fileName = OperatingSystem.current.windows ? name + ".cmd" : name
        script(fileName, OperatingSystem.current.windows ? "@echo off\r\nexit /b 0\r\n" : "#!/bin/sh\nexit 0\n")
    }

    private Path script(String fileName, String content) {
        Path file = bin.resolve(fileName)
        Files.writeString(file, content)
        if (!OperatingSystem.current.windows) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        file
    }
}
