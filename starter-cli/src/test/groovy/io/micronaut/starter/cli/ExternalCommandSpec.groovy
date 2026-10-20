package io.micronaut.starter.cli

import picocli.CommandLine
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions
import spock.util.environment.OperatingSystem

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    void "a registered built-in alias is never dispatched to an external executable"() {
        given:
        root.addSubcommand("ca", root.getSubcommands().get("create-app"))
        executable("mn-ca")

        expect:
        ExternalCommand.find(root, ["ca", "demo"] as String[], environment()).isEmpty()
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

    void "root options and the option separator can precede an external command"() {
        given:
        executable("mn-hello")

        expect:
        ExternalCommand.find(root, ["--verbose", "hello", "one"] as String[], environment()).get().arguments == ["one"]
        ExternalCommand.find(root, ["--", "hello", "two"] as String[], environment()).get().arguments == ["two"]
    }

    void "boolean root options accept attached values before an external command"() {
        given:
        executable("mn-hello")

        expect:
        ExternalCommand.find(root, ["--verbose=false", "hello"] as String[], environment()).present
        ExternalCommand.find(root, ["--verbose=bogus", "hello"] as String[], environment()).isEmpty()
    }

    void "help and version retain precedence over external commands"() {
        given:
        executable("mn-help")
        executable("mn-version")

        expect:
        ExternalCommand.find(root, ["--help", "hello"] as String[], environment()).isEmpty()
        ExternalCommand.find(root, ["--version", "hello"] as String[], environment()).isEmpty()
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

    @Requires({ !OperatingSystem.current.windows })
    void "relative PATH entries are resolved from the working directory"() {
        given:
        executable("mn-relative")
        Path relativeBin = Path.of("").toAbsolutePath().relativize(bin.toAbsolutePath())

        expect:
        ExternalCommand.locate("relative", [PATH: relativeBin.toString()]).present
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

    @Requires({ OperatingSystem.current.windows })
    void "help ignores files with a PATHEXT suffix that do not have the mn prefix"() {
        given:
        executable("otherhello")

        expect:
        ExternalCommand.available(environment()).isEmpty()
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
    void "interrupting an external command destroys its child and restores the interrupt"() {
        given:
        Path pidFile = bin.resolve("child.pid")
        script("mn-sleep", "#!/bin/sh\nprintf '%s' \"\$\$\" > \"${pidFile}\"\nexec sleep 30\n")
        ExternalCommand command = ExternalCommand.find(root, ["sleep"] as String[], environment()).get()
        AtomicInteger exitCode = new AtomicInteger()
        AtomicBoolean interrupted = new AtomicBoolean()
        Thread worker = Thread.start {
            exitCode.set(command.run())
            interrupted.set(Thread.currentThread().isInterrupted())
        }
        long childPid = -1
        new PollingConditions(timeout: 10, initialDelay: 0.1, delay: 0.1).eventually {
            assert Files.exists(pidFile)
            assert !Files.readString(pidFile).isBlank()
        }
        childPid = Long.parseLong(Files.readString(pidFile))

        when:
        worker.interrupt()
        worker.join(5000)

        then:
        !worker.isAlive()
        exitCode.get() == ExternalCommand.CANNOT_RUN
        interrupted.get()
        new PollingConditions(timeout: 5, initialDelay: 0.1, delay: 0.1).eventually {
            assert !ProcessHandle.of(childPid).map { handle -> handle.isAlive() }.orElse(false)
        }

        cleanup:
        if (worker.isAlive()) {
            worker.interrupt()
            worker.join(5000)
        }
        if (childPid > 0) {
            ProcessHandle.of(childPid).ifPresent { handle -> handle.destroyForcibly() }
        }
    }

    @Requires({ OperatingSystem.current.windows })
    void "mn runs a Windows batch external command with quoted arguments"() {
        given:
        Path marker = bin.resolve("marker.txt")
        script("mn-hello.cmd", "@echo off\r\n>\"${marker}\" echo arg1=%~1 arg2=%~2\r\nexit /b 7\r\n")

        when:
        int exitCode = MicronautStarter.execute(["hello", "three four", ""] as String[], environment())

        then:
        exitCode == 7
        Files.readString(marker).trim() == "arg1=three four arg2="
    }

    @Requires({ OperatingSystem.current.windows })
    void "mn refuses shell metacharacters for a Windows batch external command"() {
        given:
        Path marker = bin.resolve("marker.txt")
        script("mn-hello.cmd", "@echo off\r\n>\"${marker}\" echo invoked\r\nexit /b 7\r\n")

        when:
        int exitCode = MicronautStarter.execute(["hello", argument] as String[], environment())

        then:
        exitCode == ExternalCommand.CANNOT_RUN
        !Files.exists(marker)

        where:
        argument << ["a&echo INJECTED", "%PATH%", "a|echo INJECTED", "a^b", "a\"b"]
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
