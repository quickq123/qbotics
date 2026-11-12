# The real internet. With an Uplink on this computer's cable network (a laptop has one
# built in), programs can ask websites for data with requests.
# Run it:  run zen.py
import requests

r = requests.get("https://api.github.com/zen")
print("GitHub says:", r.text)

r = requests.get("https://api.github.com/repos/python/cpython")
info = r.json()
print("Python's own repository has", info["stargazers_count"], "stars.")

# git works through the Uplink too. At the shell:
#   git clone https://github.com/<someone>/<their-repo>
# copies a GitHub repository onto this computer, the same way this helpme folder came.
# git status, git pull and git push work in it (pushing needs your token: /qbotics git token).
