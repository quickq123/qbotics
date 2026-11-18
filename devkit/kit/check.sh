#!/bin/sh
# Checks Qbotics programs without the game: will they compile, and what do they need to run?
#   ./check.sh programs/mine.py     one program
#   ./check.sh .                    every program in this folder
# It needs Java 25 or newer. Minecraft's launcher brings one: it is looked for there too.
kit=$(cd "$(dirname "$0")" && pwd)

good() {
    [ -x "$1" ] && "$1" -version 2>&1 | grep -Eq 'version "(2[5-9]|[3-9][0-9])'
}

java=""
for candidate in "$QBOTICS_JAVA" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" \
        "$HOME"/.minecraft/runtime/*/*/*/bin/java \
        "$HOME/Library/Application Support/minecraft/runtime"/*/*/*/jre.bundle/Contents/Home/bin/java \
        "$HOME"/.local/share/PrismLauncher/java/*/bin/java; do
    if [ -n "$candidate" ] && good "$candidate"; then
        java=$candidate
        break
    fi
done
if [ -z "$java" ]; then
    echo "The check needs Java 25 or newer, and none was found."
    echo "Start Minecraft 26.1 once from its launcher (it brings its own Java), or install one"
    echo "from https://adoptium.net, or set QBOTICS_JAVA to the java to use."
    exit 2
fi
exec "$java" -jar "$kit/.qbotics/qcheck.jar" "$@"
