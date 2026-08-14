package com.voiceflowkeyboard.ime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AnthropicClient {
    private static final String MODELS_URL = "https://api.anthropic.com/v1/models";
    private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Pattern WHOLE_MARKDOWN_FENCE = Pattern.compile(
            "\\A```(?:[A-Za-z0-9_-]+)?[ \\t]*(?:\\r?\\n)?([\\s\\S]*?)(?:\\r?\\n)?```\\s*\\z"
    );
    private static final String OUTPUT_CONTRACT = "\n\nOutput contract:\n"
            + "- Return plain text only.\n"
            + "- Do not wrap the answer in Markdown code fences, quotes, or labels.\n"
            + "- Do not add any text before or after the transformed text.";

    private AnthropicClient() {
    }

    static String transform(Context context, String transcript, String preset) throws Exception {
        return transform(context, transcript, preset, Prefs.expressionForPreset(context, preset));
    }

    static String transform(Context context, String transcript, String preset, int expression) throws Exception {
        String prompt = nonEmpty(Prefs.promptForPreset(context, preset, expression), Prefs.defaultPromptForPreset(preset));
        return transformWithPrompt(
                context,
                nonEmpty(
                        Prefs.transformModelForPreset(context, preset),
                        Prefs.defaultTransformModel(Prefs.PROVIDER_ANTHROPIC)
                ),
                prompt,
                "Transcript:\n" + transcript,
                2048
        );
    }

    static String applyInstruction(Context context, String sourceText, String instruction, String prompt) throws Exception {
        String input = "Editing instruction:\n" + instruction + "\n\nSource text:\n" + sourceText;
        return transformWithPrompt(
                context,
                nonEmpty(Prefs.transformModel(context), Prefs.defaultTransformModel(Prefs.PROVIDER_ANTHROPIC)),
                prompt,
                input,
                8192
        );
    }

    private static String transformWithPrompt(
            Context context,
            String model,
            String prompt,
            String input,
            int maxTokens
    ) throws Exception {
        String apiKey = requiredApiKey(context);
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("temperature", 0)
                .put("system", prompt + OUTPUT_CONTRACT)
                .put("messages", new JSONArray()
                        .put(new JSONObject()
                                .put("role", "user")
                                .put("content", input)));

        JSONObject json = new JSONObject(sendMessage(apiKey, body));
        JSONArray content = json.optJSONArray("content");
        StringBuilder builder = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject item = content.optJSONObject(i);
                if (item != null && "text".equals(item.optString("type"))) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(item.optString("text", ""));
                }
            }
        }
        String text = stripWholeOutputWrappers(builder.toString()).trim();
        if (!text.isEmpty()) {
            return text;
        }
        throw new IOException("Claude transform response did not include output text.");
    }

    static List<String> listModels(String apiKey) throws Exception {
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        if (trimmedKey.isEmpty()) {
            throw new IllegalStateException("Add your Anthropic API key first.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(MODELS_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        applyHeaders(connection, trimmedKey);
        JSONObject json = new JSONObject(readResponse(connection, "Anthropic model list"));
        JSONArray data = json.optJSONArray("data");
        List<String> models = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                String id = item == null ? "" : item.optString("id", "");
                if (!id.trim().isEmpty()) {
                    models.add(id.trim());
                }
            }
        }
        Collections.sort(models);
        return models;
    }

    static List<String> defaultTransformModels() {
        List<String> models = new ArrayList<>();
        models.add("claude-haiku-4-5");
        models.add("claude-sonnet-5");
        models.add("claude-opus-4-8");
        models.add("claude-fable-5");
        return models;
    }

    static List<String> transformModelsFrom(List<String> models) {
        List<String> filtered = new ArrayList<>();
        for (String model : models) {
            String lower = model.toLowerCase(Locale.US);
            if (lower.startsWith("claude-")) {
                filtered.add(model);
            }
        }
        return withFallbacks(filtered, defaultTransformModels());
    }

    private static List<String> withFallbacks(List<String> models, List<String> fallbacks) {
        List<String> merged = new ArrayList<>();
        for (String fallback : fallbacks) {
            if (!containsIgnoreCase(merged, fallback)) {
                merged.add(fallback);
            }
        }
        for (String model : models) {
            if (!containsIgnoreCase(merged, model)) {
                merged.add(model);
            }
        }
        return merged;
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String existing : values) {
            if (existing.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String sendMessage(String apiKey, JSONObject body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(MESSAGES_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        applyHeaders(connection, apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(connection, "Claude transform");
    }

    private static void applyHeaders(HttpURLConnection connection, String apiKey) {
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
    }

    private static String readResponse(HttpURLConnection connection, String label) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IOException(label + " failed (" + code + "): " + body);
        }
        return body;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        }
    }

    private static String stripWholeOutputWrappers(String text) {
        String result = text == null ? "" : text.trim();
        Matcher fence = WHOLE_MARKDOWN_FENCE.matcher(result);
        if (fence.matches()) {
            result = fence.group(1).trim();
        }
        return result;
    }

    private static String requiredApiKey(Context context) {
        String apiKey = Prefs.anthropicApiKey(context);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Add your Anthropic API key in VoiceFlow Keyboard settings.");
        }
        return apiKey.trim();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
