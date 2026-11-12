package com.zyvo.code;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * Thin client for the local zyvo engine.
 *
 * Every call carries `x-opencode-directory`, which is how the server decides
 * which project instance a request belongs to (verified in
 * middleware/workspace-routing.ts).
 */
public final class ApiClient {

    private static final String TAG = "ZyvoApi";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static volatile OkHttpClient shared;

    private final String directory;
    private final Prefs prefs;

    public ApiClient(String directory, Prefs prefs) {
        this.directory = directory;
        this.prefs = prefs;
    }

    public static OkHttpClient http() {
        if (shared == null) {
            synchronized (ApiClient.class) {
                if (shared == null) {
                    shared = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE friendly
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return shared;
    }

    private Request.Builder req(String path) {
        Request.Builder b = new Request.Builder()
                .url(EngineManager.BASE_URL + path)
                .header("x-opencode-directory", directory)
                .header("Accept", "application/json");
        String pw = prefs.serverPassword();
        if (pw != null && !pw.isEmpty()) b.header("Authorization", "Bearer " + pw);
        return b;
    }

    private String exec(Request request) throws IOException {
        try (Response res = http().newCall(request).execute()) {
            ResponseBody body = res.body();
            String text = body == null ? "" : body.string();
            if (!res.isSuccessful()) {
                throw new IOException("HTTP " + res.code() + " " + request.url().encodedPath()
                        + (text.isEmpty() ? "" : " — " + trim(text, 400)));
            }
            return text;
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------- sessions

    public JsonObject createSession(String title) throws IOException {
        JsonObject payload = new JsonObject();
        if (title != null && !title.isEmpty()) payload.addProperty("title", title);
        Request request = req("/session")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        String body = exec(request);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    public JsonArray listSessions() throws IOException {
        String body = exec(req("/session").get().build());
        return JsonParser.parseString(body).getAsJsonArray();
    }

    public JsonArray messages(String sessionId) throws IOException {
        String body = exec(req("/session/" + sessionId + "/message").get().build());
        return JsonParser.parseString(body).getAsJsonArray();
    }

    public void renameSession(String sessionId, String title) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("title", title);
        exec(req("/session/" + sessionId)
                .patch(RequestBody.create(payload.toString(), JSON))
                .build());
    }

    public void deleteSession(String sessionId) throws IOException {
        exec(req("/session/" + sessionId).delete().build());
    }

    public void promptAsync(String sessionId, String text) throws IOException {
        promptAsync(sessionId, text, null);
    }

    public void promptAsync(String sessionId, String text, String variant) throws IOException {
        promptAsync(sessionId, text, variant, null);
    }

    public void promptAsync(String sessionId, String text, String variant, String agent)
            throws IOException {
        JsonObject payload = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        parts.add(part);
        payload.add("parts", parts);

        JsonObject model = new JsonObject();
        model.addProperty("providerID", prefs.providerId());
        model.addProperty("modelID", prefs.modelId());
        payload.add("model", model);

        if (variant != null && !variant.isEmpty()) {
            payload.addProperty("variant", variant);
        }
        // "build" (full tools) or "plan" (read-only planning agent).
        if (agent != null && !agent.isEmpty()) {
            payload.addProperty("agent", agent);
        }

        Request request = req("/session/" + sessionId + "/prompt_async")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        exec(request);
    }

    public void abort(String sessionId) {
        try {
            exec(req("/session/" + sessionId + "/abort").post(RequestBody.create("{}", JSON)).build());
        } catch (IOException e) {
            Log.w(TAG, "abort failed: " + e.getMessage());
        }
    }

    public void replyPermission(String sessionId, String permissionId, String response) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("response", response);
            exec(req("/session/" + sessionId + "/permissions/" + permissionId)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build());
        } catch (IOException e) {
            Log.w(TAG, "permission reply failed: " + e.getMessage());
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("reply", response);
                exec(req("/permission/" + permissionId + "/reply")
                        .post(RequestBody.create(payload.toString(), JSON))
                        .build());
            } catch (IOException ignored) {
            }
        }
    }

    public JsonObject providers() throws IOException {
        String body = exec(req("/config/providers").get().build());
        return JsonParser.parseString(body).getAsJsonObject();
    }

    // ------------------------------------------------------------ event SSE

    public interface EventListener {
        void onJson(JsonObject event);

        void onClosed(String reason);
    }

    public EventSource subscribe(EventListener listener) {
        Request request = req("/event")
                .header("Accept", "text/event-stream")
                .get()
                .build();

        EventSource.Factory factory = EventSources.createFactory(http());
        return factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (data == null || data.isEmpty()) return;
                try {
                    if (data.startsWith("{")) {
                        listener.onJson(JsonParser.parseString(data).getAsJsonObject());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "bad event: " + trim(data, 200));
                }
            }

            @Override
            public void onClosed(EventSource es) {
                listener.onClosed("closed");
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                listener.onClosed(t == null ? "failure" : t.getMessage());
            }
        });
    }

    public Call rawCall(String path) {
        return http().newCall(req(path).get().build());
    }
}
