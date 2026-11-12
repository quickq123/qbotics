# A shell script: commands, one a line, run as if you typed them. Lines starting with # are
# notes, like this one. It runs from its own folder; the first command that fails stops it.
# Run it:  run setup.sh   (or  sh setup.sh)
#
# Copy it to /startup.sh and it runs every time this computer starts:  cp setup.sh /startup.sh

echo Getting things ready...
mkdir -p /projects
echo Here is what this computer has:
ls /
echo And the newest examples:
git pull
echo Done. Try:  run hello.py
