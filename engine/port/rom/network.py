# Networking between computers (design doc §9.3).
#
#     import network
#     network.send("192.168.1.3", {"ore": 128})        # a computer on the same cables
#     network.send("100.64.0.12:1003", "hello")        # one behind another antenna
#     msg = network.receive()                          # waits for the next message
#     print(msg.sender, msg.data)
import json
import time
import _host
from logistics import _call, NetworkError


def ifconfig():
    """This computer's addresses. gateway, external_ip and port are None
    until an antenna (the network's router) is cabled in."""
    me = _call("self")
    return {"ip": me["ip"], "mac": me["mac"], "name": me["name"], "gateway": me.get("gateway"),
            "external_ip": me.get("external_ip"), "port": me.get("port")}


def hostname(name=None):
    """This computer's name on the network; give a name to change it."""
    me = _call("self")
    if name is not None:
        me = _call("rename", me["mac"], name)
    return me["name"]


class Message:
    """A received message. Reply with network.send(msg.sender, ...)."""

    def __init__(self, sender, data):
        self.sender = sender    # the address to answer to
        self.data = data

    def __repr__(self):
        return "<message from %s: %r>" % (self.sender, self.data)


def send(address, data):
    """Sends data (text, numbers, lists, dicts) to another computer.

    The address is a name, internal IP or hardware address on this cable network,
    or "external IP:port" for a computer behind another antenna. Returns True if
    it was delivered, False if that computer is switched off or its inbox is full.
    A wrong or unreachable address raises NetworkError."""
    return _call("send", address, json.dumps(data))


# The one outstanding request for a message. It is kept between calls, so a
# message that arrives after a receive() timed out is not lost.
_waiting = None


def receive(timeout=None):
    """Waits for the next message and returns it. With a timeout (in seconds),
    returns None if nothing arrives in that time."""
    global _waiting
    start = time.ticks_ms()
    limit = None if timeout is None else int(timeout * 1000)
    if _waiting is None:
        _waiting = _host.submit('["recv"]')
    while True:
        step = 250
        if limit is not None:
            step = min(step, limit - time.ticks_diff(time.ticks_ms(), start))
            if step <= 0:
                step = 0
        reply = _host.wait(_waiting, step)
        if reply is not None:
            break
        if step == 0:
            return None
    _waiting = None
    if not reply.startswith("{"):
        raise NetworkError(reply)
    reply = json.loads(reply)["r"]
    return Message(reply["sender"], json.loads(reply["data"]))


class Radio:
    """The antenna cabled to this computer: the network's router."""

    def __init__(self):
        self._info = _call("radio")

    def _get(self, key):
        self._info = _call("radio")
        return self._info[key]

    @property
    def external_ip(self):
        """The address other networks reach this one by, e.g. "100.64.0.7"."""
        return self._get("external_ip")

    @property
    def frequency(self):
        return self._get("frequency")

    @property
    def tier(self):
        """ "short", "long" or "infinite"."""
        return self._info["tier"]

    @property
    def range(self):
        """Reach in blocks; None for an infinite antenna."""
        r = self._info["range"]
        return None if r < 0 else r

    def tune(self, frequency):
        """Only antennas on the same frequency hear each other."""
        self._info = _call("tune", str(frequency))

    def send(self, address, data):
        return send(address, data)

    def receive(self, timeout=None):
        return receive(timeout)

    def __repr__(self):
        return "<antenna %s frequency %s>" % (self._info["external_ip"], self._info["frequency"])
