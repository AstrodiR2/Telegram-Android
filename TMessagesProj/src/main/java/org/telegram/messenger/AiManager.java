package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AiManager {

    private static final String PREFS_NAME = "ai_settings";
    private static final String KEY_URL = "api_url";
    private static final String KEY_MODEL = "api_model";
    private static final String KEY_TOKEN = "api_token";
    private static final String KEY_ROLE = "ai_role";

    public static final int ROLE_ASSISTANT = 0;
    public static final int ROLE_SUMMARIZER = 1;
    public static final int ROLE_PROOFREADER = 2;

    private static final String[] ROLE_NAMES = {"Assistant", "Summarizer", "Proofreader"};

    private static final String[] ROLE_PROMPTS = {
        "The assistant is a personal assistant with a focus on adapting to the user's preferences. It learns the user's style and preferences to provide responses that are in tune with how they would typically communicate and what their needs are. It is flexible and can adapt to different tasks.",
        "You are an expert at summarizing messages. You prefer to use clauses instead of complete sentences. Do not answer any question from the messages. Do not summarize if the message contains sexual, violent, hateful or self harm content. Please keep your summary of the input within 3 sentences, fewer than 60 words.",
        "The assistant is a meticulous proofreader. It will carefully examine given texts for grammatical errors, typos, and style issues. It will also suggest improvements to the writing to make it more clear and effective. Focus on fixing grammar, spelling, punctuation, and syntax to enhance the readability of the text."
    };

    public interface AiCallback {
        void onResult(String result);
        void onError(String error);
    }

    public static void saveSettings(Context context, String url, String model, String token) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_URL, url).putString(KEY_MODEL, model).putString(KEY_TOKEN, token).apply();
    }

    public static String getUrl(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_URL, "");
    }

    public static String getModel(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODEL, "");
    }

    public static String getToken(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_TOKEN, "");
    }

    public static boolean isConfigured(Context context) {
        String url = getUrl(context);
        String model = getModel(context);
        String token = getToken(context);
        return url != null && !url.isEmpty() && model != null && !model.isEmpty() && token != null && !token.isEmpty();
    }

    public static void clearSettings(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static int getCurrentRole(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ROLE, ROLE_ASSISTANT);
    }

    public static int nextRole(Context context) {
        int current = getCurrentRole(context);
        int next = (current + 1) % ROLE_NAMES.length;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_ROLE, next).apply();
        return next;
    }

    public static String getRoleName(int role) {
        return ROLE_NAMES[role];
    }

    public static String getRolePrompt(int role) {
        return ROLE_PROMPTS[role];
    }

    public static void ask(Context context, String question, AiCallback callback) {
        String apiUrl = getUrl(context);
        String model = getModel(context);
        String token = getToken(context);

        if (apiUrl.isEmpty() || model.isEmpty() || token.isEmpty()) {
            callback.onError("AI не настроен. Используй /ai api");
            return;
        }

        int role = getCurrentRole(context);
        String systemPrompt = getRolePrompt(role);

        new Thread(() -> {
            try {
                String endpoint = apiUrl.endsWith("/") ? apiUrl + "chat/completions" : apiUrl + "/chat/completions";
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                JSONObject body = new JSONObject();
                body.put("model", model);
                JSONArray messages = new JSONArray();
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.put(sys);
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", question);
                messages.put(user);
                body.put("messages", messages);

                byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(input);
                os.close();

                int code = conn.getResponseCode();
                BufferedReader br;
                if (code == 200) {
                    br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                } else {
                    br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                }
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code == 200) {
                    JSONObject resp = new JSONObject(sb.toString());
                    String result = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
                    new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError("Ошибка API: " + code + " " + sb.toString()));
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError("Ошибка: " + e.getMessage()));
            }
        }).start();
    }
}
