import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pure-JDK pieces of the R2 migration.
 *
 * The live entry point is deliberately separate from this class so these invariants can be tested with
 * javac/java alone. No database, Cloudinary, or S3 client is reachable from this class.
 */
public final class R2MigrationCore {
    public static final String MANIFEST_KIND = "bunch-r2-image-migration";
    public static final int MANIFEST_VERSION = 1;
    public static final Pattern ASSET_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");
    public static final Pattern ARCHIVE_DIGEST = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final Set<String> RESOURCE_TYPES = Set.of("image", "video", "raw");
    private static final Set<String> DELIVERY_TYPES = Set.of("upload", "private", "authenticated", "fetch", "facebook", "twitter", "gravatar",
        "youtube", "hulu", "vimeo", "animoto", "worldstarhiphop", "dailymotion", "list");

    private R2MigrationCore() {
    }

    public record DbAsset(
        long id,
        String publicId,
        String url,
        String objectKey,
        long photoReferences,
        long profileReferences,
        List<String> photoFileHashes
    ) {
        public DbAsset(
            long id,
            String publicId,
            String url,
            String objectKey,
            long photoReferences,
            long profileReferences
        ) {
            this(id, publicId, url, objectKey, photoReferences, profileReferences, List.of());
        }

        public DbAsset {
            if (id <= 0) {
                throw new IllegalArgumentException("image asset id must be positive");
            }
            requireText(publicId, "public_id");
            requireText(url, "url");
            if (objectKey != null && objectKey.isBlank()) {
                throw new IllegalArgumentException("object_key must be null or non-blank");
            }
            if (photoReferences < 0 || profileReferences < 0) {
                throw new IllegalArgumentException("reference counts must not be negative");
            }
            photoFileHashes = photoFileHashes == null ? List.of() : List.copyOf(photoFileHashes);
            for (String hash : photoFileHashes) {
                if (!ARCHIVE_DIGEST.matcher(hash).matches()) {
                    throw new IllegalArgumentException("photo file hash must be lowercase SHA-256");
                }
            }
            photoFileHashes = photoFileHashes.stream().distinct().sorted().toList();
        }

        public boolean hasPhotoReference() {
            return photoReferences > 0;
        }

        public boolean hasProfileReference() {
            return profileReferences > 0;
        }

        public boolean isReferenced() {
            return hasPhotoReference() || hasProfileReference();
        }
    }

    public record CloudResource(
        String assetId,
        String publicId,
        String resourceType,
        String deliveryType,
        long version,
        String format,
        long sourceBytes,
        String sourceEtag,
        String secureUrl
    ) {
        public CloudResource {
            assetId = assetId == null ? "" : assetId;
            requireText(publicId, "public_id");
            requireAllowed(resourceType, RESOURCE_TYPES, "resource_type");
            requireAllowed(deliveryType, DELIVERY_TYPES, "type");
            if (version < 0) {
                throw new IllegalArgumentException("Cloudinary version must not be negative");
            }
            if (sourceBytes < 0) {
                throw new IllegalArgumentException("Cloudinary byte count must not be negative");
            }
            format = format == null ? "" : format;
            sourceEtag = sourceEtag == null ? "" : sourceEtag;
            secureUrl = secureUrl == null ? "" : secureUrl;
        }

        public String identityKey() {
            return R2MigrationCore.identityKey(resourceType, deliveryType, publicId);
        }

        public String archiveDigest() {
            return sha256Hex(identityKey());
        }
    }

    public record LegacyIdentity(String resourceType, String deliveryType, long version) {
        public LegacyIdentity {
            requireAllowed(resourceType, RESOURCE_TYPES, "resource_type");
            requireAllowed(deliveryType, DELIVERY_TYPES, "type");
            if (version < 0) {
                throw new IllegalArgumentException("legacy URL version must not be negative");
            }
        }
    }

