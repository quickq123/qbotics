# The real internet, through the Uplink (design doc 9.4): HTTPS requests, as the popular
# Python "requests" library makes them.
#
#     import requests
#     r = requests.get("https://api.github.com/repos/python/cpython")
#     print(r.status_code, r.json()["stargazers_count"])
#     requests.post("https://example.com/api", json={"ore": 64})
#
# A computer needs an Uplink on its cable network (a laptop has one built in). HTTPS only;
# private and local addresses are never reached. Answers are limited in size (a computer's
# memory is small) and each player's computers may make a limited number a minute.
import json as _json
import _host


class RequestException(Exception):
    """The request couldn't be made, or got no answer. The message says why."""


class HTTPError(RequestException):
    """raise_for_status() found a status of 400 or more."""


class Response:
    """An answer: status_code, ok, reason, headers (names in lower case), text, url; json()."""

    def __init__(self, info):
        self.status_code = info["status"]
        self.reason = info.get("reason", "")
        self.headers = info.get("headers", {})
        self.text = info.get("text", "")
        self.url = info.get("url", "")
        self.ok = self.status_code < 400

    @property
    def content(self):
        """The body as bytes."""
        return self.text.encode()

    def json(self):
        """The body read as JSON: a dict or a list."""
        return _json.loads(self.text)

    def raise_for_status(self):
        """Raises HTTPError if the status says something went wrong (400 or more)."""
        if not self.ok:
            raise HTTPError("%d %s for %s" % (self.status_code, self.reason, self.url))

    def __repr__(self):
        return "<Response [%d]>" % self.status_code


_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"


def _quote(value):
    out = []
    for b in str(value).encode():
        c = chr(b)
        out.append(c if c in _SAFE else "%%%02X" % b)
    return "".join(out)


def _encode(pairs):
    return "&".join(_quote(k) + "=" + _quote(v) for k, v in pairs.items())


def request(method, url, params=None, data=None, json=None, headers=None, timeout=None):
    """Makes a request and returns a Response. params are added to the address; data is a
    dict (sent as a form) or text; json is sent as JSON. timeout is kept by the server."""
    if params:
        url = url + ("&" if "?" in url else "?") + _encode(params)
    headers = dict(headers or {})
    body = None
    if json is not None:
        body = _json.dumps(json)
        headers.setdefault("Content-Type", "application/json")
    elif isinstance(data, dict):
        body = _encode(data)
        headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
    elif data is not None:
        body = data if isinstance(data, str) else bytes(data).decode()
    reply = _host.wait(_host.submit(_json.dumps(["http", method, url, headers, body])))
    if reply is None:
        raise RequestException("the request was interrupted")
    if not reply.startswith("{"):
        raise RequestException(reply)
    reply = _json.loads(reply)
    if "e" in reply:
        raise RequestException(reply["e"])
    return Response(reply["r"])


def get(url, params=None, **kwargs):
    """Asks for a page or an API's data: requests.get(url).json()."""
    return request("GET", url, params=params, **kwargs)


def post(url, data=None, json=None, **kwargs):
    """Sends data: requests.post(url, json={...})."""
    return request("POST", url, data=data, json=json, **kwargs)


def put(url, data=None, **kwargs):
    return request("PUT", url, data=data, **kwargs)


def patch(url, data=None, **kwargs):
    return request("PATCH", url, data=data, **kwargs)


def delete(url, **kwargs):
    return request("DELETE", url, **kwargs)


def head(url, **kwargs):
    """Only the status and headers."""
    return request("HEAD", url, **kwargs)
