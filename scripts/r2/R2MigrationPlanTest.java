import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline inventory pagination and delta-plan regression; never accesses a real service. */
public final class R2MigrationPlanTest {
    public static void main(String[] args) throws Exception {
        inventoryPaging();
        deltaPlanning();
        System.out.println("R2MigrationPlanTest: PASS (pagination, authoritative cross-check, stale/duplicate rejection, archive, delta)");
    }

    private static Map<String,Object> resource(String id, String type, long version) {
        return new LinkedHashMap<>(Map.of("asset_id", "asset-"+id, "public_id", id, "resource_type", type,
            "type", "upload", "version", version, "format", type.equals("image") ? "jpg" : "bin",
            "bytes", 5L, "etag", "etag-"+id,
            "secure_url", "https://res.cloudinary.com/demo/"+type+"/upload/v"+version+"/"+id+(type.equals("image")?".jpg":".bin")));
    }

    private static void inventoryPaging() throws Exception {
        AtomicInteger mode = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        Map<String,Object> image = resource("photo", "image", 7);
        Map<String,Object> raw = resource("document", "raw", 8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String,Object> response = new LinkedHashMap<>();
            if (path.endsWith("/search")) {
                searches.incrementAndGet();
                boolean second = body.contains("next_cursor");
                response.put("total_count", mode.get()==1 && second ? 3L : 2L);
                response.put("resources", List.of(second && mode.get()!=2 ? raw : image));
                if (!second) response.put("next_cursor", "second-page");
            } else if (path.endsWith("/image/upload")) {
                response.put("resources", mode.get()==3 ? List.of() : List.of(image));
            } else if (path.endsWith("/raw/upload")) {
                response.put("resources", List.of(raw));
            } else response.put("resources", List.of());
            byte[] bytes = R2MigrationCore.Json.stringify(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Class<?> type = Class.forName("R2ImageMigration$CloudinaryClient");
            Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            Object client = ctor.newInstance("demo", "test-key", "test-secret", "http://127.0.0.1:"+server.getAddress().getPort());
            Method list = type.getDeclaredMethod("listAll");list.setAccessible(true);
            List<?> resources = (List<?>)list.invoke(client);
            check(resources.size()==2 && searches.get()==2,"did not enumerate the last search page");
            mode.set(1); rejected(() -> list.invoke(client));
            mode.set(2); rejected(() -> list.invoke(client));
            mode.set(3); rejected(() -> list.invoke(client));
        } finally {server.stop(0);}
    }

    private static void deltaPlanning() throws Exception {
        R2MigrationCore.CloudResource photo = cloud(resource("photo", "image", 7));
        R2MigrationCore.CloudResource orphan = cloud(resource("document", "raw", 8));
        R2MigrationCore.DbAsset asset = new R2MigrationCore.DbAsset(42, "photo",
            "https://res.cloudinary.com/demo/image/upload/v7/photo.jpg", null, 1, 0);
        Class<?> planner = Class.forName("R2ImageMigration$Planner");
        Method create = planner.getDeclaredMethod("create",String.class,List.class,List.class);create.setAccessible(true);
        Object previous = create.invoke(null,"demo",List.of(photo,orphan),List.of(asset));
        Class<?> manifest = previous.getClass();
        Field entries = manifest.getDeclaredField("entries");entries.setAccessible(true);
        @SuppressWarnings("unchecked") List<R2MigrationCore.PlanEntry> old = (List<R2MigrationCore.PlanEntry>)entries.get(previous);
        check(old.stream().anyMatch(e->e.classification().equals("ORPHAN_NONIMAGE") && e.targetKey().startsWith("archive/")),"orphan not archived");
        String hash="a".repeat(64);
        for(int i=0;i<old.size();i++) old.set(i,old.get(i).withVerification(hash,hash,
            old.get(i).thumbnailRequired()?"b".repeat(64):null,"VERIFIED",null));
        Object current=create.invoke(null,"demo",List.of(photo,orphan),List.of(asset));
        Method carry=manifest.getDeclaredMethod("carryVerifiedFrom",manifest);carry.setAccessible(true);carry.invoke(current,previous);
        check(((List<?>)entries.get(current)).stream().allMatch(e->((R2MigrationCore.PlanEntry)e).state().equals("VERIFIED")),"same originals not reused");
        R2MigrationCore.CloudResource changed=new R2MigrationCore.CloudResource(photo.assetId(),photo.publicId(),photo.resourceType(),photo.deliveryType(),
            9,photo.format(),photo.sourceBytes(),photo.sourceEtag(),photo.secureUrl());
        Object changedPlan=create.invoke(null,"demo",List.of(changed,orphan),List.of(new R2MigrationCore.DbAsset(42,"photo",
            "https://res.cloudinary.com/demo/image/upload/v9/photo.jpg",null,1,0)));
        carry.invoke(changedPlan,previous);
        check(((List<?>)entries.get(changedPlan)).stream().anyMatch(e->{var entry=(R2MigrationCore.PlanEntry)e;
            return entry.publicId().equals("photo") && entry.state().equals("PLANNED");}),"changed source reused stale bytes");
    }

    private static R2MigrationCore.CloudResource cloud(Map<String,Object> resource) throws Exception {
        Method method=R2ImageMigration.class.getDeclaredMethod("resourceFromMap",Map.class);method.setAccessible(true);
        return (R2MigrationCore.CloudResource)method.invoke(null,resource);
    }
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static void rejected(Throwing action) throws Exception {
        try{action.run();throw new AssertionError("invalid inventory accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"unexpected failure");}
    }
    private interface Throwing {void run() throws Exception;}
}
