import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Offline wire regression: real AWS SDK v2, loopback HTTP only, synthetic credentials.
 * Run from the repository root after extracting app.jar's BOOT-INF/lib jars into build/r2-wire-libs:
 * <pre>
 * javac --release 21 --add-modules jdk.httpserver \
 *   -cp 'build/classes/kotlin/main:build/r2-wire-libs/*' -d build/r2-wire-classes \
 *   scripts/r2/R2MigrationCore.java scripts/r2/R2ImageMigration.java scripts/r2/R2MigrationWireTest.java
 * java --add-modules jdk.httpserver \
 *   -cp 'build/r2-wire-classes:build/classes/kotlin/main:build/r2-wire-libs/*' R2MigrationWireTest
 * </pre>
 * Never delete scratch before invoking the tool: production creates an existing temporary file,
 * and deleting it here would hide ResponseTransformer.toFile's FileAlreadyExistsException regression.
 */
public final class R2MigrationWireTest {
    private static final String BUCKET = "offline-wire-bucket";
    private static final String IDENTITY = "image\u0000upload\u0000offline-fixture";
    private static final long VERSION = 7;

    private R2MigrationWireTest() {
    }

    public static void main(String[] args) throws Exception {
        Path source = Files.createTempFile("r2-wire-source-", ".bin");
        Path scratch = Files.createTempFile("r2-wire-scratch-", ".bin");
        byte[] original = new byte[256 * 1024 + 17];
        for (int index = 0; index < original.length; index++) {
            original[index] = (byte) (index * 31 + index / 257);
        }
        Files.write(source, original);
        String hash = sha256(original);
        List<String> failures = new ArrayList<>();
        try (Wire wire = new Wire(); S3Client client = client(wire.endpoint())) {
            Class<?> type = Class.forName("R2ImageMigration$S3Target");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, S3Client.class);
            constructor.setAccessible(true);
            Object target = constructor.newInstance(BUCKET, client);
            Method put = type.getDeclaredMethod("putIfNeeded", String.class, Path.class, String.class,
                String.class, String.class, long.class, boolean.class, Path.class);
            Method get = type.getDeclaredMethod("getSha256", String.class, Path.class, boolean.class);
            put.setAccessible(true);
            get.setAccessible(true);

            run("PUT then GET verifies exact source bytes with existing scratch", failures, () -> {
                resetScratch(scratch);
                int start = wire.requests.size();
                equal(hash, invoke(put, target, "photos/101/original.jpg", source, hash,
                    "image/jpeg", IDENTITY, VERSION, false, scratch), "verified hash");
                List<Request> requests = wire.since(start);
                equal(List.of("GET", "PUT", "GET"), methods(requests), "first-copy wire sequence");
                Request upload = requests.get(1);
                check(Arrays.equals(original, upload.body()), "PUT changed source bytes or added AWS chunk frames");
                equal(Integer.toString(original.length), upload.contentLength(), "PUT content length");
                check(upload.transferEncoding() == null, "PUT used HTTP transfer encoding");
                check(upload.contentEncoding() == null || !upload.contentEncoding().contains("aws-chunked"),
                    "PUT used AWS chunked encoding");
                check(upload.payloadHash() != null && !upload.payloadHash().startsWith("STREAMING-"),
                    "PUT used streaming payload signing");
                equal("image/jpeg", upload.contentType(), "PUT content type");
                equal(hash, upload.sourceHash(), "source hash metadata");
                equal(sha256(IDENTITY.getBytes(StandardCharsets.UTF_8)), upload.identityHash(),
                    "source identity metadata");
                equal(Long.toString(VERSION), upload.version(), "source version metadata");
                check(Arrays.equals(original, Files.readAllBytes(scratch)), "GET scratch differs from source");
            });

            run("GET replaces existing scratch and hashes downloaded bytes", failures, () -> {
                String key = "photos/102/original.jpg";
                wire.objects.put(wire.path(key), original.clone());
                resetScratch(scratch);
                int start = wire.requests.size();
                equal(hash, invoke(get, target, key, scratch, false), "GET SHA-256");
                equal(List.of("GET"), methods(wire.since(start)), "direct GET sequence");
                check(Arrays.equals(original, Files.readAllBytes(scratch)), "GET did not replace scratch bytes");
            });

            run("matching rerun GET skips PUT", failures, () -> {
                String key = "photos/103/original.jpg";
                wire.objects.put(wire.path(key), original.clone());
                resetScratch(scratch);
                int start = wire.requests.size();
                equal(hash, invoke(put, target, key, source, hash,
                    "image/jpeg", IDENTITY, VERSION, false, scratch), "rerun hash");
                equal(List.of("GET"), methods(wire.since(start)), "rerun must not PUT");
            });

            run("mismatched target fails without overwriting", failures, () -> {
                String key = "photos/104/original.jpg";
                byte[] mismatch = "different existing object".getBytes(StandardCharsets.UTF_8);
                wire.objects.put(wire.path(key), mismatch);
                resetScratch(scratch);
                int start = wire.requests.size();
                try {
                    invoke(put, target, key, source, hash, "image/jpeg", IDENTITY, VERSION, false, scratch);
                    throw new AssertionError("mismatched target was accepted");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("different GET SHA-256"),
                        "failure did not identify target hash mismatch");
                }
                equal(List.of("GET"), methods(wire.since(start)), "mismatch must not PUT");
                check(Arrays.equals(mismatch, wire.objects.get(wire.path(key))), "mismatch target was overwritten");
            });

