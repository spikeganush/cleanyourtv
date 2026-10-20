package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A room's controls from Home Assistant for the shelf: a title line with sensor readings and one card
 * per entity. Settings come from ha.json in the launcher's external files directory, so the token never
 * lives in the source or the APK:
 * <pre>
 * {"server": "http://192.168.1.34:8123", "token": "...", "title": "Bedroom",
 *  "header": ["sensor.room_temperature", "sensor.room_humidity"],
 *  "cards": [{"entity": "light.room", "name": "Light"},
 *            {"entity": "input_boolean.motion_blocker", "name": "Auto light", "on": "Paused", "off": "On"},
 *            {"entity": "timer.sleep", "name": "Sleep timer"}]}
 * </pre>
 * Toggles light, switch, fan and input_boolean; starts, pauses and resumes a timer but never cancels it
 * (an automation watching the timer go idle would fire); runs a script or scene. Off the main thread only.
 */
final class HomeAssistant {
    private static final String TAG = "TvHomeHA";

    static final class Config {
        String server, token, title;
        final List<String> header = new ArrayList<>();
        final List<JSONObject> cards = new ArrayList<>();
    }

    static final class Room {
        String title;
        final List<Shelf.Item> cards = new ArrayList<>();
    }

    private HomeAssistant() {}

    static Config config(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, "ha.json");
        if (!f.isFile() || f.length() > 16384) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Config c = new Config();
            c.server = o.optString("server").replaceAll("/+$", "");
            c.token = o.optString("token");
            c.title = o.optString("title", "Home");
            JSONArray h = o.optJSONArray("header");
            for (int i = 0; h != null && i < h.length(); i++) c.header.add(h.getString(i));
            JSONArray cards = o.optJSONArray("cards");
            for (int i = 0; cards != null && i < cards.length(); i++) c.cards.add(cards.getJSONObject(i));
            if (c.server.isEmpty() || c.token.isEmpty() || c.cards.isEmpty()) return null;
            return c;
        } catch (Exception e) {
            Log.w(TAG, "ha.json unreadable: " + e);
            return null;
        }
    }

    /** The room as it is now; null when the server cannot be reached. */
    static Room load(Config c) {
        try {
            Room room = new Room();
            StringBuilder title = new StringBuilder(c.title);
            for (String id : c.header) {
                JSONObject s = state(c, id);
                if (s == null || "unavailable".equals(s.optString("state"))) continue;
                String unit = s.optJSONObject("attributes") != null
                        ? s.optJSONObject("attributes").optString("unit_of_measurement") : "";
                title.append("   ·   ").append(s.optString("state")).append("°C".equals(unit) ? "°" : unit);
            }
            room.title = title.toString();
            for (JSONObject card : c.cards) {
                String id = card.optString("entity");
                JSONObject s = state(c, id);
                if (s == null) continue;   // missing entity: no card
                room.cards.add(card(card, id, s));
            }
            return room;
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e);
            return null;
        }
    }

    private static Shelf.Item card(JSONObject card, String id, JSONObject s) {
        String domain = id.substring(0, id.indexOf('.'));
        String st = s.optString("state");
        JSONObject attrs = s.optJSONObject("attributes");
        String name = card.optString("name", attrs != null ? attrs.optString("friendly_name", id) : id);
        Shelf.Item item = new Shelf.Item("ha:" + id, name, -1f, null);
        item.entityId = id;
        item.state = st;
        item.active = "on".equals(st) || "active".equals(st);
        switch (domain) {
            case "light":
                item.icon = R.drawable.ic_ha_light;
                break;
            case "input_boolean":
            case "binary_sensor":
                item.icon = id.contains("motion") || id.contains("presence") ? R.drawable.ic_ha_motion : R.drawable.ic_ha_power;
                break;
            case "timer":
                item.icon = R.drawable.ic_ha_timer;
                break;
            case "fan":
                item.icon = R.drawable.ic_ha_fan;
                break;
            default:
                item.icon = R.drawable.ic_ha_power;
        }
        if ("unavailable".equals(st) || "unknown".equals(st)) {
            item.value = "Unavailable";
        } else if ("timer".equals(domain)) {
            item.value = timerText(st, attrs);
        } else if (card.has(st)) {
            item.value = card.optString(st);        // custom label for this state, e.g. "on": "Paused"
        } else if ("light".equals(domain) && "on".equals(st) && attrs != null && attrs.has("brightness")) {
            item.value = "On · " + Math.round(attrs.optInt("brightness") * 100f / 255f) + "%";
        } else {
            item.value = "on".equals(st) ? "On" : "off".equals(st) ? "Off" : st;
        }
        return item;
    }

    private static String timerText(String st, JSONObject attrs) {
        try {
            if ("active".equals(st) && attrs != null && attrs.has("finishes_at")) {
                long min = Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(attrs.getString("finishes_at"))).toMinutes();
                return Math.max(0, min + 1) + " min left";
            }
            if ("paused".equals(st) && attrs != null && attrs.has("remaining")) {
                String[] hms = attrs.getString("remaining").split(":");
                long min = Long.parseLong(hms[0]) * 60 + Long.parseLong(hms[1]);
                return "Paused · " + min + " min";
            }
        } catch (Exception ignored) {
        }
        return "active".equals(st) ? "Running" : "paused".equals(st) ? "Paused" : "Off";
    }

    /** What OK on a card does; true when the call went through. */
    static boolean press(Config c, Shelf.Item item) {
        String id = item.entityId;
        String domain = id.substring(0, id.indexOf('.'));
        String service;
        switch (domain) {
            case "timer":
                // idle: start; running: pause; paused: start resumes it. Never cancel or finish.
                service = "active".equals(item.state) ? "timer/pause" : "timer/start";
                break;
            case "script":
            case "scene":
                service = domain + "/turn_on";
                break;
            case "light":
            case "switch":
            case "fan":
            case "input_boolean":
                service = "homeassistant/toggle";
                break;
            default:
                return false;   // sensors are for reading
        }
        try {
            post(c, "/api/services/" + service, "{\"entity_id\":\"" + id + "\"}");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "press failed: " + e);
            return false;
        }
    }

    private static JSONObject state(Config c, String id) throws Exception {
        HttpURLConnection http = open(c, "/api/states/" + id);
        try {
            int code = http.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code + " for " + id);
            return new JSONObject(read(http.getInputStream()));
        } finally {
            http.disconnect();
        }
    }

    private static void post(Config c, String path, String body) throws IOException {
        HttpURLConnection http = open(c, path);
        try {
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = http.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + path);
        } finally {
            http.disconnect();
        }
    }

    private static HttpURLConnection open(Config c, String path) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(c.server + path).openConnection();
        http.setConnectTimeout(3000);
        http.setReadTimeout(5000);
        http.setRequestProperty("Authorization", "Bearer " + c.token);
        return http;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 512_000) throw new IOException("response too large");
            }
            return out.toString("UTF-8");
        }
    }
}