    public record PlanEntry(
        String sourceIdentity,
        String assetId,
        String publicId,
        String resourceType,
        String deliveryType,
        long version,
        String format,
        long sourceBytes,
        String sourceEtag,
        String sourceUrl,
        Long dbAssetId,
        String classification,
        String targetKey,
        String thumbnailKey,
        boolean thumbnailRequired,
        String sourceSha256,
        String targetSha256,
        String thumbnailSha256,
        String state,
        String error
    ) {
        public PlanEntry {
            requireText(sourceIdentity, "source_identity");
            requireText(publicId, "public_id");
            requireAllowed(resourceType, RESOURCE_TYPES, "resource_type");
            requireAllowed(deliveryType, DELIVERY_TYPES, "type");
            if (version < 0 || sourceBytes < 0) {
                throw new IllegalArgumentException("source version/size must not be negative");
            }
            requireText(classification, "classification");
            requireText(state, "state");
            if (targetKey == null || targetKey.isBlank()) {
                throw new IllegalArgumentException("target_key must be non-blank");
            }
            validateTargetKey(targetKey);
            if (thumbnailRequired && (thumbnailKey == null || thumbnailKey.isBlank())) {
                throw new IllegalArgumentException("photo entry needs a thumbnail key");
            }
            if (!thumbnailRequired && thumbnailKey != null) {
                throw new IllegalArgumentException("non-photo entry must not have a thumbnail key");
            }
            validateOptionalHash(sourceSha256, "source_sha256");
            validateOptionalHash(targetSha256, "target_sha256");
            validateOptionalHash(thumbnailSha256, "thumbnail_sha256");
            if (thumbnailRequired && targetKey.startsWith("photos/") && !targetKey.endsWith("/original.jpg")) {
                throw new IllegalArgumentException("photo target must end in /original.jpg");
            }
            if (thumbnailRequired && !thumbnailKey.equals(R2MigrationCore.thumbnailKey(targetKey))) {
                throw new IllegalArgumentException("photo thumbnail key does not match original key");
            }
        }

        public PlanEntry withVerification(
            String sourceSha256,
            String targetSha256,
            String thumbnailSha256,
            String state,
            String error
        ) {
            return new PlanEntry(
                sourceIdentity,
                assetId,
                publicId,
                resourceType,
                deliveryType,
                version,
                format,
                sourceBytes,
                sourceEtag,
                sourceUrl,
                dbAssetId,
                classification,
                targetKey,
                thumbnailKey,
                thumbnailRequired,
                sourceSha256,
                targetSha256,
                thumbnailSha256,
                state,
                error
            );
        }
    }

    public static String identityKey(String resourceType, String deliveryType, String publicId) {
        requireAllowed(resourceType, RESOURCE_TYPES, "resource_type");
        requireAllowed(deliveryType, DELIVERY_TYPES, "type");
        requireText(publicId, "public_id");
        // NUL cannot be present in a Cloudinary public ID. The separators make the tuple unambiguous without
        // URL normalization, so public IDs such as "a/b" remain distinct from every other identity.
        return resourceType + "\u0000" + deliveryType + "\u0000" + publicId;
    }

    public static String displayIdentity(CloudResource resource) {
        return resource.resourceType() + "/" + resource.deliveryType() + "/" + resource.publicId();
    }

    public static String referencedPhotoKey(long assetId) {
        return "photos/" + checkedAssetSegment(Long.toString(assetId)) + "/original.jpg";
    }

    public static String referencedProfileKey(long assetId) {
        return "profiles/" + checkedAssetSegment(Long.toString(assetId)) + "/profile.jpg";
    }

    public static String thumbnailKey(String originalKey) {
        if (originalKey == null || !originalKey.startsWith("photos/") || !originalKey.endsWith("/original.jpg")) {
            throw new IllegalArgumentException("thumbnail key requires a canonical photo original key");
        }
        String result = originalKey.substring(0, originalKey.length() - "/original.jpg".length()) + "/thumbnail.jpg";
        validateTargetKey(result);
        return result;
    }

    /**
     * Archive keys never include a public ID. A public ID may contain slashes, Unicode, or characters that collide
     * after sanitization. The SHA-256 of the complete source identity is the collision-resistant path component.
     */
    public static String databaseArchiveKey(long assetId, CloudResource resource) {
        return "archive/assets/" + assetId + "/" + resource.archiveDigest() + "/original." +
            safeExtension(resource.format());
    }

    public static String orphanArchiveKey(CloudResource resource) {
        return "archive/orphans/" + resource.archiveDigest() + "/original." + safeExtension(resource.format());
    }

    public static void validateTargetKey(String key) {
        requireText(key, "target_key");
        if (key.contains("..") || key.contains("//") || key.contains("\\") || key.contains(":") ||
            key.startsWith("http") || key.startsWith("/")) {
            throw new IllegalArgumentException("object key contains an unsafe path component");
        }
        if (key.matches("photos/[A-Za-z0-9_-]+/original[.]jpg") ||
            key.matches("photos/[A-Za-z0-9_-]+/thumbnail[.]jpg") ||
            key.matches("profiles/[A-Za-z0-9_-]+/profile[.]jpg") ||
            key.matches("archive/assets/[0-9]+/[a-f0-9]{64}/original[.][A-Za-z0-9_-]{1,16}") ||
            key.matches("archive/orphans/[a-f0-9]{64}/original[.][A-Za-z0-9_-]{1,16}")) {
            return;
        }
        throw new IllegalArgumentException("object key is outside the R2 migration namespaces");
    }

    public static String safeExtension(String format) {
        if (format == null || format.isBlank()) {
            return "bin";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(normalized).matches() ? normalized : "bin";
    }

    public static String digestInventory(Collection<CloudResource> resources) {
        List<CloudResource> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator.comparing(CloudResource::identityKey));
        StringBuilder input = new StringBuilder();
        for (CloudResource resource : ordered) {
            input.append(resource.identityKey()).append('\u0000')
                .append(resource.assetId()).append('\u0000')
                .append(resource.version()).append('\u0000')
                .append(resource.sourceBytes()).append('\u0000')
                .append(resource.sourceEtag()).append('\u0000')
                .append(resource.format()).append('\n');
        }
        return sha256Hex(input.toString());
    }

