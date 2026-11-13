package engine;

import qbotics.web.JsRuntime;

/**
 * How fast the web panels' JavaScript engine runs (design doc §10.3): QuickJS on Chicory.
 * Each script runs several times; the best time is reported, after the JVM has warmed up.
 * Also checks a few answers, so a faster engine that gets things wrong is caught.
 *
 * <p>Run: {@code ./gradlew -q jsbench}
 */
public final class JsBench {
    private JsBench() {
    }

    private record Script(String name, String code, String expect) {
    }

    private static final Script[] SCRIPTS = {
        new Script("loop", "let s = 0;\nfor (let i = 0; i < 1000000; i++) { s += i & 7; }\ns", "3500000"),
        new Script("fib", "function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }\nfib(24)", "46368"),
        new Script("objects", String.join("\n",
                "const list = [];",
                "for (let i = 0; i < 40000; i++) { list.push({ id: i, name: 'item' + i, tags: [i % 3, i % 5] }); }",
                "const m = new Map();",
                "for (const o of list) { m.set(o.name, (m.get(o.name) || 0) + o.tags[0]); }",
                "list.filter(o => o.tags[1] === 0).map(o => o.id).reduce((a, b) => a + b, 0)"), "159980000"),
        new Script("strings", String.join("\n",
                "let out = [];",
                "for (let i = 0; i < 20000; i++) { out.push(('row ' + i).padStart(10, '.').toUpperCase()); }",
                "out.join(',').split(',').filter(s => s.includes('9')).length"), "6878"),
        new Script("math", String.join("\n",
                "let a = 0;",
                "for (let i = 0; i < 200000; i++) { a += Math.sin(i * 0.01) * Math.cos(i * 0.02) + Math.sqrt(i); }",
                "Math.round(a)"), "59628189"),
        new Script("json", String.join("\n",
                "const data = { rows: [] };",
                "for (let i = 0; i < 5000; i++) { data.rows.push({ i, v: [1, 2, 3], s: 'x' + i }); }",
                "let n = 0;",
                "for (let k = 0; k < 5; k++) { n += JSON.parse(JSON.stringify(data)).rows.length; }",
                "n"), "25000"),
    };

    public static void main(String[] args) {
        long start = System.nanoTime();
        JsRuntime js = new JsRuntime(64 << 20, (op, text) -> null);
        System.out.printf("engine ready in %d ms%n", (System.nanoTime() - start) / 1_000_000);
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        boolean ok = true;
        for (Script script : SCRIPTS) {
            long best = Long.MAX_VALUE;
            long first = 0;
            String answer = "";
            for (int round = 0; round < rounds; round++) {
                long t = System.nanoTime();
                JsRuntime.Result result = js.eval("(() => { " + script.code().replaceFirst("(?s)(.*\n|^)([^\n]*)$", "$1return $2") + " })()", script.name(), 0);
                long took = System.nanoTime() - t;
                if (round == 0) {
                    first = took;
                }
                best = Math.min(best, took);
                answer = result.ok() ? result.text() : "error: " + result.text();
            }
            boolean right = answer.equals(script.expect());
            ok &= right;
            System.out.printf("%-8s best %6.1f ms   first %7.1f ms   %s%n", script.name(), best / 1e6, first / 1e6,
                    right ? "ok" : "WRONG: " + answer + " (expected " + script.expect() + ")");
        }
        System.exit(ok ? 0 : 1);
    }
}
