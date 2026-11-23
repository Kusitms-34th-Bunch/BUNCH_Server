import com.bunch.global.image.PhotoThumbnail;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * One-time Cloudinary to R2 copier.
 *
 * The default mode is offline plan generation from JSON fixtures. Network access and DB/object writes require
 * explicit flags. This source intentionally uses only JDK HTTP/JDBC/ImageIO through the shared PhotoThumbnail
 * helper and the approved AWS SDK v2 S3 client.
 */
public final class R2ImageMigration {
    private static final int MAX_RESULTS = 500;
    private static final Pattern CLOUD_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern DB_SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> RESOURCE_TYPES = Set.of("image", "video", "raw");
    private static final Set<String> DELIVERY_TYPES = Set.of(
        "upload",
        "private",
        "authenticated",
        "fetch",
        "facebook",
        "twitter",
        "gravatar",
        "youtube",
        "hulu",
        "vimeo",
        "animoto",
        "worldstarhiphop",
        "dailymotion",
        "list"
    );
    private static final Set<String> SOURCE_DELIVERY_TYPES = Set.of("upload", "private", "authenticated");

    private R2ImageMigration() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                printUsage();
                return;
            }
            switch (options.mode) {
                case "inventory" -> inventory(options);
                case "plan" -> plan(options);
                case "copy" -> copy(options);
                case "cutover" -> cutover(options);
                default -> throw new IllegalArgumentException("unknown --mode: " + options.mode);
            }
        } catch (Exception exception) {
            // Do not print exception objects: HTTP/JDBC exceptions can contain credentials, URLs, or response bodies.
            System.err.println("R2 migration failed: " + safeMessage(exception));
            System.exit(2);
        }
    }

    private static void inventory(Options options) throws Exception {
        require(options.live, "inventory requires --live");
        String cloudName = requireEnv("CLOUDINARY_CLOUD_NAME");
        CloudinaryClient cloudinary = new CloudinaryClient(
            cloudName,
            requireEnv("CLOUDINARY_API_KEY"),
            requireEnv("CLOUDINARY_API_SECRET")
        );
        List<R2MigrationCore.CloudResource> resources = cloudinary.listAll();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("kind", "cloudinary-inventory");
        output.put("cloud_name", cloudName);
        output.put("created_at", Instant.now().toString());
        output.put("inventory_digest", R2MigrationCore.digestInventory(resources));
        output.put("resources", resources.stream().map(R2ImageMigration::resourceMap).toList());
        writeJson(requiredOutput(options, "inventory"), output);
        printSummary("inventory", resources.size(), 0, 0);
    }

    private static void plan(Options options) throws Exception {
        require(options.output != null, "plan requires --output");
        String cloudName;
        List<R2MigrationCore.CloudResource> resources;
        List<R2MigrationCore.DbAsset> databaseAssets;
        if (options.live) {
            cloudName = requireEnv("CLOUDINARY_CLOUD_NAME");
            CloudinaryClient cloudinary = new CloudinaryClient(
                cloudName,
                requireEnv("CLOUDINARY_API_KEY"),
                requireEnv("CLOUDINARY_API_SECRET")
            );
            resources = cloudinary.listAll();
            try (DatabaseClient database = DatabaseClient.connect(options)) {
                databaseAssets = database.readSnapshot(false).assets;
            }
        } else {
            require(options.resourcesJson != null && options.databaseJson != null,
                "offline plan requires --resources-json and --database-json");
            cloudName = options.cloudName != null ? options.cloudName : "offline-fixture";
            resources = readResources(options.resourcesJson);
            databaseAssets = readDatabase(options.databaseJson);
        }

        Manifest manifest = Planner.create(cloudName, resources, databaseAssets);
        if (options.previousManifest != null) {
            Manifest previous = Manifest.read(options.previousManifest);
            manifest.carryVerifiedFrom(previous);
        }
        writeJson(options.output, manifest.toMap());
        printSummary("plan", resources.size(), manifest.verifiedCount(), manifest.fatalCount());
        Map<String, Long> classifications = new TreeMap<>();
        manifest.entries.forEach(entry -> classifications.merge(entry.classification(), 1L, Long::sum));
        manifest.databaseAssets.stream().filter(plan -> Manifest.isFatal(plan.classification))
            .forEach(plan -> classifications.merge(plan.classification, 1L, Long::sum));
        System.out.println("classifications=" + classifications);
    }

    private static void copy(Options options) throws Exception {
        require(options.live && options.apply && options.confirmWrite,
            "copy requires --live --apply --confirm-write");
        Path manifestPath = requireManifest(options);
        Manifest manifest = Manifest.read(manifestPath);
        try (RuntimeContext context = RuntimeContext.live(options, manifest.cloudName)) {
            context.assertFresh(manifest);
            context.copy(manifest, manifestPath, options.repairTarget);
        }
        printSummary("copy", manifest.entries.size(), manifest.verifiedCount(), manifest.fatalCount());
    }

    private static void cutover(Options options) throws Exception {
        require(options.live && options.apply && options.confirmWrite && options.confirmFreeze,
            "cutover requires --live --apply --confirm-write --confirm-freeze");
        Path manifestPath = requireManifest(options);
        Manifest manifest = Manifest.read(manifestPath);
        try (RuntimeContext context = RuntimeContext.live(options, manifest.cloudName)) {
            context.assertFresh(manifest);
            context.verifyAllTargets(manifest);
            context.applyDatabaseMapping(manifest);
        }
        printSummary("cutover", manifest.entries.size(), manifest.verifiedCount(), manifest.fatalCount());
    }

    private static Path requireManifest(Options options) {
        require(options.manifest != null, "this mode requires --manifest");
        return options.manifest;
    }

    private static Path requiredOutput(Options options, String kind) {
        require(options.output != null, kind + " requires --output");
        return options.output;
    }

    private static List<R2MigrationCore.CloudResource> readResources(Path path) throws IOException {
        Object parsed = R2MigrationCore.Json.parse(Files.readString(path));
        Object value = parsed instanceof Map<?, ?> map ? ((Map<?, ?>) map).get("resources") : parsed;
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("resource fixture must be an array or an object.resources array");
        }
        List<R2MigrationCore.CloudResource> result = new ArrayList<>();
        for (Object item : list) {
            result.add(resourceFromMap(R2MigrationCore.object(item, "resource")));
        }
        return result;
    }

    private static List<R2MigrationCore.DbAsset> readDatabase(Path path) throws IOException {
        Object parsed = R2MigrationCore.Json.parse(Files.readString(path));
        Object value = parsed instanceof Map<?, ?> map
            ? firstPresent(map, "database_assets", "assets")
            : parsed;
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("database fixture must be an array or a database_assets array");
        }
        List<R2MigrationCore.DbAsset> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> object = R2MigrationCore.object(item, "database asset");
            result.add(new R2MigrationCore.DbAsset(
                R2MigrationCore.longValue(object.get("id"), "id"),
                R2MigrationCore.string(object, "public_id"),
                R2MigrationCore.string(object, "url"),
                R2MigrationCore.optionalString(object, "object_key"),
                numberOrZero(object.get("photo_references"), "photo_references"),
                numberOrZero(object.get("profile_references"), "profile_references")
            ));
        }
        return result;
    }

    private static Object firstPresent(Map<?, ?> map, String first, String second) {
        return map.containsKey(first) ? map.get(first) : map.get(second);
    }

    private static R2MigrationCore.CloudResource resourceFromMap(Map<String, Object> object) {
        long version = object.get("version") != null ? numberOrZero(object.get("version"), "version") :
            R2MigrationCore.parseLegacyUrl(optionalString(object, "secure_url", ""))
                .map(R2MigrationCore.LegacyIdentity::version).orElse(0L);
        return new R2MigrationCore.CloudResource(
            optionalString(object, "asset_id", ""),
            R2MigrationCore.string(object, "public_id"),
            R2MigrationCore.string(object, "resource_type"),
            R2MigrationCore.string(object, "type"),
            version,
            optionalString(object, "format", ""),
            numberOrZero(object.get("bytes"), "bytes"),
            optionalString(object, "etag", ""),
            optionalString(object, "secure_url", "")
        );
    }

    private static Map<String, Object> resourceMap(R2MigrationCore.CloudResource resource) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset_id", resource.assetId());
        result.put("public_id", resource.publicId());
        result.put("resource_type", resource.resourceType());
        result.put("type", resource.deliveryType());
        result.put("version", resource.version());
        result.put("format", resource.format());
        result.put("bytes", resource.sourceBytes());
        result.put("etag", resource.sourceEtag());
        result.put("secure_url", resource.secureUrl());
        return result;
    }

    private static long numberOrZero(Object value, String field) {
        return value == null ? 0 : R2MigrationCore.longValue(value, field);
    }

    private static boolean booleanValue(Object value, String field) {
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException("manifest field " + field + " must be a boolean");
        }
        return booleanValue;
    }

    private static String optionalString(Map<String, Object> object, String key, String defaultValue) {
        String value = R2MigrationCore.optionalString(object, key);
        return value == null ? defaultValue : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void writeJson(Path path, Map<String, Object> object) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            R2MigrationCore.Json.stringify(object) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void printSummary(String operation, int resources, int verified, int failures) {
        System.out.println(operation + ": resources=" + resources + " verified=" + verified + " failures=" + failures);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "missing required environment variable " + name);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof SQLException || exception instanceof software.amazon.awssdk.core.exception.SdkException ||
            exception instanceof IOException) return exception.getClass().getSimpleName();
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message
            .replaceAll("(?i)(authorization|password|secret|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]")
            .replaceAll("https?://[^\\s]+", "[url-redacted]");
    }

    private static void printUsage() {
        System.out.println("""
            R2ImageMigration

            Safe default:
              --mode plan --resources-json resources.json --database-json database.json --output manifest.json

            Live phases:
              --mode inventory --live --output inventory.json
              --mode plan --live --output manifest.json
              --mode copy --live --apply --confirm-write --manifest manifest.json
              --mode cutover --live --apply --confirm-write --confirm-freeze --manifest manifest.json

            Required live environment:
              CLOUDINARY_CLOUD_NAME CLOUDINARY_API_KEY CLOUDINARY_API_SECRET
              R2_ENDPOINT R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY
              DB_URL DB_USERNAME DB_PASSWORD

            The copier never deletes Cloudinary or R2 source objects. ETag is recorded only for source-version
            freshness; every target is verified with a GET body SHA-256.
            """);
    }

    private static final class Options {
        private String mode = "plan";
        private String cloudName;
        private Path manifest;
        private Path output;
        private Path resourcesJson;
        private Path databaseJson;
        private Path previousManifest;
        private boolean live;
        private boolean apply;
        private boolean confirmWrite;
        private boolean confirmFreeze;
        private boolean repairTarget;
        private boolean help;

        private static Options parse(String[] args) {
            Options result = new Options();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                String[] split = argument.split("=", 2);
                String key = split[0];
                String value = split.length == 2 ? split[1] : null;
                switch (key) {
                    case "--help", "-h" -> result.help = true;
                    case "--live" -> result.live = true;
                    case "--apply" -> result.apply = true;
                    case "--confirm-write" -> result.confirmWrite = true;
                    case "--confirm-freeze" -> result.confirmFreeze = true;
                    case "--repair-target" -> result.repairTarget = true;
                    case "--mode" -> result.mode = value == null ? next(args, ++index, key) : value;
                    case "--cloud-name" -> result.cloudName = value == null ? next(args, ++index, key) : value;
                    case "--manifest" -> result.manifest = Path.of(value == null ? next(args, ++index, key) : value);
                    case "--output" -> result.output = Path.of(value == null ? next(args, ++index, key) : value);
                    case "--resources-json" -> result.resourcesJson = Path.of(value == null ? next(args, ++index, key) : value);
                    case "--database-json" -> result.databaseJson = Path.of(value == null ? next(args, ++index, key) : value);
                    case "--previous-manifest" -> result.previousManifest =
                        Path.of(value == null ? next(args, ++index, key) : value);
                    default -> throw new IllegalArgumentException("unknown argument: " + argument);
                }
            }
            return result;
        }

        private static String next(String[] args, int index, String key) {
            if (index >= args.length) {
                throw new IllegalArgumentException(key + " needs a value");
            }
            return args[index];
        }
    }

    private static boolean isRaster(R2MigrationCore.CloudResource resource) {
        return resource.resourceType().equals("image") && Set.of("jpg", "jpeg", "png", "webp", "gif", "avif",
            "heic", "heif", "tif", "tiff", "bmp", "ico", "svg").contains(resource.format().toLowerCase(Locale.ROOT));
    }

    private static final class Planner {
        private static Manifest create(
            String cloudName,
            List<R2MigrationCore.CloudResource> resources,
            List<R2MigrationCore.DbAsset> databaseAssets
        ) {
            require(CLOUD_NAME.matcher(cloudName).matches(), "invalid cloud name");
            Map<String, List<R2MigrationCore.CloudResource>> byPublicId = new HashMap<>();
            Map<String, R2MigrationCore.CloudResource> byIdentity = new HashMap<>();
            for (R2MigrationCore.CloudResource resource : resources) {
                if (byIdentity.put(resource.identityKey(), resource) != null) {
                    throw new IllegalArgumentException("duplicate Cloudinary resource identity");
                }
                byPublicId.computeIfAbsent(resource.publicId(), ignored -> new ArrayList<>()).add(resource);
            }

            Map<Long, DbPlan> dbPlans = new TreeMap<>();
            Set<String> matched = new HashSet<>();
            for (R2MigrationCore.DbAsset asset : databaseAssets) {
                DbPlan plan = matchAsset(asset, byPublicId);
                dbPlans.put(asset.id(), plan);
                if (plan.sourceIdentity != null) {
                    matched.add(plan.sourceIdentity);
                }
            }

            List<R2MigrationCore.PlanEntry> entries = new ArrayList<>();
            Map<String, String> targetOwners = new HashMap<>();
            for (R2MigrationCore.CloudResource resource : resources) {
                DbPlan dbPlan = dbPlans.values().stream()
                    .filter(plan -> resource.identityKey().equals(plan.sourceIdentity))
                    .findFirst()
                    .orElse(null);
                String classification;
                Long dbAssetId = null;
                String target;
                String thumbnail = null;
                boolean thumbnailRequired = false;
                if (dbPlan == null) {
                    classification = isRaster(resource) ? "ORPHAN_IMAGE" : "ORPHAN_NONIMAGE";
                    target = R2MigrationCore.orphanArchiveKey(resource);
                } else {
                    classification = dbPlan.classification;
                    dbAssetId = dbPlan.asset.id();
                    target = dbPlan.targetKey;
                    thumbnail = dbPlan.thumbnailKey;
                    thumbnailRequired = dbPlan.thumbnailRequired;
                }
                String owner = targetOwners.put(target, resource.identityKey());
                if (owner != null && !owner.equals(resource.identityKey())) {
                    throw new IllegalArgumentException("target key collision between source identities");
                }
                entries.add(new R2MigrationCore.PlanEntry(
                    resource.identityKey(),
                    resource.assetId(),
                    resource.publicId(),
                    resource.resourceType(),
                    resource.deliveryType(),
                    resource.version(),
                    resource.format(),
                    resource.sourceBytes(),
                    resource.sourceEtag(),
                    resource.secureUrl(),
                    dbAssetId,
                    classification,
                    target,
                    thumbnail,
                    thumbnailRequired,
                    null,
                    null,
                    null,
                    "PLANNED",
                    null
                ));
            }
            entries.sort(Comparator.comparing(R2MigrationCore.PlanEntry::sourceIdentity));
            return new Manifest(
                cloudName,
                R2MigrationCore.digestInventory(resources),
                R2MigrationCore.digestDatabase(databaseAssets),
                Instant.now().toString(),
                entries,
                new ArrayList<>(dbPlans.values())
            );
        }

        private static DbPlan matchAsset(
            R2MigrationCore.DbAsset asset,
            Map<String, List<R2MigrationCore.CloudResource>> byPublicId
        ) {
            var legacy = R2MigrationCore.parseLegacyUrl(asset.url()).orElse(null);
            if (legacy == null || !R2MigrationCore.legacyUrlMentionsPublicId(asset.url(), asset.publicId(), "")) {
                return DbPlan.failure(asset, "DB_URL_IDENTITY_UNPARSEABLE");
            }
            List<R2MigrationCore.CloudResource> candidates = byPublicId.getOrDefault(asset.publicId(), List.of())
                .stream()
                .filter(resource -> resource.resourceType().equals(legacy.resourceType()))
                .filter(resource -> resource.deliveryType().equals(legacy.deliveryType()))
                .toList();
            if (candidates.size() != 1) {
                return DbPlan.failure(asset, candidates.isEmpty() ? "MISSING_SOURCE" : "AMBIGUOUS_SOURCE");
            }
            R2MigrationCore.CloudResource resource = candidates.get(0);
            if (legacy.version() != 0 && legacy.version() != resource.version()) {
                return DbPlan.failure(asset, "SOURCE_VERSION_MISMATCH");
            }
            boolean photo = asset.hasPhotoReference();
            boolean profile = asset.hasProfileReference();
            String classification;
            String target;
            String thumbnail = null;
            boolean thumbnailRequired = false;
            if (asset.isReferenced() && isRaster(resource) &&
                !Set.of("jpg", "jpeg").contains(resource.format().toLowerCase(Locale.ROOT))) {
                return DbPlan.failure(asset, "REFERENCED_UNSUPPORTED_FORMAT");
            }
            if (!isRaster(resource)) {
                classification = asset.isReferenced() ? "REFERENCED_NONIMAGE" : "UNREFERENCED_DB_NONIMAGE";
                target = R2MigrationCore.databaseArchiveKey(asset.id(), resource);
            } else if (photo) {
                classification = "REFERENCED_PHOTO";
                target = R2MigrationCore.referencedPhotoKey(asset.id());
                thumbnail = R2MigrationCore.thumbnailKey(target);
                thumbnailRequired = true;
            } else if (profile) {
                classification = "REFERENCED_PROFILE";
                target = R2MigrationCore.referencedProfileKey(asset.id());
            } else {
                classification = "UNREFERENCED_DB_IMAGE";
                target = R2MigrationCore.databaseArchiveKey(asset.id(), resource);
            }
            if (asset.objectKey() != null && !asset.objectKey().equals(target)) {
                classification = "STALE_EXISTING_KEY";
            }
            return new DbPlan(asset, resource.identityKey(), classification, target, thumbnail, thumbnailRequired);
        }
    }

    private static final class DbPlan {
        private final R2MigrationCore.DbAsset asset;
        private final String sourceIdentity;
        private final String classification;
        private final String targetKey;
        private final String thumbnailKey;
        private final boolean thumbnailRequired;

        private DbPlan(
            R2MigrationCore.DbAsset asset,
            String sourceIdentity,
            String classification,
            String targetKey,
            String thumbnailKey,
            boolean thumbnailRequired
        ) {
            this.asset = asset;
            this.sourceIdentity = sourceIdentity;
            this.classification = classification;
            this.targetKey = targetKey;
            this.thumbnailKey = thumbnailKey;
            this.thumbnailRequired = thumbnailRequired;
        }

        private static DbPlan failure(R2MigrationCore.DbAsset asset, String classification) {
            return new DbPlan(asset, null, classification, null, null, false);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", asset.id());
            result.put("public_id", asset.publicId());
            result.put("url", asset.url());
            result.put("object_key", asset.objectKey());
            result.put("photo_references", asset.photoReferences());
            result.put("profile_references", asset.profileReferences());
            result.put("source_identity", sourceIdentity);
            result.put("classification", classification);
            result.put("target_key", targetKey);
            result.put("thumbnail_key", thumbnailKey);
            result.put("thumbnail_required", thumbnailRequired);
            return result;
        }
    }

    private static final class Manifest {
        private final String cloudName;
        private final String inventoryDigest;
        private final String databaseDigest;
        private final String createdAt;
        private final List<R2MigrationCore.PlanEntry> entries;
        private final List<DbPlan> databaseAssets;

        private Manifest(
            String cloudName,
            String inventoryDigest,
            String databaseDigest,
            String createdAt,
            List<R2MigrationCore.PlanEntry> entries,
            List<DbPlan> databaseAssets
        ) {
            this.cloudName = cloudName;
            this.inventoryDigest = inventoryDigest;
            this.databaseDigest = databaseDigest;
            this.createdAt = createdAt;
            this.entries = new ArrayList<>(entries);
            this.databaseAssets = new ArrayList<>(databaseAssets);
            validate();
        }

        private static Manifest read(Path path) throws IOException {
            Map<String, Object> object = R2MigrationCore.object(
                R2MigrationCore.Json.parse(Files.readString(path)),
                "manifest"
            );
            String cloudName = R2MigrationCore.string(object, "cloud_name");
            R2MigrationCore.validateManifestHeader(object, cloudName);
            List<R2MigrationCore.PlanEntry> entries = new ArrayList<>();
            for (Object value : (List<?>) object.get("resources")) {
                entries.add(entryFromMap(R2MigrationCore.object(value, "manifest resource")));
            }
            List<DbPlan> dbPlans = new ArrayList<>();
            for (Object value : (List<?>) object.get("database_assets")) {
                dbPlans.add(dbPlanFromMap(R2MigrationCore.object(value, "manifest database asset")));
            }
            return new Manifest(
                cloudName,
                R2MigrationCore.string(object, "inventory_digest"),
                R2MigrationCore.string(object, "database_digest"),
                R2MigrationCore.string(object, "created_at"),
                entries,
                dbPlans
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", R2MigrationCore.MANIFEST_KIND);
            result.put("manifest_version", R2MigrationCore.MANIFEST_VERSION);
            result.put("cloud_name", cloudName);
            result.put("created_at", createdAt);
            result.put("inventory_digest", inventoryDigest);
            result.put("database_digest", databaseDigest);
            result.put("resources", entries.stream().map(Manifest::entryMap).toList());
            result.put("database_assets", databaseAssets.stream().map(DbPlan::toMap).toList());
            return result;
        }

        private static R2MigrationCore.PlanEntry entryFromMap(Map<String, Object> object) {
            boolean thumbnailRequired = booleanValue(object.get("thumbnail_required"), "thumbnail_required");
            return new R2MigrationCore.PlanEntry(
                R2MigrationCore.string(object, "source_identity"),
                optionalString(object, "asset_id", ""),
                R2MigrationCore.string(object, "public_id"),
                R2MigrationCore.string(object, "resource_type"),
                R2MigrationCore.string(object, "delivery_type"),
                R2MigrationCore.longValue(object.get("version"), "version"),
                optionalString(object, "format", ""),
                R2MigrationCore.longValue(object.get("source_bytes"), "source_bytes"),
                optionalString(object, "source_etag", ""),
                optionalString(object, "source_url", ""),
                object.get("db_asset_id") == null ? null : R2MigrationCore.longValue(object.get("db_asset_id"), "db_asset_id"),
                R2MigrationCore.string(object, "classification"),
                R2MigrationCore.string(object, "target_key"),
                R2MigrationCore.optionalString(object, "thumbnail_key"),
                thumbnailRequired,
                R2MigrationCore.optionalString(object, "source_sha256"),
                R2MigrationCore.optionalString(object, "target_sha256"),
                R2MigrationCore.optionalString(object, "thumbnail_sha256"),
                R2MigrationCore.string(object, "state"),
                R2MigrationCore.optionalString(object, "error")
            );
        }

        private static DbPlan dbPlanFromMap(Map<String, Object> object) {
            R2MigrationCore.DbAsset asset = new R2MigrationCore.DbAsset(
                R2MigrationCore.longValue(object.get("id"), "id"),
                R2MigrationCore.string(object, "public_id"),
                R2MigrationCore.string(object, "url"),
                R2MigrationCore.optionalString(object, "object_key"),
                R2MigrationCore.longValue(object.get("photo_references"), "photo_references"),
                R2MigrationCore.longValue(object.get("profile_references"), "profile_references")
            );
            return new DbPlan(
                asset,
                R2MigrationCore.optionalString(object, "source_identity"),
                R2MigrationCore.string(object, "classification"),
                R2MigrationCore.optionalString(object, "target_key"),
                R2MigrationCore.optionalString(object, "thumbnail_key"),
                booleanValue(object.get("thumbnail_required"), "thumbnail_required")
            );
        }

        private static Map<String, Object> entryMap(R2MigrationCore.PlanEntry entry) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source_identity", entry.sourceIdentity());
            result.put("asset_id", entry.assetId());
            result.put("public_id", entry.publicId());
            result.put("resource_type", entry.resourceType());
            result.put("delivery_type", entry.deliveryType());
            result.put("version", entry.version());
            result.put("format", entry.format());
            result.put("source_bytes", entry.sourceBytes());
            result.put("source_etag", entry.sourceEtag());
            result.put("source_url", entry.sourceUrl());
            result.put("db_asset_id", entry.dbAssetId());
            result.put("classification", entry.classification());
            result.put("target_key", entry.targetKey());
            result.put("thumbnail_key", entry.thumbnailKey());
            result.put("thumbnail_required", entry.thumbnailRequired());
            result.put("source_sha256", entry.sourceSha256());
            result.put("target_sha256", entry.targetSha256());
            result.put("thumbnail_sha256", entry.thumbnailSha256());
            result.put("state", entry.state());
            result.put("error", entry.error());
            return result;
        }

        private void validate() {
            require(CLOUD_NAME.matcher(cloudName).matches(), "invalid manifest cloud name");
            require(databaseAssets.stream().map(plan -> plan.asset.id()).distinct().count() == databaseAssets.size(),
                "manifest contains duplicate database asset IDs");
            Map<String, String> sources = new HashMap<>();
            Map<String, String> targets = new HashMap<>();
            for (R2MigrationCore.PlanEntry entry : entries) {
                String previousSource = sources.put(entry.sourceIdentity(), entry.targetKey());
                require(previousSource == null, "manifest contains duplicate source identities");
                String previousTarget = targets.put(entry.targetKey(), entry.sourceIdentity());
                require(previousTarget == null || previousTarget.equals(entry.sourceIdentity()),
                    "manifest contains a target collision");
            }
            for (DbPlan plan : databaseAssets) {
                if (plan.sourceIdentity != null) {
                    require(sources.containsKey(plan.sourceIdentity), "DB plan has no resource entry");
                    require(plan.targetKey != null, "matched DB plan has no target key");
                }
            }
        }

        private int fatalCount() {
            int failures = 0;
            for (DbPlan plan : databaseAssets) {
                if (isFatal(plan.classification)) {
                    failures++;
                }
            }
            for (R2MigrationCore.PlanEntry entry : entries) {
                if (isFatal(entry.classification())) {
                    failures++;
                }
            }
            return failures;
        }

        private void carryVerifiedFrom(Manifest previous) {
            require(cloudName.equals(previous.cloudName), "previous manifest belongs to a different cloud");
            Map<String, R2MigrationCore.PlanEntry> old = new HashMap<>();
            previous.entries.forEach(entry -> old.put(entry.sourceIdentity(), entry));
            for (int index = 0; index < entries.size(); index++) {
                R2MigrationCore.PlanEntry current = entries.get(index);
                R2MigrationCore.PlanEntry prior = old.get(current.sourceIdentity());
                if (prior != null && "VERIFIED".equals(prior.state()) &&
                    current.assetId().equals(prior.assetId()) && current.version() == prior.version() &&
                    current.sourceBytes() == prior.sourceBytes() && current.sourceEtag().equals(prior.sourceEtag()) &&
                    current.format().equals(prior.format()) && current.classification().equals(prior.classification()) &&
                    current.targetKey().equals(prior.targetKey()) &&
                    java.util.Objects.equals(current.thumbnailKey(), prior.thumbnailKey()) &&
                    prior.sourceSha256() != null && prior.sourceSha256().equals(prior.targetSha256()) &&
                    (!current.thumbnailRequired() || prior.thumbnailSha256() != null)) {
                    entries.set(index, current.withVerification(prior.sourceSha256(), prior.targetSha256(),
                        prior.thumbnailSha256(), "VERIFIED", null));
                }
            }
        }

        private int verifiedCount() {
            return (int) entries.stream().filter(entry -> "VERIFIED".equals(entry.state())).count();
        }

        private static boolean isFatal(String classification) {
            return classification.startsWith("MISSING") ||
                classification.contains("MISMATCH") ||
                classification.contains("AMBIGUOUS") ||
                classification.contains("UNPARSEABLE") ||
                classification.equals("REFERENCED_NONIMAGE") ||
                classification.contains("UNSUPPORTED") ||
                classification.equals("STALE_EXISTING_KEY");
        }
    }

    private static final class RuntimeContext implements AutoCloseable {
        private final Options options;
        private final String cloudName;
        private final String cloudinarySecret;
        private final CloudinaryClient cloudinary;
        private final DatabaseClient database;
        private final S3Target target;

        private RuntimeContext(
            Options options,
            String cloudName,
            String cloudinarySecret,
            CloudinaryClient cloudinary,
            DatabaseClient database,
            S3Target target
        ) {
            this.options = options;
            this.cloudName = cloudName;
            this.cloudinarySecret = cloudinarySecret;
            this.cloudinary = cloudinary;
            this.database = database;
            this.target = target;
        }

        private static RuntimeContext live(Options options, String cloudName) throws Exception {
            require(cloudName.equals(requireEnv("CLOUDINARY_CLOUD_NAME")), "manifest cloud does not match environment");
            String apiKey = requireEnv("CLOUDINARY_API_KEY");
            String secret = requireEnv("CLOUDINARY_API_SECRET");
            CloudinaryClient cloudinary = new CloudinaryClient(cloudName, apiKey, secret);
            DatabaseClient database = DatabaseClient.connect(options);
            S3Target target = S3Target.connect();
            return new RuntimeContext(options, cloudName, secret, cloudinary, database, target);
        }

        private void assertFresh(Manifest manifest) throws Exception {
            List<R2MigrationCore.CloudResource> currentResources = cloudinary.listAll();
            if (!manifest.inventoryDigest.equals(R2MigrationCore.digestInventory(currentResources))) {
                throw new IllegalStateException("manifest rejected: Cloudinary inventory changed; create a new manifest");
            }
            Map<String, R2MigrationCore.CloudResource> byIdentity = new HashMap<>();
            for (R2MigrationCore.CloudResource resource : currentResources) {
                byIdentity.put(resource.identityKey(), resource);
            }
            for (R2MigrationCore.PlanEntry entry : manifest.entries) {
                R2MigrationCore.CloudResource resource = byIdentity.get(entry.sourceIdentity());
                require(resource != null, "manifest rejected: source identity disappeared");
                require(resource.assetId().equals(entry.assetId()) &&
                    resource.version() == entry.version() &&
                    resource.sourceBytes() == entry.sourceBytes() &&
                    resource.sourceEtag().equals(entry.sourceEtag()) &&
                    resource.format().equals(entry.format()), "manifest rejected: source identity/version changed");
            }
            DatabaseSnapshot snapshot = database.readSnapshot(true);
            require(snapshot.digest.equals(manifest.databaseDigest),
                "manifest rejected: database mapping/reference snapshot changed");
            Manifest expected = Planner.create(cloudName, currentResources, snapshot.assets);
            require(expected.databaseAssets.size() == manifest.databaseAssets.size(), "manifest DB mapping is incomplete");
            Map<Long, DbPlan> expectedPlans = new HashMap<>();
            expected.databaseAssets.forEach(plan -> expectedPlans.put(plan.asset.id(), plan));
            for (DbPlan plan : manifest.databaseAssets) {
                DbPlan correct = expectedPlans.get(plan.asset.id());
                require(correct != null && java.util.Objects.equals(correct.sourceIdentity, plan.sourceIdentity) &&
                    java.util.Objects.equals(correct.targetKey, plan.targetKey) &&
                    java.util.Objects.equals(correct.thumbnailKey, plan.thumbnailKey) &&
                    correct.classification.equals(plan.classification), "manifest DB mapping was altered");
            }
            Map<String, R2MigrationCore.PlanEntry> expectedEntries = new HashMap<>();
            expected.entries.forEach(entry -> expectedEntries.put(entry.sourceIdentity(), entry));
            require(expectedEntries.size() == manifest.entries.size(), "manifest resource list is incomplete");
            for (R2MigrationCore.PlanEntry entry : manifest.entries) {
                R2MigrationCore.PlanEntry correct = expectedEntries.get(entry.sourceIdentity());
                require(correct != null && correct.targetKey().equals(entry.targetKey()) &&
                    java.util.Objects.equals(correct.thumbnailKey(), entry.thumbnailKey()) &&
                    correct.classification().equals(entry.classification()), "manifest resource mapping was altered");
            }
            Map<Long, R2MigrationCore.DbAsset> currentById = new HashMap<>();
            for (R2MigrationCore.DbAsset asset : snapshot.assets) {
                currentById.put(asset.id(), asset);
            }
            for (DbPlan plan : manifest.databaseAssets) {
                R2MigrationCore.DbAsset current = currentById.get(plan.asset.id());
                require(current != null || plan.sourceIdentity == null, "manifest rejected: DB asset disappeared");
                if (current != null) {
                    require(current.publicId().equals(plan.asset.publicId()) &&
                        current.url().equals(plan.asset.url()) &&
                        current.photoReferences() == plan.asset.photoReferences() &&
                        current.profileReferences() == plan.asset.profileReferences(),
                        "manifest rejected: DB asset identity/reference changed");
                    if (current.objectKey() != null) {
                        require(current.objectKey().equals(plan.targetKey),
                            "manifest rejected: existing object_key points to a different target");
                    }
                }
            }
        }

        private void copy(Manifest manifest, Path manifestPath, boolean repairTarget) throws Exception {
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < manifest.entries.size(); index++) {
                R2MigrationCore.PlanEntry entry = manifest.entries.get(index);
                if (Manifest.isFatal(entry.classification())) {
                    failures.add(entry.classification() + ":" + entry.publicId());
                    continue;
                }
                if ("VERIFIED".equals(entry.state()) && entry.sourceSha256() != null &&
                    entry.sourceSha256().equals(entry.targetSha256())) {
                    // Original identity/version/bytes/ETag were checked by assertFresh. Verify final R2 bodies again.
                    Path scratch = Files.createTempFile("bunch-r2-reuse-", ".bin");
                    try {
                        require(entry.sourceSha256().equals(target.getSha256(entry.targetKey(), scratch, false)),
                            "reused original target hash mismatch");
                        if (entry.thumbnailRequired()) {
                            require(entry.thumbnailSha256() != null && entry.thumbnailSha256().equals(
                                target.getSha256(entry.thumbnailKey(), scratch, false)), "reused thumbnail hash mismatch");
                        }
                        continue;
                    } finally {
                        Files.deleteIfExists(scratch);
                    }
                }
                Path sourceFile = Files.createTempFile("bunch-r2-source-", ".bin");
                Path targetFile = Files.createTempFile("bunch-r2-target-", ".bin");
                Path thumbnailFile = Files.createTempFile("bunch-r2-thumbnail-", ".jpg");
                try {
                    String sourceUrl = SourceUrl.forResource(entry, cloudName, cloudinarySecret);
                    download(sourceUrl, sourceFile, entry.sourceBytes());
                    String sourceHash = R2MigrationCore.sha256Hex(sourceFile);
                    if (entry.dbAssetId() != null) database.verifyPhotoHashes(entry.dbAssetId(), sourceHash);
                    if (entry.sourceSha256() != null && !entry.sourceSha256().equals(sourceHash)) {
                        throw new IllegalStateException("source bytes changed for manifest identity");
                    }
                    String targetHash = target.putIfNeeded(
                        entry.targetKey(),
                        sourceFile,
                        sourceHash,
                        contentType(entry.format()),
                        entry.sourceIdentity(),
                        entry.version(),
                        repairTarget,
                        targetFile
                    );
                    String thumbnailHash = null;
                    if (entry.thumbnailRequired()) {
                        require(Files.size(sourceFile) <= 30L * 1024 * 1024,
                            "referenced photo exceeds the 30 MiB thumbnail input limit");
                        byte[] sourceBytes = Files.readAllBytes(sourceFile);
                        byte[] thumbnail = PhotoThumbnail.create(sourceBytes);
                        Files.write(thumbnailFile, thumbnail, StandardOpenOption.TRUNCATE_EXISTING);
                        thumbnailHash = R2MigrationCore.sha256Hex(thumbnailFile);
                        target.putIfNeeded(
                            entry.thumbnailKey(),
                            thumbnailFile,
                            thumbnailHash,
                            "image/jpeg",
                            entry.sourceIdentity(),
                            entry.version(),
                            repairTarget,
                            targetFile
                        );
                    }
                    manifest.entries.set(index, entry.withVerification(
                        sourceHash,
                        targetHash,
                        thumbnailHash,
                        "VERIFIED",
                        null
                    ));
                    writeJson(manifestPath, manifest.toMap());
                } catch (Exception exception) {
                    String error = safeMessage(exception);
                    manifest.entries.set(index, entry.withVerification(
                        entry.sourceSha256(),
                        entry.targetSha256(),
                        entry.thumbnailSha256(),
                        "FAILED",
                        error
                    ));
                    writeJson(manifestPath, manifest.toMap());
                    failures.add(entry.classification() + ":" + entry.publicId() + ":" + error);
                } finally {
                    Files.deleteIfExists(sourceFile);
                    Files.deleteIfExists(targetFile);
                    Files.deleteIfExists(thumbnailFile);
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("copy completed with " + failures.size() + " failed entries");
            }
        }

        private void verifyAllTargets(Manifest manifest) throws Exception {
            if (manifest.fatalCount() > 0) {
                throw new IllegalStateException("cutover refused: manifest contains fatal classification(s)");
            }
            for (R2MigrationCore.PlanEntry entry : manifest.entries) {
                require("VERIFIED".equals(entry.state()) && entry.sourceSha256() != null,
                    "cutover refused: every resource must be VERIFIED by copy");
                Path targetFile = Files.createTempFile("bunch-r2-final-target-", ".bin");
                try {
                    String actual = target.getSha256(entry.targetKey(), targetFile, false);
                    if (entry.dbAssetId() != null) database.verifyPhotoHashes(entry.dbAssetId(), actual);
                    require(entry.targetSha256().equals(actual) && entry.sourceSha256().equals(actual),
                        "cutover refused: target GET hash does not equal source hash");
                    if (entry.thumbnailRequired()) {
                        String thumbnail = target.getSha256(entry.thumbnailKey(), targetFile, false);
                        require(entry.thumbnailSha256().equals(thumbnail),
                            "cutover refused: thumbnail target GET hash mismatch");
                    }
                } finally {
                    Files.deleteIfExists(targetFile);
                }
            }
        }

        private void applyDatabaseMapping(Manifest manifest) throws SQLException {
            database.applyMappings(manifest);
        }

        @Override
        public void close() {
            try {
                database.close();
            } catch (SQLException exception) {
                System.err.println("R2 migration cleanup warning: database close failed");
            } finally {
                target.close();
            }
        }

        private static void download(String sourceUrl, Path output, long expectedBytes) throws Exception {
            URI uri = URI.create(sourceUrl);
            require("https".equalsIgnoreCase(uri.getScheme()), "source URL must use HTTPS");
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "*/*")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(output)
            );
            require(response.statusCode() == 200, "Cloudinary source GET returned HTTP " + response.statusCode());
            if (expectedBytes > 0) {
                require(Files.size(output) == expectedBytes, "source byte count differs from inventory");
            }
        }

        private static String contentType(String format) {
            return switch (R2MigrationCore.safeExtension(format)) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                case "mp4" -> "video/mp4";
                case "mov" -> "video/quicktime";
                case "pdf" -> "application/pdf";
                default -> "application/octet-stream";
            };
        }
    }

    private static final class CloudinaryClient {
        private final String cloudName;
        private final String apiKey;
        private final String apiSecret;
        private final HttpClient client;
        private final String apiBase;

        private CloudinaryClient(String cloudName, String apiKey, String apiSecret) {
            this(cloudName, apiKey, apiSecret, "https://api.cloudinary.com");
        }

        private CloudinaryClient(String cloudName, String apiKey, String apiSecret, String apiBase) {
            URI endpoint = URI.create(apiBase);
            require(apiBase.equals("https://api.cloudinary.com") ||
                (endpoint.getScheme().equals("http") && Set.of("127.0.0.1", "localhost").contains(endpoint.getHost())),
                "inventory endpoint must be official or loopback");
            this.apiBase = apiBase;
            require(CLOUD_NAME.matcher(cloudName).matches(), "invalid Cloudinary cloud name");
            require(!apiKey.isBlank() && !apiSecret.isBlank(), "Cloudinary credentials are empty");
            this.cloudName = cloudName;
            this.apiKey = apiKey;
            this.apiSecret = apiSecret;
            this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }

        private List<R2MigrationCore.CloudResource> listAll() throws Exception {
            // Search enumerates every delivery type, including fetched/social assets and non-images.
            // The unfiltered total_count and cursor checks make a partial inventory a hard failure.
            Map<String, R2MigrationCore.CloudResource> all = new TreeMap<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            long expectedTotal = -1;
            do {
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("max_results", MAX_RESULTS);
                query.put("sort_by", List.of(Map.of("public_id", "asc")));
                if (cursor != null) query.put("next_cursor", cursor);
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(apiBase + "/v1_1/" + cloudName + "/resources/search"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", basicAuth(apiKey, apiSecret))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(R2MigrationCore.Json.stringify(query)))
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                require(response.statusCode() == 200, "Cloudinary inventory HTTP " + response.statusCode());
                Map<String, Object> body = R2MigrationCore.object(R2MigrationCore.Json.parse(response.body()), "inventory");
                long total = R2MigrationCore.longValue(body.get("total_count"), "total_count");
                require(expectedTotal < 0 || total == expectedTotal, "inventory changed during pagination; retry");
                expectedTotal = total;
                require(body.get("resources") instanceof List<?>, "inventory has no resources array");
                for (Object item : (List<?>) body.get("resources")) {
                    R2MigrationCore.CloudResource resource = resourceFromMap(R2MigrationCore.object(item, "resource"));
                    require(all.putIfAbsent(resource.identityKey(), resource) == null,
                        "inventory repeated a resource; retry a fresh snapshot");
                }
                String next = R2MigrationCore.optionalString(body, "next_cursor");
                cursor = next == null || next.isBlank() ? null : next;
                require(cursor == null || cursors.add(cursor), "inventory cursor repeated");
            } while (cursor != null);
            require(all.size() == expectedTotal, "inventory count does not equal total_count");
            // Search is indexed. Cross-check the three durable source types through the authoritative Admin API
            // so a not-yet-indexed upload cannot be silently omitted during the final freeze.
            Set<String> authoritative = new HashSet<>();
            for (String resourceType : RESOURCE_TYPES) {
                for (String deliveryType : SOURCE_DELIVERY_TYPES) {
                    cursor = null;
                    cursors.clear();
                    do {
                        HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(resourcesUri(resourceType, deliveryType, cursor))
                                .timeout(Duration.ofMinutes(2)).header("Authorization", basicAuth(apiKey, apiSecret))
                                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        require(response.statusCode() == 200, "Cloudinary source inventory HTTP " + response.statusCode());
                        Map<String, Object> body = R2MigrationCore.object(R2MigrationCore.Json.parse(response.body()), "sources");
                        require(body.get("resources") instanceof List<?>, "source inventory missing resources");
                        for (Object item : (List<?>) body.get("resources")) {
                            R2MigrationCore.CloudResource resource = resourceFromMap(R2MigrationCore.object(item, "resource"));
                            R2MigrationCore.CloudResource indexed = all.get(resource.identityKey());
                            require(indexed != null && indexed.assetId().equals(resource.assetId()) &&
                                indexed.version() == resource.version() && indexed.sourceBytes() == resource.sourceBytes() &&
                                indexed.format().equals(resource.format()),
                                "Search/Admin inventory differs; wait for indexing and retry");
                            authoritative.add(resource.identityKey());
                            all.put(resource.identityKey(), resource);
                        }
                        String next = R2MigrationCore.optionalString(body, "next_cursor");
                        cursor = next == null || next.isBlank() ? null : next;
                        require(cursor == null || cursors.add(cursor), "source inventory cursor repeated");
                    } while (cursor != null);
                }
            }
            require(all.values().stream().filter(resource -> SOURCE_DELIVERY_TYPES.contains(resource.deliveryType()))
                .allMatch(resource -> authoritative.contains(resource.identityKey())),
                "Search includes deleted/unmatched originals; wait for indexing and retry");
            return new ArrayList<>(all.values());
        }

        private URI resourcesUri(String resourceType, String deliveryType, String cursor) {
            String query = "max_results=" + MAX_RESULTS +
                "&fields=" + encode("asset_id,public_id,format,version,resource_type,type,bytes,etag,secure_url");
            if (cursor != null) {
                query += "&next_cursor=" + encode(cursor);
            }
            return URI.create(
                apiBase + "/v1_1/" + cloudName + "/resources/" +
                    resourceType + "/" + deliveryType + "?" + query
            );
        }

        private static String basicAuth(String key, String secret) {
            return "Basic " + Base64.getEncoder().encodeToString(
                (key + ":" + secret).getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static final class SourceUrl {
        private static String forResource(R2MigrationCore.PlanEntry entry, String cloudName, String apiSecret) {
            if (!Set.of("private", "authenticated").contains(entry.deliveryType()) && !entry.sourceUrl().isBlank()) {
                URI uri = URI.create(entry.sourceUrl());
                require("https".equalsIgnoreCase(uri.getScheme()) &&
                    uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null,
                    "Cloudinary secure_url is not a safe HTTPS URL");
                return uri.toString();
            }
            String filename = entry.publicId();
            String extension = R2MigrationCore.safeExtension(entry.format());
            if (!filename.toLowerCase(Locale.ROOT).endsWith("." + extension) && !entry.format().isBlank()) {
                filename += "." + extension;
            }
            String path = (entry.version() > 0 ? "v" + entry.version() + "/" : "") + filename;
            String signature = "";
            if (!entry.deliveryType().equals("upload")) {
                signature = "/s--" + signDeliveryPath(encodePath(filename), apiSecret) + "--";
            }
            return "https://res.cloudinary.com/" + cloudName + "/" + entry.resourceType() + "/" +
                entry.deliveryType() + signature + "/" + encodePath(path);
        }

        private static String signDeliveryPath(String path, String secret) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((path + secret).getBytes(StandardCharsets.UTF_8));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 8);
            } catch (Exception exception) {
                throw new IllegalStateException("could not sign Cloudinary source URL");
            }
        }

        private static String encodePath(String path) {
            StringBuilder result = new StringBuilder();
            for (String segment : path.split("/", -1)) {
                if (result.length() > 0) {
                    result.append('/');
                }
                result.append(encode(segment).replace("+", "%20"));
            }
            return result.toString();
        }
    }

    private static final class S3Target implements AutoCloseable {
        private final String bucket;
        private final S3Client client;

        private S3Target(String bucket, S3Client client) {
            this.bucket = bucket;
            this.client = client;
        }

        private static S3Target connect() {
            String endpoint = requireEnv("R2_ENDPOINT");
            String bucket = requireEnv("R2_BUCKET");
            String accessKey = requireEnv("R2_ACCESS_KEY_ID");
            String secret = requireEnv("R2_SECRET_ACCESS_KEY");
            URI uri = URI.create(endpoint);
            require(("https".equalsIgnoreCase(uri.getScheme()) ||
                ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null &&
                    Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))),
                "R2 endpoint must be HTTPS or a loopback HTTP test endpoint");
            require(uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null,
                "R2 endpoint must not contain credentials/query/fragment");
            require(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"),
                "R2 endpoint must exclude the bucket path");
            require(uri.getScheme().equals("http") || uri.getHost().matches("[a-f0-9]{32}[.]r2[.]cloudflarestorage[.]com"),
                "R2 endpoint must use the account S3 hostname");
            require(bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"), "invalid R2 bucket");
            S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();
            S3Client client = S3Client.builder()
                .endpointOverride(uri)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .region(Region.of("auto"))
                .serviceConfiguration(serviceConfiguration)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofMinutes(5)))
                .build();
            return new S3Target(bucket, client);
        }

        private String putIfNeeded(
            String key,
            Path source,
            String expectedHash,
            String contentType,
            String sourceIdentity,
            long version,
            boolean repair,
            Path scratch
        ) throws IOException {
            String existing = getSha256(key, scratch, true);
            if (expectedHash.equals(existing)) {
                return existing;
            }
            if (existing != null && !repair) {
                throw new IllegalStateException("target exists with a different GET SHA-256: " + key);
            }
            Map<String, String> metadata = Map.of(
                "r2-source-identity-sha256", R2MigrationCore.sha256Hex(sourceIdentity),
                "r2-source-version", Long.toString(version),
                "r2-source-sha256", expectedHash
            );
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .metadata(metadata)
                    .build(),
                RequestBody.fromFile(source)
            );
            String verified = getSha256(key, scratch, false);
            require(expectedHash.equals(verified), "target GET SHA-256 differs after PUT: " + key);
            return verified;
        }

        private String getSha256(String key, Path output, boolean missingOkay) throws IOException {
            try {
                try (var stream = Files.newOutputStream(output, StandardOpenOption.TRUNCATE_EXISTING)) {
                    client.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(key).build(),
                        ResponseTransformer.toOutputStream(stream)
                    );
                }
                return R2MigrationCore.sha256Hex(output);
            } catch (S3Exception exception) {
                if (missingOkay && exception.statusCode() == 404) {
                    return null;
                }
                throw new IOException("R2 target GET failed with HTTP " + exception.statusCode());
            }
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class DatabaseSnapshot {
        private final List<R2MigrationCore.DbAsset> assets;
        private final String digest;

        private DatabaseSnapshot(List<R2MigrationCore.DbAsset> assets) {
            this.assets = assets;
            this.digest = R2MigrationCore.digestDatabase(assets);
        }
    }

    private static final class DatabaseClient implements AutoCloseable {
        private final Connection connection;
        private final String schema;

        private DatabaseClient(Connection connection, String schema) {
            this.connection = connection;
            this.schema = schema;
        }

        private static DatabaseClient connect(Options options) throws SQLException {
            String url = requireEnv("DB_URL");
            String username = requireEnv("DB_USERNAME");
            String password = requireEnv("DB_PASSWORD");
            String schema = System.getenv().getOrDefault("DB_SCHEMA", "public");
            require(DB_SCHEMA.matcher(schema).matches(), "invalid DB_SCHEMA");
            return new DatabaseClient(DriverManager.getConnection(url, username, password), schema);
        }

        private DatabaseSnapshot readSnapshot(boolean requireObjectKey) throws SQLException {
            boolean hasObjectKey = hasColumn("image_assets", "object_key");
            require(!requireObjectKey || hasObjectKey,
                "image_assets.object_key is missing; run scripts/r2/prepare.sql first");
            String assetsTable = qualified("image_assets");
            String objectColumn = hasObjectKey ? ", object_key" : "";
            Map<Long, Long> photoReferences = countReferences(
                "SELECT image_asset_id, COUNT(*) FROM " + qualified("photos") +
                    " GROUP BY image_asset_id"
            );
            Map<Long, Long> bundleReferences = countReferences(
                "SELECT image_asset_id, COUNT(*) FROM " + qualified("bundle_photos") +
                    " GROUP BY image_asset_id"
            );
            Map<Long, Long> profileReferences = countReferences(
                "SELECT profile_image_asset_id, COUNT(*) FROM (" +
                    "SELECT profile_image_asset_id FROM " + qualified("members") +
                    " WHERE profile_image_asset_id IS NOT NULL UNION ALL " +
                    "SELECT profile_image_asset_id FROM " + qualified("bunches") +
                    " WHERE profile_image_asset_id IS NOT NULL) refs " +
                    "GROUP BY profile_image_asset_id"
            );
            List<R2MigrationCore.DbAsset> assets = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                     "SELECT id, public_id, url" + objectColumn + " FROM " + assetsTable + " ORDER BY id"
                 )) {
                while (result.next()) {
                    long id = result.getLong("id");
                    String objectKey = hasObjectKey ? result.getString("object_key") : null;
                    assets.add(new R2MigrationCore.DbAsset(
                        id,
                        result.getString("public_id"),
                        result.getString("url"),
                        objectKey,
                        photoReferences.getOrDefault(id, 0L) + bundleReferences.getOrDefault(id, 0L),
                        profileReferences.getOrDefault(id, 0L)
                    ));
                }
            }
            return new DatabaseSnapshot(assets);
        }

        private void verifyPhotoHashes(long assetId, String hash) throws SQLException {
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT file_hash FROM " + qualified("photos") + " WHERE image_asset_id = ?")) {
                query.setLong(1, assetId);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) require(hash.equals(rows.getString(1)),
                        "source original SHA-256 does not match existing photo file_hash");
                }
            }
        }

        private void applyMappings(Manifest manifest) throws SQLException {
            connection.setAutoCommit(false);
            try {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try (Statement lock = connection.createStatement()) {
                    lock.execute(
                        "LOCK TABLE " + qualified("image_assets") + ", " + qualified("members") + ", " +
                            qualified("bunches") + ", " + qualified("photos") + ", " + qualified("bundle_photos") +
                            " IN SHARE ROW EXCLUSIVE MODE"
                    );
                }
                DatabaseSnapshot current = readSnapshot(true);
                require(current.digest.equals(manifest.databaseDigest),
                    "cutover refused: database changed after final freeze");
                Map<Long, R2MigrationCore.DbAsset> byId = new HashMap<>();
                for (R2MigrationCore.DbAsset asset : current.assets) {
                    byId.put(asset.id(), asset);
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + qualified("image_assets") +
                        " SET object_key = ? WHERE id = ? AND public_id = ? AND url = ?" +
                        " AND (object_key IS NULL OR object_key = ?)"
                )) {
                    for (DbPlan plan : manifest.databaseAssets) {
                        require(plan.sourceIdentity != null && plan.targetKey != null &&
                            !Manifest.isFatal(plan.classification),
                            "cutover refused: an image_assets row has no safe source/key mapping");
                        R2MigrationCore.DbAsset asset = byId.get(plan.asset.id());
                        require(asset != null, "cutover refused: image_assets row disappeared");
                        update.setString(1, plan.targetKey);
                        update.setLong(2, asset.id());
                        update.setString(3, asset.publicId());
                        update.setString(4, asset.url());
                        update.setString(5, plan.targetKey);
                        require(update.executeUpdate() == 1,
                            "cutover refused: concurrent object_key change detected");
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException(exception.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        }

        private Map<Long, Long> countReferences(String sql) throws SQLException {
            Map<Long, Long> result = new HashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    result.put(rows.getLong(1), rows.getLong(2));
                }
            }
            return result;
        }

        private boolean hasColumn(String table, String column) throws SQLException {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, schema, table, column)) {
                return columns.next();
            }
        }

        private String qualified(String table) {
            return "\"" + schema + "\".\"" + table + "\"";
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }
}
