package qbotics.devkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The devkit's check: mistakes found as the game finds them, and what programs need to run. */
class CheckTest {
    private static Check check;
    @TempDir
    Path folder;

    @BeforeAll
    static void engine() {
        check = new Check(System.out);
    }

    private String run(String name, String... lines) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n");
        return run(List.of(file));
    }

    private String run(List<Path> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        check.out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        check.reset();
        check.run(files, true);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void namedDevicesAndAQbot() throws IOException {
        String out = run("smelt.py",
                "import qbot",
                "from logistics import network, move",
                "FURNACE = \"oven\"",
                "net = network()",
                "store = net.bus(\"Store\")",
                "oven = net.bus(FURNACE)",
                "spare = net.interface()",
                "for name in [\"a\", \"b\"]:",
                "    net.fluid_bus(name)",
                "chests = net.storage(\"chest*\")",
                "far = net.remote(\"100.64.0.2\")",
                "far.interface(\"vault\")",
                "open(\"settings.json\").read()",
                "open(\"log.txt\", \"w\").write(\"x\")");
        assertTrue(out.contains("smelt.py:1:8: needs: to run on a Qbot"), out);
        assertTrue(out.contains("smelt.py:5:13: needs: on its network: an Item Serial Bus (or USB) named \"Store\""), out);
        assertTrue(out.contains("named \"oven\""), "a name set once at the top is read: " + out);
        assertTrue(out.contains("exactly one Interface (it's found with no name"), out);
        assertTrue(out.contains("a Fluid Serial Bus (or USB), by a name the program works out as it runs"), out);
        assertTrue(out.contains("Interfaces or Item Serial Buses matching \"chest*\""), out);
        assertTrue(out.contains("Transport Upgrade in it and in the one at 100.64.0.2"), out);
        assertTrue(out.contains("on the network behind 100.64.0.2: an Interface named \"vault\""), out);
        assertTrue(out.contains("the file \"settings.json\" on its drive"), out);
        assertTrue(!out.contains("log.txt"), "a file written isn't needed: " + out);
        assertTrue(out.contains("No mistakes found."), out);
        assertEquals(0, check.errors);
    }

    @Test
    void mistakesInTheGamesWords() throws IOException {
        String out = run("bad.py",
                "import time",
                "from logistics import network, mvoe",
                "from math import sqroot",
                "start = time.monotonic()",
                "print(cuont)");
        assertTrue(out.contains("bad.py:2:32: error: logistics has no mvoe. Did you mean move?"), out);
        assertTrue(out.contains("bad.py:3:18: error: math has no sqroot. Did you mean sqrt?"), out);
        assertTrue(out.contains("bad.py:4:14: error: time has no monotonic."), out);
        assertTrue(out.contains("It has: sleep, sleep_ms"), "a small module lists what it has: " + out);
        assertTrue(out.contains("bad.py:5:7: warning: cuont"), out);
        assertEquals(3, check.errors);
    }

    @Test
    void theEnginesOwnCompiler() throws IOException {
        // Good Python to the editor's checker, but not to the engine.
        String out = run("odd.py",
                "def f(x):",
                "    return [x for x in range(3) if x else 0]");
        assertTrue(out.contains("odd.py:2:5: error: Python can't read this line: invalid syntax."), out);
    }

    @Test
    void aProgramsOwnFiles() throws IOException {
        Files.writeString(folder.resolve("devices.py"), "from logistics import network\nnet = network()\ninbox = net.bus(\"inbox\")\n");
        String out = run("main.py",
                "from devices import inbox, net",
                "import helpers",
                "store = net.bus(\"store\")");
        assertTrue(out.contains("devices.py:3:13: needs: on its network: an Item Serial Bus (or USB) named \"inbox\""), out);
        assertTrue(out.contains("main.py:3:13: needs: on its network: an Item Serial Bus (or USB) named \"store\""), out);
        assertTrue(out.contains("main.py:2:8: needs: the module \"helpers\" on its drive"), out);
    }

    @Test
    void everyExampleIsClean() throws IOException {
        Path helpme = Path.of("../common/src/main/resources/qbotics/helpme");
        List<Path> files;
        try (var walk = Files.walk(helpme)) {
            files = walk.filter(p -> p.toString().endsWith(".py")).sorted().toList();
        }
        String out = run(files);
        assertEquals(0, check.errors + check.warnings, out);
        assertTrue(out.contains("an Item Serial Bus (or USB) named \"furnace\""), out);
    }
}
