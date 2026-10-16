import app.familytv.discovery.ProfileRdKey;
import app.familytv.user.SharedProgressPage;
import app.familytv.portal.DeviceConfig;
import com.google.gson.*;
import java.nio.file.*;

/** Runs after R8, unlike JVM unit tests. All responses are from the synthetic Worker fixture. */
public class ProfileRdReleaseProbe {
    public static void main(String[] args) throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        int checked = 0;
        for (JsonElement exchange : fixture.getAsJsonArray("exchanges")) {
            JsonObject response = exchange.getAsJsonObject();
            String path = response.get("path").getAsString();
            if (path.equals("/api/device/config")) {
                DeviceConfig config = new Gson().fromJson(response.get("body").getAsString(), DeviceConfig.class);
                if (!config.getPaired() || config.getProfile() == null || !"synthetic-tmdb-metadata-key".equals(config.getTmdbApiKey()))
                    throw new AssertionError("Release config response corrupted");
            }
            if (path.startsWith("/api/device/progress?")) {
                SharedProgressPage page = new Gson().fromJson(response.get("body").getAsString(), SharedProgressPage.class);
                if (!"ab1234cd5678ef90".equals(page.getProfile()) || page.getRows() == null || !page.getRows().isEmpty())
                    throw new AssertionError("Release profile identity response corrupted");
            }
            if (!response.get("path").getAsString().startsWith("/api/device/realdebrid/api-key")) continue;
            String body = response.get("body").getAsString();
            JsonObject expected = JsonParser.parseString(body).getAsJsonObject();
            ProfileRdKey actual = new Gson().fromJson(body, ProfileRdKey.class);
            if (!expected.get("profileId").getAsString().equals(actual.getProfileId())
                    || expected.get("revision").getAsLong() != actual.getRevision()
                    || expected.get("configured").getAsBoolean() != actual.getConfigured()
                    || !java.util.Objects.equals(expected.has("apiKey") && !expected.get("apiKey").isJsonNull()
                            ? expected.get("apiKey").getAsString() : null, actual.getApiKey())) {
                throw new AssertionError("Release shrinking corrupted the profile key response");
            }
            checked++;
        }
        if (checked != 5) throw new AssertionError("Missing lifecycle fixture responses");
        System.out.println("PASS: " + checked + " synthetic Worker key responses survive release shrinking");
    }
}
