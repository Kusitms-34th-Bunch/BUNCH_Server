import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class R2MigrationCoreTest {
    public static void main(String[] args) throws Exception {
        testCanonicalKeys();
        testArchiveIdentityAndCollisionSafety();
        testIdentityAndLegacyUrlParsing();
        testDigestAndJsonRoundTrip();
        testManifestRejectsStaleShape();
        testNonImageClassificationInputs();
        System.out.println("R2MigrationCoreTest: PASS");
    }

    private static void testCanonicalKeys() {
        assertEquals("photos/42/original.jpg", R2MigrationCore.referencedPhotoKey(42));
        assertEquals("photos/42/thumbnail.jpg", R2MigrationCore.thumbnailKey("photos/42/original.jpg"));
        assertEquals("profiles/42/profile.jpg", R2MigrationCore.referencedProfileKey(42));
        R2MigrationCore.validateTargetKey("photos/42/original.jpg");
        R2MigrationCore.validateTargetKey("profiles/42/profile.jpg");
        R2MigrationCore.validateTargetKey(
            "archive/assets/42/" + "a".repeat(64) + "/original.png"
        );
        assertThrows(() -> R2MigrationCore.validateTargetKey("https://example.invalid/x"));
        assertThrows(() -> R2MigrationCore.validateTargetKey("photos/42/original.jpeg"));
        assertThrows(() -> R2MigrationCore.validateTargetKey("photos/../original.jpg"));
    }

    private static void testArchiveIdentityAndCollisionSafety() {
        R2MigrationCore.CloudResource first = resource("folder/a_b", "image", "upload", 1, "jpg");
        R2MigrationCore.CloudResource second = resource("folder/a/b", "image", "upload", 1, "jpg");
        assertNotEquals(first.archiveDigest(), second.archiveDigest());
        assertNotEquals(
            R2MigrationCore.orphanArchiveKey(first),
            R2MigrationCore.orphanArchiveKey(second)
        );
        assertTrue(R2MigrationCore.orphanArchiveKey(first).matches(
            "archive/orphans/[a-f0-9]{64}/original[.]jpg"
        ));
        assertTrue(R2MigrationCore.databaseArchiveKey(7, first).startsWith("archive/assets/7/"));
    }

    private static void testIdentityAndLegacyUrlParsing() {
        R2MigrationCore.CloudResource resource = resource("photos/old", "image", "authenticated", 12, "jpg");
        assertEquals(
            "image\u0000authenticated\u0000photos/old",
            resource.identityKey()
        );
        R2MigrationCore.LegacyIdentity identity = R2MigrationCore.parseLegacyUrl(
            "https://res.cloudinary.com/demo/image/authenticated/v12/photos/old.jpg"
        ).orElseThrow();
        assertEquals("image", identity.resourceType());
        assertEquals("authenticated", identity.deliveryType());
        assertEquals(12L, identity.version());
        assertTrue(R2MigrationCore.legacyUrlMentionsPublicId(
            "https://res.cloudinary.com/demo/image/authenticated/v12/photos/old.jpg",
            "photos/old",
            "jpg"
        ));
        assertTrue(R2MigrationCore.parseLegacyUrl("http://not-allowed.example/image/upload/x.jpg").isEmpty());
    }

    private static void testDigestAndJsonRoundTrip() throws Exception {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            R2MigrationCore.sha256Hex("abc")
        );
        Path fixture = Files.createTempFile("r2-core-test-", ".bin");
        try {
            Files.writeString(fixture, "abc", StandardCharsets.UTF_8);
            assertEquals(R2MigrationCore.sha256Hex("abc"), R2MigrationCore.sha256Hex(fixture));
        } finally {
            Files.deleteIfExists(fixture);
        }
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("message", "quote \" and slash \\\\");
        value.put("items", java.util.Arrays.asList(1L, true, null));
        Object parsed = R2MigrationCore.Json.parse(R2MigrationCore.Json.stringify(value));
        assertTrue(parsed instanceof Map<?, ?>);
        assertThrows(() -> R2MigrationCore.Json.parse("{\"x\":1,\"x\":2}"));
        assertThrows(() -> R2MigrationCore.Json.parse("{\"x\":"));
    }

    private static void testManifestRejectsStaleShape() {
        Map<String, Object> invalid = Map.of(
            "kind", R2MigrationCore.MANIFEST_KIND,
            "manifest_version", 999L,
            "cloud_name", "demo",
            "inventory_digest", "a".repeat(64),
            "database_digest", "b".repeat(64),
            "resources", List.of(),
            "database_assets", List.of()
        );
        assertThrows(() -> R2MigrationCore.validateManifestHeader(invalid, "demo"));
        Map<String, Object> wrongCloud = Map.of(
            "kind", R2MigrationCore.MANIFEST_KIND,
            "manifest_version", (long) R2MigrationCore.MANIFEST_VERSION,
            "cloud_name", "other",
            "inventory_digest", "a".repeat(64),
            "database_digest", "b".repeat(64),
            "resources", List.of(),
            "database_assets", List.of()
        );
        assertThrows(() -> R2MigrationCore.validateManifestHeader(wrongCloud, "demo"));
    }

    private static void testNonImageClassificationInputs() {
        R2MigrationCore.CloudResource video = resource("video/old", "video", "private", 4, "mp4");
        R2MigrationCore.DbAsset referenced = new R2MigrationCore.DbAsset(
            5,
            "video/old",
            "https://res.cloudinary.com/demo/video/private/v4/video/old.mp4",
            null,
            1,
            0
        );
        assertTrue(!video.resourceType().equals("image"));
        assertTrue(referenced.isReferenced());
        assertEquals(
            "archive/assets/5/" + video.archiveDigest() + "/original.mp4",
            R2MigrationCore.databaseArchiveKey(referenced.id(), video)
        );
    }

    private static R2MigrationCore.CloudResource resource(
        String publicId,
        String resourceType,
        String deliveryType,
        long version,
        String format
    ) {
        return new R2MigrationCore.CloudResource(
            "asset-" + version,
            publicId,
            resourceType,
            deliveryType,
            version,
            format,
            3,
            "etag-" + version,
            "https://res.cloudinary.com/demo/" + resourceType + "/" + deliveryType + "/" + publicId + "." + format
        );
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertNotEquals(Object first, Object second) {
        if (first.equals(second)) {
            throw new AssertionError("values unexpectedly equal: " + first);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("expected condition to be true");
        }
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected action to throw");
    }
}
