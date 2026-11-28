import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

final class DrvJava {
    private static final String NIX_B32 = "0123456789abcdfghijklmnpqrsvwxyz";
    private static final long CLIENT_MAGIC = 0x6e697863L;
    private static final long SERVER_MAGIC = 0x6478696fL;
    private static final long PROTOCOL_1_21 = (1L << 8) | 21L;
    private static final long STDERR_NEXT = 0x6f6c6d67L;
    private static final long STDERR_LAST = 0x616c7473L;
    private static final long STDERR_ERROR = 0x63787470L;
    private static final long WOP_ADD_TEXT_TO_STORE = 8L;
    private static final long WOP_QUERY_VALID_PATHS = 31L;
    private static final FileTime FIXED_TIME = FileTime.from(Instant.parse("1980-01-01T00:00:02Z"));

    // --- Minimal strict JSON parser & serializer ---

    static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s;
        }

        Object parse() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (pos != s.length()) throw new RuntimeException("trailing JSON content at " + pos);
            return v;
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new RuntimeException("unexpected EOF in JSON");
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw new RuntimeException("unexpected char '" + c + "' at " + pos);
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new RuntimeException("expected " + lit + " at " + pos);
            pos += lit.length();
        }

        private Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw new RuntimeException("expected object key at " + pos);
                String k = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new RuntimeException("expected ':' at " + pos);
                pos++;
                Object v = parseValue();
                map.put(k, v);
                skipWs();
                if (pos >= s.length()) throw new RuntimeException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new RuntimeException("expected ',' or '}' at " + (pos - 1));
            }
        }

        private List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (pos >= s.length()) throw new RuntimeException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new RuntimeException("expected ',' or ']' at " + (pos - 1));
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new RuntimeException("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default: throw new RuntimeException("invalid escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String num = s.substring(start, pos);
            return isFloat ? Double.valueOf(num) : Long.valueOf(num);
        }
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Boolean) return obj.toString();
        if (obj instanceof Number) return obj.toString();
        if (obj instanceof String) return jsonStr((String) obj);
        if (obj instanceof List<?>) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(item));
            }
            return sb.append(']').toString();
        }
        if (obj instanceof Map<?, ?>) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStr(String.valueOf(e.getKey()))).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        throw new RuntimeException("unsupported JSON type: " + obj.getClass());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static List<String> asStringList(Object o) {
        if (o == null) return Collections.emptyList();
        List<Object> raw = asList(o);
        List<String> out = new ArrayList<>(raw.size());
        for (Object x : raw) out.add((String) x);
        return out;
    }

    // --- Nix store paths, ATerm formatting, and protocol-1.21 daemon client ---

    private static String storeDir() {
        String d = System.getenv("NIX_STORE");
        if (d == null || !d.startsWith("/")) return "/nix/store";
        while (d.length() > 1 && d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String nixBase32(byte[] data) {
        int outLen = (data.length * 8 - 1) / 5 + 1;
        StringBuilder out = new StringBuilder(outLen);
        for (int n = 0; n < outLen; n++) {
            int b = (outLen - 1 - n) * 5;
            int i = b / 8;
            int j = b % 8;
            int c = (data[i] & 0xff) >>> j;
            if (j > 0 && i + 1 < data.length) {
                c |= (data[i + 1] & 0xff) << (8 - j);
            }
            out.append(NIX_B32.charAt(c & 0x1f));
        }
        return out.toString();
    }

    private static byte[] compressHash(byte[] digest, int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < digest.length; i++) out[i % size] ^= digest[i];
        return out;
    }

    private static String makeStorePath(String sdir, String type, String sha256Hex, String name) {
        String fp = type + ":sha256:" + sha256Hex + ":" + sdir + ":" + name;
        String h = nixBase32(compressHash(sha256(fp.getBytes(StandardCharsets.UTF_8)), 20));
        return sdir + "/" + h + "-" + name;
    }

    private static String makeTextPath(String sdir, String name, byte[] text, Collection<String> refs) {
        String digest = toHex(sha256(text));
        TreeSet<String> sorted = new TreeSet<>(refs);
        StringBuilder typ = new StringBuilder("text");
        for (String r : sorted) typ.append(':').append(r);
        return makeStorePath(sdir, typ.toString(), digest, name);
    }

    private static String makeFixedOutputPath(String sdir, String name, String sha256Hex) {
        String inner = toHex(sha256(("fixed:out:sha256:" + sha256Hex + ":").getBytes(StandardCharsets.UTF_8)));
        return makeStorePath(sdir, "output:out", inner, name);
    }

    private static String hashPlaceholder(String outputName) {
        return "/" + nixBase32(sha256(("nix-output:" + outputName).getBytes(StandardCharsets.UTF_8)));
    }

    private static String atermStr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static byte[] formatDerivation(
        List<String[]> outputs,
        Map<String, List<String>> inputDrvs,
        Set<String> inputSrcs,
        String platform,
        String builder,
        List<String> args,
        Map<String, String> env
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Derive([");
        List<String[]> sortedOuts = new ArrayList<>(outputs);
        sortedOuts.sort(Comparator.comparing(a -> a[0]));
        for (int i = 0; i < sortedOuts.size(); i++) {
            if (i > 0) sb.append(',');
            String[] o = sortedOuts.get(i);
            sb.append('(').append(atermStr(o[0])).append(',').append(atermStr(o[1]))
              .append(',').append(atermStr(o[2])).append(',').append(atermStr(o[3])).append(')');
        }
        sb.append("],[");
        TreeMap<String, List<String>> sortedDrvs = new TreeMap<>(inputDrvs);
        boolean first = true;
        for (Map.Entry<String, List<String>> e : sortedDrvs.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('(').append(atermStr(e.getKey())).append(",[");
            TreeSet<String> os = new TreeSet<>(e.getValue());
            boolean fo = true;
            for (String o : os) {
                if (!fo) sb.append(',');
                fo = false;
                sb.append(atermStr(o));
            }
            sb.append("])");
        }
        sb.append("],[");
        TreeSet<String> sortedSrcs = new TreeSet<>(inputSrcs);
        first = true;
        for (String src : sortedSrcs) {
            if (!first) sb.append(',');
            first = false;
            sb.append(atermStr(src));
        }
        sb.append("],").append(atermStr(platform)).append(',').append(atermStr(builder)).append(",[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(atermStr(args.get(i)));
        }
        sb.append("],[");
        TreeMap<String, String> sortedEnv = new TreeMap<>(env);
        first = true;
        for (Map.Entry<String, String> e : sortedEnv.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('(').append(atermStr(e.getKey())).append(',').append(atermStr(e.getValue())).append(')');
        }
        sb.append("])");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static final class Node {
        final String drvPath;
        final String outPath;
        final String drvName;
        final byte[] aterm;
        final List<String> refs;

        Node(String drvPath, String outPath, String drvName, byte[] aterm, List<String> refs) {
            this.drvPath = drvPath;
            this.outPath = outPath;
            this.drvName = drvName;
            this.aterm = aterm;
            this.refs = refs;
        }
    }

    private static Node makeFetchurlDrv(String sdir, String name, List<String> urls, String sha256Hex) {
        String outPath = makeFixedOutputPath(sdir, name, sha256Hex);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("builder", "builtin:fetchurl");
        env.put("executable", "");
        env.put("impureEnvVars", "http_proxy https_proxy ftp_proxy all_proxy no_proxy");
        env.put("name", name);
        env.put("out", outPath);
        env.put("outputHash", sha256Hex);
        env.put("outputHashAlgo", "sha256");
        env.put("outputHashMode", "flat");
        env.put("preferLocalBuild", "1");
        env.put("system", "builtin");
        env.put("unpack", "");
        env.put("url", urls.get(0));
        env.put("urls", String.join(" ", urls));
        List<String[]> outputs = Collections.singletonList(new String[]{"out", outPath, "sha256", sha256Hex});
        byte[] aterm = formatDerivation(outputs, Collections.emptyMap(), Collections.emptySet(),
            "builtin", "builtin:fetchurl", Collections.emptyList(), env);
        String drvName = name + ".drv";
        String drvPath = makeTextPath(sdir, drvName, aterm, Collections.emptyList());
        return new Node(drvPath, outPath, drvName, aterm, Collections.emptyList());
    }

    static final class DaemonClient implements Closeable {
        private final SocketChannel ch;
        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

        DaemonClient(String remote) throws IOException {
            String sockPath;
            if (remote != null && remote.startsWith("unix://")) {
                String rest = remote.substring("unix://".length());
                int q = rest.indexOf('?');
                sockPath = q >= 0 ? rest.substring(0, q) : rest;
            } else if ("daemon".equals(remote)) {
                sockPath = "/nix/var/nix/daemon-socket/socket";
            } else {
                throw new RuntimeException("unsupported NIX_REMOTE=" + remote);
            }
            this.ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            this.ch.connect(UnixDomainSocketAddress.of(sockPath));
            handshake();
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }

        private void sendU64(long v) {
            byte[] b = new byte[8];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(v);
            outBuf.write(b, 0, 8);
        }

        private void sendBytes(byte[] b) {
            sendU64(b.length);
            outBuf.write(b, 0, b.length);
            int pad = (8 - (b.length % 8)) % 8;
            for (int i = 0; i < pad; i++) outBuf.write(0);
        }

        private void sendStr(String s) {
            sendBytes(s.getBytes(StandardCharsets.UTF_8));
        }

        private void sendStrSet(List<String> items) {
            sendU64(items.size());
            for (String s : items) sendStr(s);
        }

        private void flush() throws IOException {
            byte[] data = outBuf.toByteArray();
            outBuf.reset();
            ByteBuffer bb = ByteBuffer.wrap(data);
            while (bb.hasRemaining()) ch.write(bb);
        }

        private byte[] recvExact(int n) throws IOException {
            byte[] data = new byte[n];
            ByteBuffer bb = ByteBuffer.wrap(data);
            while (bb.hasRemaining()) {
                int r = ch.read(bb);
                if (r < 0) throw new EOFException("unexpected EOF from nix daemon");
            }
            return data;
        }

        private long recvU64() throws IOException {
            return ByteBuffer.wrap(recvExact(8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }

        private byte[] recvBytes() throws IOException {
            int n = (int) recvU64();
            byte[] data = recvExact(n);
            int pad = (8 - (n % 8)) % 8;
            if (pad > 0) recvExact(pad);
            return data;
        }

        private String recvStr() throws IOException {
            return new String(recvBytes(), StandardCharsets.UTF_8);
        }

        private List<String> recvStrSet() throws IOException {
            int n = (int) recvU64();
            List<String> res = new ArrayList<>(n);
            for (int i = 0; i < n; i++) res.add(recvStr());
            return res;
        }

        private void drainStderr() throws IOException {
            while (true) {
                long code = recvU64();
                if (code == STDERR_LAST) return;
                if (code == STDERR_NEXT) {
                    recvBytes();
                } else if (code == STDERR_ERROR) {
                    String msg = recvStr();
                    recvU64();
                    throw new RuntimeException("nix daemon error: " + msg);
                } else {
                    throw new RuntimeException(String.format("unexpected nix daemon stderr code 0x%x", code));
                }
            }
        }

        private void handshake() throws IOException {
            sendU64(CLIENT_MAGIC);
            flush();
            long magic = recvU64();
            if (magic != SERVER_MAGIC) throw new RuntimeException(String.format("unexpected nix daemon magic 0x%x", magic));
            recvU64(); // server version
            sendU64(PROTOCOL_1_21);
            sendU64(0L); // cpuAffinity
            sendU64(0L); // reserveSpace
            flush();
            drainStderr();
        }

        Set<String> queryValidPaths(List<String> paths) throws IOException {
            if (paths.isEmpty()) return Collections.emptySet();
            sendU64(WOP_QUERY_VALID_PATHS);
            sendStrSet(paths);
            flush();
            drainStderr();
            return new HashSet<>(recvStrSet());
        }

        String addTextToStore(String name, byte[] text, List<String> refs) throws IOException {
            sendU64(WOP_ADD_TEXT_TO_STORE);
            sendStr(name);
            sendBytes(text);
            sendStrSet(refs);
            flush();
            drainStderr();
            return recvStr();
        }
    }

    private static void registerNodes(String remote, List<Node> nodes) throws IOException {
        if (nodes.isEmpty()) return;
        List<Node> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Node n : nodes) {
            if (seen.add(n.drvPath)) unique.add(n);
        }
        try (DaemonClient client = new DaemonClient(remote)) {
            List<String> paths = new ArrayList<>(unique.size());
            for (Node n : unique) paths.add(n.drvPath);
            Set<String> valid = new HashSet<>(client.queryValidPaths(paths));
            for (Node n : unique) {
                if (valid.contains(n.drvPath)) continue;
                String got = client.addTextToStore(n.drvName, n.aterm, n.refs);
                if (!got.equals(n.drvPath)) {
                    throw new RuntimeException("daemon returned " + got + ", expected " + n.drvPath);
                }
                valid.add(n.drvPath);
            }
        }
    }

    // --- rules_jvm_external lockfile planner ---

    static final class JarSpec {
        final String path;
        final String sha256;
        final List<String> urls;

        JarSpec(String path, String sha256, List<String> urls) {
            this.path = path;
            this.sha256 = sha256;
            this.urls = urls;
        }
    }

    private static boolean validComponent(String s) {
        if (s == null || s.isEmpty() || s.startsWith(".") || s.endsWith(".") || s.contains("..")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == '+' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static Map<String, JarSpec> planLock(Path lockFile, List<String> roots, boolean includeAll) throws IOException {
        String raw = Files.readString(lockFile, StandardCharsets.UTF_8);
        Map<String, Object> lock = asMap(new JsonParser(raw).parse());
        String version = (String) lock.get("version");
        if (!"3".equals(version)) {
            throw new RuntimeException("expected rules_jvm_external lock version 3");
        }
        Map<String, List<String>> repositories = new TreeMap<>();
        Map<String, Object> reposObj = asMap(lock.get("repositories"));
        if (reposObj != null) {
            for (Map.Entry<String, Object> e : new TreeMap<>(reposObj).entrySet()) {
                String url = e.getKey();
                if (!url.startsWith("https://") || url.indexOf('@') >= 0 || url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
                    throw new RuntimeException("expected a public HTTPS repository: " + url);
                }
                String base = url;
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                for (String coord : asStringList(e.getValue())) {
                    repositories.computeIfAbsent(coord, k -> new ArrayList<>()).add(base);
                }
            }
        }
        Map<String, String[]> artifacts = new TreeMap<>();
        Map<String, Object> artsObj = asMap(lock.get("artifacts"));
        if (artsObj != null) {
            for (Map.Entry<String, Object> e : new TreeMap<>(artsObj).entrySet()) {
                String coordinate = e.getKey();
                Map<String, Object> art = asMap(e.getValue());
                String[] pieces = coordinate.split(":", -1);
                String group, name, ext;
                if (pieces.length == 2) {
                    group = pieces[0]; name = pieces[1]; ext = "jar";
                } else if (pieces.length == 3) {
                    group = pieces[0]; name = pieces[1]; ext = pieces[2];
                } else {
                    throw new RuntimeException("invalid coordinate: " + coordinate);
                }
                String ver = (String) art.get("version");
                if (!validComponent(group) || !validComponent(name) || !validComponent(ext) || !validComponent(ver)) {
                    throw new RuntimeException("invalid coordinate or version: " + coordinate);
                }
                Map<String, Object> shasums = asMap(art.get("shasums"));
                if (shasums != null) {
                    for (Map.Entry<String, Object> se : new TreeMap<>(shasums).entrySet()) {
                        String classifier = se.getKey();
                        if (!validComponent(classifier)) {
                            throw new RuntimeException("invalid classifier: " + classifier);
                        }
                        String hash = (String) se.getValue();
                        String key = "jar".equals(classifier) ? coordinate : (group + ":" + name + ":" + ext + ":" + classifier);
                        String suffix = "jar".equals(classifier) ? "" : ("-" + classifier);
                        String path = group.replace('.', '/') + "/" + name + "/" + ver + "/" + name + "-" + ver + suffix + "." + ext;
                        artifacts.put(key, new String[]{ext, hash, path});
                    }
                }
            }
        }
        Set<String> skipped = new HashSet<>(asStringList(lock.get("skipped")));
        Map<String, Object> depsObj = asMap(lock.get("dependencies"));
        ArrayDeque<String> queue = new ArrayDeque<>();
        if (roots.isEmpty() && includeAll) {
            for (Map.Entry<String, String[]> e : artifacts.entrySet()) {
                String k = e.getKey();
                if ("jar".equals(e.getValue()[0]) && !skipped.contains(k)
                    && !k.endsWith(":sources") && !k.endsWith(":javadoc")) {
                    queue.add(k);
                }
            }
        } else {
            queue.addAll(roots);
        }

        Set<String> seen = new HashSet<>();
        Map<String, JarSpec> result = new TreeMap<>();
        while (!queue.isEmpty()) {
            String key = queue.pollFirst();
            if (!seen.add(key)) continue;
            String[] info = artifacts.get(key);
            if (info == null) throw new RuntimeException("missing coordinate: " + key);
            if (depsObj != null && depsObj.containsKey(key)) {
                queue.addAll(asStringList(depsObj.get(key)));
            }
            if (skipped.contains(key)) continue;
            String ext = info[0];
            String hash = info[1];
            String relPath = info[2];
            if (!"jar".equals(ext)) throw new RuntimeException("non-JAR classpath dependency: " + key);
            if (!isSha256Hex(hash)) throw new RuntimeException("missing SHA-256 for " + key);
            List<String> bases = repositories.get(key);
            if (bases == null || bases.isEmpty()) throw new RuntimeException("no locked repository for " + key);
            List<String> urls = new ArrayList<>(bases.size());
            for (String b : bases) urls.add(b + "/" + relPath);
            result.put(key, new JarSpec(relPath, hash.toLowerCase(Locale.ROOT), urls));
        }
        return result;
    }

    // --- Subcommands ---

    private static void cmdPlanJson(String[] args) throws IOException {
        if (args.length < 2) throw new RuntimeException("usage: drvjava-plan LOCKFILE [GROUP:ARTIFACT ...]");
        Path lockFile = Paths.get(args[1]);
        boolean includeAll = true;
        int idx = 2;
        if (idx < args.length && "--explicit-roots".equals(args[idx])) {
            includeAll = false;
            idx++;
        }
        List<String> roots = new ArrayList<>();
        for (int i = idx; i < args.length; i++) roots.add(args[i]);
        Map<String, JarSpec> planned = planLock(lockFile, roots, includeAll);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JarSpec> e : planned.entrySet()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("path", e.getValue().path);
            j.put("sha256", e.getValue().sha256);
            j.put("urls", e.getValue().urls);
            out.put(e.getKey(), j);
        }
        System.out.print(toJson(out));
    }

    private static void cmdPlan() throws IOException {
        String sdir = storeDir();
        Map<String, Object> cfg = asMap(new JsonParser(System.getenv("CONFIG_JSON")).parse());
        String lockSource = (String) cfg.get("lockSource");
        String lockRel = (String) cfg.get("lockFile");
        String memberLock = (String) cfg.get("memberLock");
        boolean explicitRoots = Boolean.TRUE.equals(cfg.get("explicitRoots"));
        List<String> roots = asStringList(cfg.get("roots"));
        String system = (String) cfg.get("system");
        String jdk = (String) cfg.get("jdk");
        String tool = (String) cfg.get("tool");

        Path lockPath;
        if (lockRel != null) {
            lockPath = Paths.get(lockSource, lockRel);
        } else {
            Path cand = Paths.get(lockSource, memberLock);
            lockPath = Files.isRegularFile(cand) ? cand : Paths.get(lockSource, "maven_install.json");
        }

        Map<String, JarSpec> planned = planLock(lockPath, roots, !explicitRoots);
        List<Node> nodes = new ArrayList<>();
        Map<String, List<String>> inputDrvs = new TreeMap<>();
        List<Map<String, Object>> jarEntries = new ArrayList<>();

        for (JarSpec jar : planned.values()) {
            String baseName = Paths.get(jar.path).getFileName().toString();
            Node f = makeFetchurlDrv(sdir, baseName, jar.urls, jar.sha256);
            nodes.add(f);
            inputDrvs.put(f.drvPath, Collections.singletonList("out"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rel", jar.path);
            entry.put("storePath", f.outPath);
            jarEntries.add(entry);
        }

        registerNodes(System.getenv("NIX_REMOTE"), nodes);

        String ph = hashPlaceholder("out");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JARS_JSON", toJson(jarEntries));
        env.put("builder", jdk + "/bin/java");
        env.put("name", "drvjava-jars");
        env.put("out", ph);
        env.put("outputHashAlgo", "sha256");
        env.put("outputHashMode", "recursive");
        env.put("system", system);

        Set<String> inputSrcs = new TreeSet<>(Arrays.asList(jdk, tool));
        List<String> args = Arrays.asList("-cp", tool + "/drvjava.jar", "DrvJava", "assemble-jars");
        List<String[]> outputs = Collections.singletonList(new String[]{"out", "", "r:sha256", ""});
        byte[] aterm = formatDerivation(outputs, inputDrvs, inputSrcs, system, jdk + "/bin/java", args, env);
        Files.write(Paths.get(System.getenv("out")), aterm);
    }

    private static void cmdAssembleJars() throws IOException {
        Path out = Paths.get(System.getenv("out"));
        Files.createDirectories(out);
        List<Object> entries = asList(new JsonParser(System.getenv("JARS_JSON")).parse());
        List<String> cp = new ArrayList<>(entries.size());
        for (Object item : entries) {
            Map<String, Object> e = asMap(item);
            String rel = (String) e.get("rel");
            String storePath = (String) e.get("storePath");
            cp.add(storePath);
            Path dest = out.resolve("jars").resolve(rel);
            Files.createDirectories(dest.getParent());
            Files.createSymbolicLink(dest, Paths.get(storePath));
        }
        Files.writeString(out.resolve("classpath"), String.join(":", cp), StandardCharsets.UTF_8);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) return;
        Files.walk(src).sorted().forEach(p -> {
            try {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private static void cmdCompile() throws IOException {
        Path out = Paths.get(System.getenv("out"));
        Files.createDirectories(out);
        Path srcRoot = Paths.get(System.getenv("SRC"));
        Map<String, Object> cfg = asMap(new JsonParser(System.getenv("COMPILE_JSON")).parse());
        List<String> dirs = asStringList(cfg.get("dirs"));
        List<String> resourcePaths = asStringList(cfg.get("resourcePaths"));
        List<String> requiredSources = asStringList(cfg.get("requiredSources"));
        String cpFile = (String) cfg.get("classpathFile");
        String extraCp = (String) cfg.get("extraCp");

        for (String req : requiredSources) {
            if (!Files.isRegularFile(srcRoot.resolve(req))) {
                throw new RuntimeException("missing Java source: " + req);
            }
        }

        List<String> javaFiles = new ArrayList<>();
        for (String d : dirs) {
            Path p = srcRoot.resolve(d);
            if (Files.isDirectory(p)) {
                List<Path> found = new ArrayList<>();
                try (var stream = Files.walk(p)) {
                    stream.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".java"))
                          .sorted()
                          .forEach(found::add);
                }
                for (Path f : found) javaFiles.add(f.toString());
            } else if (Files.isRegularFile(p)) {
                javaFiles.add(p.toString());
            }
        }

        String cp = Files.readString(Paths.get(cpFile), StandardCharsets.UTF_8).trim();
        if (extraCp != null && !extraCp.isEmpty()) {
            cp = cp.isEmpty() ? extraCp : (extraCp + ":" + cp);
        }

        if (!javaFiles.isEmpty()) {
            List<String> jargs = new ArrayList<>();
            jargs.add("-encoding");
            jargs.add("UTF-8");
            if (!cp.isEmpty()) {
                jargs.add("-cp");
                jargs.add(cp);
            }
            jargs.add("-d");
            jargs.add(out.toString());
            jargs.addAll(javaFiles);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new RuntimeException("no system JavaCompiler available");
            int rc = compiler.run(null, System.out, System.err, jargs.toArray(new String[0]));
            if (rc != 0) throw new RuntimeException("javac failed with exit code " + rc);
        }

        for (String r : resourcePaths) {
            Path rp = srcRoot.resolve(r);
            if (Files.isDirectory(rp)) copyTree(rp, out);
        }
    }

    private static void cmdBuild() throws IOException {
        Path out = Paths.get(System.getenv("out"));
        Path classpathDir = Paths.get(System.getenv("CLASSPATH_DIR"));
        Path mainClasses = Paths.get(System.getenv("MAIN_CLASSES"));
        String jdk = System.getenv("JDK");
        String bash = System.getenv("BASH");
        String mainClass = System.getenv("MAIN_CLASS");
        if (mainClass != null && mainClass.isEmpty()) mainClass = null;

        Files.createDirectories(out.resolve("bin"));
        Files.createDirectories(out.resolve("lib"));

        Path cpJars = classpathDir.resolve("jars");
        if (Files.isDirectory(cpJars)) {
            Files.createSymbolicLink(out.resolve("jars"), cpJars);
        } else {
            Files.createDirectories(out.resolve("jars"));
        }

        Path jarPath = out.resolve("lib").resolve("project.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry metaInf = new JarEntry("META-INF/");
            metaInf.setLastModifiedTime(FIXED_TIME);
            jos.putNextEntry(metaInf);
            jos.closeEntry();

            StringBuilder mf = new StringBuilder("Manifest-Version: 1.0\r\n");
            if (mainClass != null) mf.append("Main-Class: ").append(mainClass).append("\r\n");
            mf.append("\r\n");
            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            manifestEntry.setLastModifiedTime(FIXED_TIME);
            jos.putNextEntry(manifestEntry);
            jos.write(mf.toString().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            List<Path> entries = new ArrayList<>();
            try (var stream = Files.walk(mainClasses)) {
                stream.filter(p -> !p.equals(mainClasses)).sorted().forEach(entries::add);
            }
            for (Path p : entries) {
                String rel = mainClasses.relativize(p).toString().replace(File.separatorChar, '/');
                if (Files.isDirectory(p)) {
                    JarEntry je = new JarEntry(rel + "/");
                    je.setLastModifiedTime(FIXED_TIME);
                    jos.putNextEntry(je);
                    jos.closeEntry();
                } else {
                    JarEntry je = new JarEntry(rel);
                    je.setLastModifiedTime(FIXED_TIME);
                    jos.putNextEntry(je);
                    Files.copy(p, jos);
                    jos.closeEntry();
                }
            }
        }

        String depCp = Files.readString(classpathDir.resolve("classpath"), StandardCharsets.UTF_8).trim();
        String fullCp = depCp.isEmpty() ? jarPath.toString() : (jarPath.toString() + ":" + depCp);
        Files.writeString(out.resolve("classpath"), fullCp, StandardCharsets.UTF_8);

        StringBuilder wrapper = new StringBuilder();
        wrapper.append("#!").append(bash).append("/bin/bash\n");
        wrapper.append("exec ").append(shQuote(jdk + "/bin/java"))
               .append(" -cp ").append(shQuote(fullCp));
        if (mainClass != null) wrapper.append(' ').append(shQuote(mainClass));
        wrapper.append(" \"$@\"\n");
        Path bin = out.resolve("bin").resolve("drvjava-run");
        Files.writeString(bin, wrapper.toString(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bin, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
        ));
    }

    private static String shQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void cmdTest() throws Exception {
        Path out = Paths.get(System.getenv("out"));
        Path testClasses = Paths.get(System.getenv("TEST_CLASSES"));
        Path mainClasses = Paths.get(System.getenv("MAIN_CLASSES"));
        Path testCpDir = Paths.get(System.getenv("TEST_CLASSPATH_DIR"));
        String jdk = System.getenv("JDK");
        Map<String, Object> cfg = asMap(new JsonParser(System.getenv("TEST_JSON")).parse());
        String testClass = (String) cfg.get("testClass");
        List<String> testArgs = asStringList(cfg.get("testArgs"));

        String depCp = Files.readString(testCpDir.resolve("classpath"), StandardCharsets.UTF_8).trim();
        String fullCp = testClasses + ":" + mainClasses + (depCp.isEmpty() ? "" : (":" + depCp));

        List<String> cmd = new ArrayList<>();
        cmd.add(jdk + "/bin/java");
        cmd.add("-ea");
        cmd.add("-cp");
        cmd.add(fullCp);
        if (testClass == null) {
            cmd.add("org.junit.platform.console.ConsoleLauncher");
            cmd.add("execute");
            cmd.add("--scan-class-path");
            cmd.add("--fail-if-no-tests");
        } else {
            cmd.add(testClass);
        }
        cmd.addAll(testArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        String tmpdir = System.getenv("TMPDIR");
        if (tmpdir != null && !tmpdir.isEmpty()) pb.directory(new File(tmpdir));
        pb.inheritIO();
        int rc = pb.start().waitFor();
        if (rc != 0) System.exit(rc);
        Files.write(out, new byte[0]);
    }

    private static void copyWithReplace(Path src, Path dst, byte[] oldBytes, byte[] newBytes) throws IOException {
        byte[] data = Files.readAllBytes(src);
        if (oldBytes.length == newBytes.length && oldBytes.length > 0 && data.length >= oldBytes.length) {
            byte first = oldBytes[0];
            int limit = data.length - oldBytes.length;
            for (int i = 0; i <= limit; i++) {
                if (data[i] != first) continue;
                boolean match = true;
                for (int j = 1; j < oldBytes.length; j++) {
                    if (data[i + j] != oldBytes[j]) { match = false; break; }
                }
                if (match) {
                    System.arraycopy(newBytes, 0, data, i, newBytes.length);
                    i += oldBytes.length - 1;
                }
            }
        }
        Files.write(dst, data);
        if (Files.isExecutable(src)) {
            try {
                Files.setPosixFilePermissions(dst, Files.getPosixFilePermissions(src));
            } catch (UnsupportedOperationException ignored) {}
        }
    }

    private static void cmdSliceJdk() throws IOException {
        String fullJdkStr = System.getenv("FULL_JDK");
        String outStr = System.getenv("out");
        byte[] oldBytes = fullJdkStr.getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = outStr.getBytes(StandardCharsets.UTF_8);
        if (oldBytes.length != newBytes.length) {
            throw new RuntimeException("jdkSlim path length mismatch: " + oldBytes.length + " vs " + newBytes.length);
        }
        Path srcJdk = Paths.get(fullJdkStr).resolve("lib").resolve("openjdk");
        Path out = Paths.get(outStr);
        Path dstJdk = out.resolve("lib").resolve("openjdk");
        Files.createDirectories(dstJdk);
        String[] keepTop = {"bin", "conf", "include", "lib", "release"};
        for (String name : keepTop) {
            Path p = srcJdk.resolve(name);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(p).forEach(item -> {
                    try {
                        Path rel = srcJdk.relativize(item);
                        if (rel.toString().equals("lib/src.zip")) return;
                        Path target = dstJdk.resolve(rel);
                        if (Files.isSymbolicLink(item)) {
                            Files.createDirectories(target.getParent());
                            Files.createSymbolicLink(target, Files.readSymbolicLink(item));
                        } else if (Files.isDirectory(item)) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            copyWithReplace(item, target, oldBytes, newBytes);
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
            } else {
                copyWithReplace(p, dstJdk.resolve(name), oldBytes, newBytes);
            }
        }
        Files.createSymbolicLink(out.resolve("bin"), Paths.get("lib/openjdk/bin"));
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("drvjava: missing command");
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "plan": cmdPlan(); break;
                case "plan-json": cmdPlanJson(args); break;
                case "assemble-jars": cmdAssembleJars(); break;
                case "compile": cmdCompile(); break;
                case "build": cmdBuild(); break;
                case "test": cmdTest(); break;
                case "slice-jdk": cmdSliceJdk(); break;
                default: throw new RuntimeException("unknown drvjava command: " + args[0]);
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            System.err.println("drvjava: " + (msg != null ? msg : t.toString()));
            System.exit(1);
        }
    }
}
