package engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import qbotics.web.JsRuntime;

/**
 * Runs QuickJS's own tests (its tests/ folder, given as the argument) on the web panels'
 * engine, one test function at a time, and prints each result. Run it on two builds and
 * compare the output: the split interpreter (engine/js/split_interp.py) must give the same
 * results as QuickJS as it comes ({@code SPLIT=0 engine/js/build.sh}).
 *
 * <p>The tests' imports are left out: assert.js is put in front instead, and tests that need
 * QuickJS's os or std modules fail the same way on both builds.
 *
 * <p>Run: {@code ./gradlew -q jstest -Ptests=<quickjs>/tests}
 */
public final class JsTest {
    private JsTest() {
    }

    private static final String[] FILES = {"test_language.js", "test_closure.js", "test_loop.js", "test_bigint.js", "test_builtin.js"};

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String assertJs = Files.readString(dir.resolve("assert.js")).replaceAll("(?m)^export\\s+", "");
        int passed = 0;
        int failed = 0;
        for (String file : FILES) {
            List<String> calls = new ArrayList<>();
            StringBuilder code = new StringBuilder(assertJs).append('\n');
            for (String line : Files.readAllLines(dir.resolve(file))) {
                if (line.startsWith("import ")) {
                    continue;
                }
                if (line.matches("test\\w*\\(\\);\\s*")) {
                    calls.add(line.strip());
                    continue;
                }
                code.append(line).append('\n');
            }
            JsRuntime js = new JsRuntime(64 << 20, (op, text) -> null);
            JsRuntime.Result loaded = js.eval(code.toString(), file, 0);
            if (!loaded.ok()) {
                System.out.println(file + " (loading): FAIL " + firstLine(loaded.text()));
                failed++;
                continue;
            }
            for (String call : calls) {
                JsRuntime.Result result = js.eval(call, file, 0);
                System.out.println(file + " " + call + " " + (result.ok() ? "ok" : "FAIL " + firstLine(result.text())));
                if (result.ok()) {
                    passed++;
                } else {
                    failed++;
                }
            }
        }
        System.out.println("passed " + passed + ", failed " + failed);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
