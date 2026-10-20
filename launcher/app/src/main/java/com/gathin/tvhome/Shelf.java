package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Data for the shelf above the dock: "Continue watching" from a Jellyfin server and the recently used
 * apps. No UI here. Everything runs off the main thread and fails quietly to an empty row.
 *
 * Android's own Watch Next list cannot be read by a third-party launcher (it needs a privileged
 * permission), so continue-watching comes from the Jellyfin server instead.
 */
final class Shelf {
    private static final String TAG = "TvHomeShelf";
    static final int MAX_ITEMS = 12;

    /** One card: a 16:9 picture, a name for the row header, optional progress, what to open. */
    static final class Item {
        final String key;
        final String title;
        Bitmap image;          // network picture (Jellyfin)
        Drawable drawable;     // app banner or icon (recent apps)
        final float progress;  // 0..1, or -1 for none
        final Intent intent;

        Item(String key, String title, float progress, Intent intent) {
            this.key = key;
            this.title = title;
            this.progress = progress;
            this.intent = intent;
        }
    }

    private Shelf() {}

    // ---- recent apps ------------------------------------------------------------------------------

    static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Apps used in the last two weeks, newest first, skipping the dock apps and this launcher. */
    static List<Item> recentApps(Context context, Set<String> skip) {
        List<Item> out = new ArrayList<>();
        if (!hasUsageAccess(context)) return out;
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14L * 86_400_000L, now);
        if (stats == null) return out;
        // The same package can appear once per interval bucket: keep its latest use.
        java.util.Map<String, Long> last = new java.util.HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() <= 0) continue;
            Long t = last.get(s.getPackageName());
            if (t == null || s.getLastTimeUsed() > t) last.put(s.getPackageName(), s.getLastTimeUsed());
        }
        List<String> pkgs = new ArrayList<>(last.keySet());
        Collections.sort(pkgs, (a, b) -> Long.compare(last.get(b), last.get(a)));
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>(skip);
        seen.add(context.getPackageName());
        for (String pkg : pkgs) {
            if (out.size() >= MAX_ITEMS) break;
            if (!seen.add(pkg)) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // settings, system services: nothing to open
            try {
                ResolveInfo ri = pm.resolveActivity(launch, 0);
                CharSequence label = ri != null ? ri.loadLabel(pm) : pkg;
                Drawable banner = ri != null ? ri.activityInfo.loadBanner(pm) : null;
                if (banner == null) banner = pm.getApplicationBanner(pkg);
                if (banner == null) banner = pm.getApplicationIcon(pkg);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Item item = new Item("app:" + pkg, label.toString(), -1f, launch);
                item.drawable = banner;
                out.add(item);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return out;
    }

    // ---- Jellyfin ---------------------------------------------------------------------------------

    /**
     * Settings come from jellyfin.json in the launcher's external files directory, so the API key never
     * lives in the source or the APK:
     * {"server": "http://192.168.1.10:8096", "apiKey": "...", "user": "name"}
     */
    static final class JellyfinConfig {
        String server, apiKey, user;
    }

    static JellyfinConfig jellyfinConfig(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, "jellyfin.json");
        if (!f.isFile() || f.length() > 4096) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JellyfinConfig c = new JellyfinConfig();
            c.server = o.optString("server").replaceAll("/+$", "");
            c.apiKey = o.optString("apiKey");
            c.user = o.optString("user");
            if (c.server.isEmpty() || c.apiKey.isEmpty() || c.user.isEmpty()) return null;
            return c;
        } catch (Exception e) {
            Log.w(TAG, "jellyfin.json unreadable: " + e);
            return null;
        }
    }

    private static String cachedUserId, cachedUserKey;

    /** The server's resume list for the user: episodes and movies in progress, newest first. */
    static List<Item> jellyfinResume(JellyfinConfig c, int imageWidth) {
        List<Item> out = new ArrayList<>();
        try {
            String userKey = c.server + "|" + c.user;
            if (!userKey.equals(cachedUserKey)) {
                cachedUserId = null;
                JSONArray users = new JSONArray(get(c, "/Users"));
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.getJSONObject(i);
                    if (c.user.equalsIgnoreCase(u.optString("Name"))) cachedUserId = u.optString("Id");
                }
                cachedUserKey = userKey;
            }
            if (cachedUserId == null) {
                Log.w(TAG, "Jellyfin user not found: " + c.user);
                return out;
            }
            JSONObject res = new JSONObject(get(c, "/Users/" + cachedUserId + "/Items/Resume?Limit=" + MAX_ITEMS
                    + "&MediaTypes=Video&Fields=ParentId&EnableImageTypes=Primary,Thumb,Backdrop&ImageTypeLimit=1"));
            JSONArray items = res.optJSONArray("Items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String id = it.optString("Id");
                String title = it.optString("Name");
                if ("Episode".equals(it.optString("Type"))) {
                    String ep = "";
                    if (it.has("ParentIndexNumber") && it.has("IndexNumber")) {
                        ep = "S" + it.optInt("ParentIndexNumber") + ":E" + it.optInt("IndexNumber") + " · ";
                    }
                    title = it.optString("SeriesName") + "  " + ep + title;
                }
                JSONObject ud = it.optJSONObject("UserData");
                float progress = ud != null ? (float) ud.optDouble("PlayedPercentage", 0) / 100f : 0f;
                Intent open = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("org.jellyfin.androidtv",
                                "org.jellyfin.androidtv.ui.startup.StartupActivity"))
                        .putExtra("ItemId", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                Item item = new Item("jf:" + id, title, progress, open);
                String img = imageUrl(c, it, imageWidth);
                if (img != null) item.image = bitmap(img, imageWidth);
                out.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "Jellyfin resume failed: " + e);
        }
        return out;
    }

    /** A 16:9 picture: the episode still, else the series or movie thumb, else a backdrop. */
    private static String imageUrl(JellyfinConfig c, JSONObject it, int width) {
        String id = it.optString("Id");
        JSONObject tags = it.optJSONObject("ImageTags");
        String path = null;
        if ("Episode".equals(it.optString("Type")) && tags != null && tags.has("Primary")) {
            path = "/Items/" + id + "/Images/Primary";
        } else if (tags != null && tags.has("Thumb")) {
            path = "/Items/" + id + "/Images/Thumb";
        } else if (!it.optString("ParentThumbItemId").isEmpty()) {
            path = "/Items/" + it.optString("ParentThumbItemId") + "/Images/Thumb";
        } else {
            JSONArray backdrops = it.optJSONArray("BackdropImageTags");
            if (backdrops != null && backdrops.length() > 0) path = "/Items/" + id + "/Images/Backdrop/0";
            else if (!it.optString("ParentBackdropItemId").isEmpty())
                path = "/Items/" + it.optString("ParentBackdropItemId") + "/Images/Backdrop/0";
            else if (tags != null && tags.has("Primary")) path = "/Items/" + id + "/Images/Primary";
        }
        return path == null ? null : c.server + path + "?fillWidth=" + width + "&quality=85";
    }

    private static String get(JellyfinConfig c, String path) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(c.server + path).openConnection();
        http.setConnectTimeout(4000);
        http.setReadTimeout(8000);
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Authorization", "MediaBrowser Client=\"tvhome\", Device=\"TV\", DeviceId=\"tvhome\", "
                + "Version=\"1\", Token=\"" + c.apiKey + "\"");
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + path.split("\\?")[0]);
            return new String(readAll(http.getInputStream(), 2_000_000), StandardCharsets.UTF_8);
        } finally {
            http.disconnect();
        }
    }

    private static Bitmap bitmap(String url, int width) {
        try {
            HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(4000);
            http.setReadTimeout(8000);
            try {
                if (http.getResponseCode() != 200) return null;
                byte[] data = readAll(http.getInputStream(), 3_000_000);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } finally {
                http.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }
}