    public static String digestDatabase(Collection<DbAsset> assets) {
        List<DbAsset> ordered = new ArrayList<>(assets);
        ordered.sort(Comparator.comparingLong(DbAsset::id));
        StringBuilder input = new StringBuilder();
        for (DbAsset asset : ordered) {
            input.append(asset.id()).append('\u0000')
                .append(asset.publicId()).append('\u0000')
                .append(asset.url()).append('\u0000')
                .append(asset.photoReferences()).append('\u0000')
                .append(String.join(",", asset.photoFileHashes())).append('\n');
        }
        return sha256Hex(input.toString());
    }

    public static String sha256Hex(byte[] bytes) {
        return hex(digest(bytes));
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static Optional<LegacyIdentity> parseLegacyUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            String[] segments = uri.getPath().split("/");
            for (int index = 0; index + 1 < segments.length; index++) {
                String resourceType = decodePathSegment(segments[index]);
                String deliveryType = decodePathSegment(segments[index + 1]);
                if (!RESOURCE_TYPES.contains(resourceType) || !DELIVERY_TYPES.contains(deliveryType)) {
                    continue;
                }
                long version = 0;
                for (int next = index + 2; next < segments.length; next++) {
                    String segment = decodePathSegment(segments[next]);
                    if (segment.matches("v[0-9]+")) {
                        version = Long.parseLong(segment.substring(1));
                        break;
                    }
                }
                return Optional.of(new LegacyIdentity(resourceType, deliveryType, version));
            }
        } catch (IllegalArgumentException ignored) {
            // The caller reports an unparseable legacy URL; it is never used as a download URL.
        }
        return Optional.empty();
    }

    public static boolean legacyUrlMentionsPublicId(String url, String publicId, String format) {
        if (url == null || publicId == null) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return false;
            }
            String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
            String suffix = "/" + publicId;
            if (decoded.endsWith(suffix)) {
                return true;
            }
            String normalizedFormat = safeExtension(format);
            return decoded.endsWith(suffix + "." + normalizedFormat) ||
                decoded.endsWith(suffix + "." + normalizedFormat.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void validateManifestHeader(Map<String, Object> manifest, String expectedCloudName) {
        requireText(string(manifest, "kind"), "kind");
        if (!MANIFEST_KIND.equals(string(manifest, "kind"))) {
            throw new IllegalArgumentException("manifest kind is not the R2 migration manifest");
        }
        if (longValue(manifest.get("manifest_version"), "manifest_version") != MANIFEST_VERSION) {
            throw new IllegalArgumentException("unsupported manifest version");
        }
        String cloudName = string(manifest, "cloud_name");
        if (!cloudName.equals(expectedCloudName)) {
            throw new IllegalArgumentException("manifest belongs to a different Cloudinary cloud");
        }
        requireDigest(string(manifest, "inventory_digest"), "inventory_digest");
        requireDigest(string(manifest, "database_digest"), "database_digest");
        if (!(manifest.get("resources") instanceof List<?>)) {
            throw new IllegalArgumentException("manifest resources must be an array");
        }
        if (!(manifest.get("database_assets") instanceof List<?>)) {
            throw new IllegalArgumentException("manifest database_assets must be an array");
        }
    }

    public static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("manifest field " + key + " must be a string");
        }
        return string;
    }

    public static long longValue(Object value, String field) {
        if (value instanceof Number number && number.longValue() == number.doubleValue()) {
            return number.longValue();
        }
        throw new IllegalArgumentException("manifest field " + field + " must be an integer");
    }

    public static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    public static String optionalString(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException("manifest field " + key + " must be a string or null");
        }
        return string;
    }

    public static String checkedAssetSegment(String value) {
        if (value == null || !ASSET_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("asset key segment is not safe");
        }
        return value;
    }

    private static void validateOptionalHash(String hash, String field) {
        if (hash != null && !ARCHIVE_DIGEST.matcher(hash).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 or null");
        }
    }

    private static void requireDigest(String digest, String field) {
        if (!ARCHIVE_DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }

    private static void requireAllowed(String value, Set<String> allowed, String field) {
        requireText(value, field);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " is not supported");
        }
    }

    private static String decodePathSegment(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] digest(byte[] bytes) {
        return newDigest().digest(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    /** Reuses the application's JSON library; no separate manifest parser is maintained. */
    public static final class Json {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL = MAPPER.copy()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        private Json() {
        }

        public static Object parse(String input) {
            try {
                return MAPPER.readValue(input, Object.class);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid migration JSON");
            }
        }

        public static String stringify(Object value) {
            return encode(MAPPER, value);
        }

        public static String canonical(Object value) {
            return encode(CANONICAL, value);
        }

        private static String encode(com.fasterxml.jackson.databind.ObjectMapper mapper, Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid migration JSON value");
            }
        }
    }
}
