package com.voiceflowkeyboard.ime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DeepgramClient {
    private static final String LISTEN_URL = "https://api.deepgram.com/v1/listen";

    private DeepgramClient() {
    }

    static String transcribe(Context context, File audioFile) throws Exception {
        return transcribe(context, audioFile, DictationLanguage.AUTO);
    }

    static String transcribe(Context context, File audioFile, DictationLanguage language) throws Exception {
        String apiKey = requiredApiKey(context);
        String model = nonEmpty(Prefs.transcriptionModel(context), Prefs.defaultTranscriptionModel(Prefs.PROVIDER_DEEPGRAM));
        String url = LISTEN_URL
                + "?model=" + URLEncoder.encode(model, "UTF-8")
                + "&smart_format=true"
                + "&filler_words=false";
        // nova-3 defaults to English. Its multilingual "multi" code does not
        // cover Mandarin, so Chinese has to be requested explicitly and cannot
        // be combined with English on this provider.
        String deepgramLanguage = language.deepgramLanguage();
        if (!deepgramLanguage.isEmpty()) {
            url += "&language=" + URLEncoder.encode(deepgramLanguage, "UTF-8");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("Authorization", "Token " + apiKey);
        connection.setRequestProperty("Content-Type", "audio/mp4");
        try (OutputStream out = connection.getOutputStream();
             InputStream in = new BufferedInputStream(new FileInputStream(audioFile))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        JSONObject json = new JSONObject(readResponse(connection));
        JSONObject results = json.optJSONObject("results");
        JSONArray channels = results == null ? null : results.optJSONArray("channels");
        if (channels != null && channels.length() > 0) {
            JSONObject channel = channels.optJSONObject(0);
            JSONArray alternatives = channel == null ? null : channel.optJSONArray("alternatives");
            if (alternatives != null && alternatives.length() > 0) {
                String transcript = alternatives.optJSONObject(0).optString("transcript", "").trim();
                if (!transcript.isEmpty()) {
                    return transcript;
                }
            }
        }
        throw new IOException("Deepgram response did not include transcript text.");
    }

    static List<String> defaultTranscriptionModels() {
        List<String> models = new ArrayList<>();
        models.add("nova-3");
        models.add("nova-3-general");
        models.add("nova-2");
        models.add("base-general");
        return models;
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IOException("Deepgram transcription failed (" + code + "): " + body);
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

    private static String requiredApiKey(Context context) {
        String apiKey = Prefs.deepgramApiKey(context);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Add your Deepgram API key in VoiceFlow Keyboard settings.");
        }
        return apiKey.trim();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
