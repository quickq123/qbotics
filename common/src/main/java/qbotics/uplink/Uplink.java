package qbotics.uplink;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsConfig;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;

/**
 * The uplink (design doc §9.4, §13): HTTPS requests from computers to the real internet,
 * for computers on a cable network with an Uplink block, and for laptops (their built-in
 * Infinite antenna counts as one).
 *
 * <p>Guarded against server-side request forgery (a program using the server to reach what it
 * shouldn't): every address a name resolves to is checked, and refused if private, loopback,
 * link-local (cloud metadata), shared, multicast or reserved; the connection then goes to the
 * very address that was checked, so a second lookup can't swap in another (DNS rebinding). The
 * same goes for each redirect. HTTPS only, with the certificate checked against the name.
 *
 * <p>Requests run on a small pool of their own threads, never the server's. The admin sets
 * whether it works at all, which domains it may reach, how large an answer may be, how long a
 * request may take and how many a player may make a minute. Each request is logged with its
 * owner (the host and path only: a query may hold someone's key).
 */
public final class Uplink {
    private Uplink() {
    }

    /** At most this many requests at once, server-wide. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "qbotics-uplink");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_SENT = 64 * 1024;
    /** What the uplink says it is to the sites it asks. Nothing about the player or the server. */
    private static final String USER_AGENT = "Qbotics-Uplink/0.1 (a Minecraft mod)";

    /** The request times of each player in the last minute, for the rate limit. */
    private static final Map<UUID, Deque<Long>> RECENT = new HashMap<>();

    /** A refusal or failure, in words for the player's program. */
    static final class UplinkError extends Exception {
        UplinkError(String message) {
            super(message, null, false, false);
        }
    }

    static String error(String message) {
        JsonObject reply = new JsonObject();
        reply.addProperty("e", message);
        return reply.toString();
    }

    // ---- who may ask (on the server thread) --------------------------------------------------

    /** True if this computer's cable network has an Uplink. On the server thread. */
    static boolean hasUplink(ServerLevel level, BlockPos computerPos) {
        if (level.getBlockEntity(computerPos) instanceof qbotics.block.ServerBlockEntity) {
            return true; // a Server has its own
        }
        for (NetworkDevice device : CableNetwork.at(level, computerPos).devices()) {
            if (device.kind() == NetworkDevice.Kind.UPLINK) {
                return true;
            }
        }
        return false;
    }

