# This computer's place on its network (design doc §9.3). Radio arrives with the antenna.
from logistics import _call


def ifconfig():
    """This computer's addresses. The gateway and external address are None
    until an antenna (the network's router) is attached."""
    me = _call("self")
    return {"ip": me["ip"], "mac": me["mac"], "name": me["name"], "gateway": None, "external_ip": None}


def hostname(name=None):
    """This computer's name on the network; give a name to change it."""
    me = _call("self")
    if name is not None:
        me = _call("rename", me["mac"], name)
    return me["name"]