            run("GET corruption after PUT fails verification", failures, () -> {
                String key = "photos/105/original.jpg";
                wire.corruptAfterPut = wire.path(key);
                resetScratch(scratch);
                int start = wire.requests.size();
                try {
                    invoke(put, target, key, source, hash, "image/jpeg", IDENTITY, VERSION, false, scratch);
                    throw new AssertionError("corrupt GET after PUT was accepted");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("target GET SHA-256 differs after PUT"),
                        "failure did not identify post-PUT hash mismatch");
                } finally {
                    wire.corruptAfterPut = null;
                }
                equal(List.of("GET", "PUT", "GET"), methods(wire.since(start)), "corrupt-copy sequence");
            });

            run("source file remains byte-for-byte unchanged", failures, () -> {
                check(Arrays.equals(original, Files.readAllBytes(source)), "source file was mutated");
                equal(hash, sha256(Files.readAllBytes(source)), "source SHA-256");
            });
            run("loopback server handled every request", failures, () ->
                check(wire.errors.isEmpty(), "HTTP fixture failures: " + wire.errors));
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(scratch);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("R2MigrationWireTest: " + failures.size() + " failed: "
                + String.join("; ", failures));
        }
        System.out.println("R2MigrationWireTest: PASS (7 checks; loopback AWS SDK v2; no external services)");
    }

    private static S3Client client(URI endpoint) {
        return S3Client.builder()
            .endpointOverride(endpoint)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("offline-access-key", "offline-secret-key")))
            .region(Region.of("auto"))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(3)))
            .build();
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw wrapped;
        }
    }

    private static void resetScratch(Path scratch) throws IOException {
        Files.writeString(scratch, "existing scratch sentinel", StandardCharsets.UTF_8);
        check(Files.isRegularFile(scratch), "scratch must exist before tool invocation");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<String> methods(List<Request> requests) {
        return requests.stream().map(Request::method).toList();
    }

    private static void run(String name, List<String> failures, Check action) {
        try {
            action.run();
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            List<String> causes = new ArrayList<>();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                causes.add(cause.getClass().getSimpleName());
            }
            String detail = name + " [" + String.join(" -> ", causes) + "]";
            failures.add(detail);
            // Class names expose the regression without logging SDK URLs, credentials, or authorization headers.
            System.err.println("FAIL " + detail);
            if (failure instanceof AssertionError) {
                System.err.println("  " + failure.getMessage());
            }
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        check(expected.equals(actual), label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    private record Request(String method, byte[] body, String contentLength, String transferEncoding,
        String contentEncoding, String payloadHash, String contentType, String sourceHash,
        String identityHash, String version) {
    }

    private static final class Wire implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private volatile String corruptAfterPut;

        private Wire() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private String path(String key) {
            return "/" + BUCKET + "/" + key;
        }

        private List<Request> since(int start) {
            return List.copyOf(requests.subList(start, requests.size()));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                var headers = exchange.getRequestHeaders();
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                requests.add(new Request(method, body, headers.getFirst("Content-Length"),
                    headers.getFirst("Transfer-Encoding"), headers.getFirst("Content-Encoding"),
                    headers.getFirst("x-amz-content-sha256"), headers.getFirst("Content-Type"),
                    headers.getFirst("x-amz-meta-r2-source-sha256"),
                    headers.getFirst("x-amz-meta-r2-source-identity-sha256"),
                    headers.getFirst("x-amz-meta-r2-source-version")));
                if (!path.startsWith("/" + BUCKET + "/") || exchange.getRequestURI().getQuery() != null) {
                    errors.add("unexpected bucket path or query");
                    respond(exchange, 400,
                        "<Error><Code>InvalidRequest</Code></Error>".getBytes(StandardCharsets.UTF_8));
                } else if (method.equals("PUT")) {
                    objects.put(path, path.equals(corruptAfterPut) ? new byte[] {1, 2, 3} : body);
                    exchange.getResponseHeaders().set("ETag", "\"offline-etag\"");
                    exchange.sendResponseHeaders(200, -1);
                } else if (method.equals("GET")) {
                    byte[] stored = objects.get(path);
                    if (stored == null) {
                        respond(exchange, 404,
                            "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8));
                    } else {
                        respond(exchange, 200, stored);
                    }
                } else {
                    errors.add("unexpected method " + method);
                    exchange.sendResponseHeaders(405, -1);
                }
            } catch (IOException failure) {
                errors.add(failure.getClass().getSimpleName());
            }
        }

        private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type",
                status == 200 ? "application/octet-stream" : "application/xml");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