    /**
     * A request from a computer: checked here, on the server thread (an uplink on its network,
     * an owner, the rate), then made on the uplink's own threads; {@code answer} is completed
     * with the reply.
     */
    public static void fromComputer(MinecraftServer server, ResourceKey<Level> dimension, BlockPos computerPos, String call,
            int heapBytes, CompletableFuture<String> answer) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.isLoaded(computerPos)) {
            answer.complete(error("This computer's block isn't loaded."));
            return;
        }
        if (!hasUplink(level, computerPos)) {
            answer.complete(error("Reaching the internet needs an Uplink on this computer's cable network."));
            return;
        }
        UUID owner = level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity computer ? computer.owner() : null;
        go(owner, "computer at " + computerPos.toShortString(), call, answerLimit(heapBytes), answer);
    }

    /** A request from a player's laptop: its built-in Infinite antenna is its uplink. */
    public static void fromLaptop(UUID player, String call, int heapBytes, CompletableFuture<String> answer) {
        go(player, "laptop", call, answerLimit(heapBytes), answer);
    }

    /**
     * The largest answer a computer is given: {@code max_response_kb} for a computer's 256 KB,
     * more for more memory (a laptop four times, a Server eight). Reading an answer takes room
     * for two or three copies of it, so it scales with what the program has.
     */
    public static int answerLimit(int heapBytes) {
        long scaled = (long) QboticsConfig.uplinkMaxResponseKb * 1024 * heapBytes / qbotics.computer.ServerComputer.BASIC_HEAP_BYTES;
        return (int) Math.max(1024, Math.min(scaled, 4L * 1024 * 1024));
    }

    private static void go(@Nullable UUID owner, String from, String call, int limit, CompletableFuture<String> answer) {
        go(owner, from, answer, () -> handle(owner, from, call, limit));
    }

    /** Checks the uplink may be used now (on, owned, within the rate), then runs the work on its own threads. */
    static void go(@Nullable UUID owner, String from, CompletableFuture<String> answer, java.util.function.Supplier<String> work) {
        if (!QboticsConfig.uplinkEnabled) {
            answer.complete(error("The uplink is switched off on this server."));
            return;
        }
        if (owner == null) {
            answer.complete(error("Only a computer someone owns can reach the internet."));
            return;
        }
        if (!allowedNow(owner)) {
            answer.complete(error("Too many requests: a player may make " + QboticsConfig.uplinkRequestsPerMinute + " a minute."));
            return;
        }
        POOL.execute(() -> {
            try {
                answer.complete(work.get());
            } catch (RuntimeException e) {
                Qbotics.LOGGER.warn("Uplink request failed", e);
                answer.complete(error("The request failed: " + e.getClass().getSimpleName() + "."));
            }
        });
    }

    private static synchronized boolean allowedNow(UUID owner) {
        long now = System.currentTimeMillis();
        Deque<Long> times = RECENT.computeIfAbsent(owner, o -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
            times.pollFirst();
        }
        if (times.size() >= QboticsConfig.uplinkRequestsPerMinute) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    // ---- the request itself (on the uplink's threads) ------------------------------------------

    /** ["http", method, url, headers {name: value} or null, body or null]. */
    static String handle(UUID owner, String from, String call, int limit) {
        try {
            JsonArray args = JsonParser.parseString(call).getAsJsonArray();
            String method = args.get(1).getAsString().toUpperCase(Locale.ROOT);
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) {
                throw new UplinkError("'" + method + "' isn't a request method the uplink makes.");
            }
            String url = args.get(2).getAsString();
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            if (args.size() > 3 && args.get(3).isJsonObject()) {
                for (Map.Entry<String, JsonElement> header : args.get(3).getAsJsonObject().entrySet()) {
                    headers.put(header.getKey(), header.getValue().getAsString());
                }
            }
            byte[] body = args.size() > 4 && !args.get(4).isJsonNull() ? args.get(4).getAsString().getBytes(StandardCharsets.UTF_8) : null;
            if (body != null && body.length > MAX_BODY_SENT) {
                throw new UplinkError("That's too much to send: the most is " + MAX_BODY_SENT / 1024 + " KB.");
            }
            return fetch(owner, from, method, url, headers, body, limit);
        } catch (UplinkError e) {
            return error(e.getMessage());
        } catch (IOException e) {
            return error("The request failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (IllegalStateException | IndexOutOfBoundsException | UnsupportedOperationException e) {
            return error("A request is: requests.get(url) (or post, put, patch, delete, head).");
        }
    }

    /** Makes the request, following redirects, and returns the reply as the program gets it. */
    static String fetch(UUID owner, String from, String method, String url, Map<String, String> headers, byte @Nullable [] body, int limit)
            throws UplinkError, IOException {
        Reply answer = download(owner, from, method, url, headers, body, limit);
        URI uri = URI.create(answer.url());
        {
            JsonObject reply = new JsonObject();
            reply.addProperty("status", answer.status());
            reply.addProperty("reason", answer.reason());
            reply.addProperty("url", uri.toString());
            JsonObject heads = new JsonObject();
            answer.headers().forEach(heads::addProperty);
            reply.add("headers", heads);
            reply.addProperty("text", new String(answer.body(), StandardCharsets.UTF_8));
            JsonObject result = new JsonObject();
            result.add("r", reply);
            return result.toString();
        }
    }

    /** An answer, after redirects: its status, headers (names in lower case), body, and final address. */
    public record Reply(int status, String reason, Map<String, String> headers, byte[] body, String url) {
    }

    /**
     * Makes a request with all the uplink's checks, following redirects, and returns the answer
     * with its body (at most {@code limit} bytes). For the uplink's own services (git), which
     * handle more than a program may take at once. Not on the server thread.
     */
    static Reply download(UUID owner, String from, String method, String url, Map<String, String> headers, byte @Nullable [] body, int limit)
            throws UplinkError, IOException {
        URI uri = parse(url);
        for (int hop = 0; ; hop++) {
            Answer answer = once(owner, from, method, uri, headers, body, limit);
            if (answer.status / 100 == 3 && answer.headers.containsKey("location") && hop < MAX_REDIRECTS) {
                uri = parse(uri.resolve(answer.headers.get("location")).toString());
                if (answer.status == 303 || ((answer.status == 301 || answer.status == 302) && method.equals("POST"))) {
                    method = "GET";
                    body = null;
                }
                continue;
            }
            return new Reply(answer.status, answer.reason, answer.headers, answer.body, uri.toString());
        }
    }

    private static URI parse(String url) throws UplinkError {
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new UplinkError("'" + url + "' isn't a web address.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UplinkError("The uplink speaks HTTPS only: the address must start with https://");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new UplinkError("'" + url + "' has no site in it.");
        }
        if (uri.getUserInfo() != null) {
            throw new UplinkError("Addresses with a name and password in them aren't taken; send it as a header.");
        }
        checkDomain(uri.getHost().toLowerCase(Locale.ROOT));
        return uri;
    }

    /** The admin's lists: a domain is a name or anything under it ("github.com" covers "api.github.com"). */
    private static void checkDomain(String host) throws UplinkError {
        for (String denied : QboticsConfig.uplinkDenyDomains) {
            if (covers(denied, host)) {
                throw new UplinkError(host + " is blocked on this server.");
            }
        }
        List<String> allowed = QboticsConfig.uplinkAllowDomains;
        if (!allowed.isEmpty() && allowed.stream().noneMatch(domain -> covers(domain, host))) {
            throw new UplinkError(host + " isn't on this server's list of sites the uplink may reach.");
        }
    }

    private static boolean covers(String domain, String host) {
        String d = domain.strip().toLowerCase(Locale.ROOT);
        return !d.isEmpty() && (host.equals(d) || host.endsWith("." + d));
    }

    /**
     * True for an address no request may reach: this machine, private networks, link-local
     * (where cloud metadata services sit), shared (carrier-grade NAT), multicast and the
     * reserved ranges. IPv6 addresses that carry an IPv4 one are judged by it.
     */
    static boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return true; // fc00::/7, unique local
            }
            boolean v4mapped = true;
            for (int i = 0; i < 10; i++) {
                v4mapped &= b[i] == 0;
            }
            if (v4mapped && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || (b[10] == 0 && b[11] == 0))) {
                try {
                    return forbidden(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return true;
                }
            }
            return false;
        }
        if (address instanceof Inet4Address) {
            int a = b[0] & 0xff;
            int c = b[1] & 0xff;
            return a == 0                                  // 0.0.0.0/8
                    || (a == 100 && c >= 64 && c < 128)    // 100.64.0.0/10, shared
                    || (a == 192 && c == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                    || (a == 198 && (c == 18 || c == 19))  // 198.18.0.0/15, benchmarking
                    || a >= 240;                           // 240.0.0.0/4 and broadcast
        }
        return true;
    }

    private record Answer(int status, String reason, Map<String, String> headers, byte[] body) {
    }

    private static Answer once(UUID owner, String from, String method, URI uri, Map<String, String> headers, byte @Nullable [] body, int limit)
            throws UplinkError, IOException {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        // Every address the name has must be one we may reach; then we connect to that very one.
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (forbidden(address)) {
                throw new UplinkError(host + " leads to a private or reserved address, which the uplink doesn't reach.");
            }
        }
        InetAddress target = addresses[0];
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        Qbotics.LOGGER.info("Uplink: {} ({}) {} https://{}{}", owner, from, method, host, uri.getRawPath() == null ? "/" : uri.getRawPath());
        int timeout = QboticsConfig.uplinkTimeoutSeconds * 1000;
        long deadline = System.currentTimeMillis() + timeout;
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(target, port), timeout);
            plain.setSoTimeout(timeout);
            SSLSocket socket;
            try {
                socket = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket(plain, host, port, true);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("no TLS here", e);
            }
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must be for this name
            if (!isLiteral(host)) {
                parameters.setServerNames(List.of(new SNIHostName(host)));
            }
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append(port == 443 ? "" : ":" + port).append("\r\n");
            request.append("User-Agent: ").append(USER_AGENT).append("\r\n");
            request.append("Accept-Encoding: identity\r\nConnection: close\r\n");
            boolean accept = false;
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey().strip();
                String value = header.getValue();
                if (name.isEmpty() || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new UplinkError("'" + name + "' isn't a header that can be sent.");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (List.of("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "user-agent").contains(lower)) {
                    continue; // the uplink sets these itself
                }
                accept |= lower.equals("accept");
                request.append(name).append(": ").append(value).append("\r\n");
            }
            if (!accept) {
                request.append("Accept: */*\r\n");
            }
            if (body != null) {
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return read(socket.getInputStream(), method.equals("HEAD"), deadline, limit);
        } catch (java.net.SocketTimeoutException e) {
            throw new UplinkError(host + " didn't answer in " + QboticsConfig.uplinkTimeoutSeconds + " seconds.");
        } catch (javax.net.ssl.SSLException e) {
            throw new UplinkError("The secure connection to " + host + " failed: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            throw new UplinkError("No site is called " + host + ".");
        }
    }

    private static boolean isLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    /** Reads a reply: the status line, the headers, then the body (whole, chunked, or to the end). */
    private static Answer read(InputStream in, boolean head, long deadline, int limit) throws IOException, UplinkError {
        String status = line(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new UplinkError("The site's answer wasn't HTTP.");
        }
        int code = Integer.parseInt(parts[1]);
        String reason = parts.length > 2 ? parts[2] : "";
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        int headerBytes = 0;
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new UplinkError("The site's answer had too many headers.");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).strip();
                headers.merge(name, value, (a, b) -> a + ", " + b);
            }
        }
        if (head || code == 204 || code == 304 || code / 100 == 1) {
            return new Answer(code, reason, headers, new byte[0]);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))) {
            while (true) {
                String size = line(in);
                int semicolon = size.indexOf(';');
                int length = Integer.parseInt((semicolon >= 0 ? size.substring(0, semicolon) : size).strip(), 16);
                if (length == 0) {
                    break;
                }
                copy(in, body, length, limit, deadline);
                line(in); // the chunk's own line end
            }
        } else if (headers.containsKey("content-length")) {
            long length = Long.parseLong(headers.get("content-length"));
            if (length > limit) {
                throw tooLarge(limit);
            }
            copy(in, body, (int) length, limit, deadline);
        } else {
            copy(in, body, Integer.MAX_VALUE, limit, deadline);
        }
        return new Answer(code, reason, headers, body.toByteArray());
    }

    private static UplinkError tooLarge(int limit) {
        return new UplinkError("The site's answer is larger than this computer may take (" + limit / 1024 + " KB; a laptop takes 4 times a computer's, a Server 8).");
    }

    private static void copy(InputStream in, ByteArrayOutputStream body, int length, int limit, long deadline)
            throws IOException, UplinkError {
        byte[] buffer = new byte[8192];
        int left = length;
        while (left > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new UplinkError("The site took too long to answer.");
            }
            int n = in.read(buffer, 0, Math.min(buffer.length, left));
            if (n < 0) {
                if (length == Integer.MAX_VALUE) {
                    return; // to the end of the connection
                }
                throw new UplinkError("The site's answer stopped part-way.");
            }
            body.write(buffer, 0, n);
            if (body.size() > limit) {
                throw tooLarge(limit);
            }
            left -= n;
        }
    }

    /** One line of the reply's head, without its line end. */
    private static String line(InputStream in) throws IOException, UplinkError {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 8192) {
                throw new UplinkError("The site's answer had a line too long.");
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }
}
