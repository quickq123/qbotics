# Copies the factory to /factory, to make it your own: git pull in /helpme never touches it.
# Run it:  run copy.sh
mkdir -p /factory
cp main.py /factory/
cp devices.py /factory/
cp safely.py /factory/
cp sorter.py /factory/
cp smelter.py /factory/
cp report.py /factory/
cp README.md /factory/
echo Copied. Run it with:  run /factory/main.py
