package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelPickerActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_TRANSCRIPTION = "transcription";
    static final String MODE_TRANSFORM = "transform";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout list;
    private TextView titleView;
    private CheckBox showAllInput;
    private EditText manualInput;
    private boolean transcription;
    private String activeProvider;
    private boolean showingModels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        transcription = MODE_TRANSCRIPTION.equals(getIntent().getStringExtra(EXTRA_MODE));
        activeProvider = currentProvider();
        setTitle(pageTitle());
        setContentView(buildContent());
        renderProviders();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    private View buildContent() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Ui.BACKGROUND);
        screen.addView(topBar());

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        return screen;
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(bar, dp(16), dp(10), dp(16), dp(10));

        TextView back = Ui.topAction(this, "Back", false);
        back.setOnClickListener(v -> goBack());
        bar.addView(back);

        titleView = Ui.text(this, pageTitle(), 18, true, Ui.TEXT);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(12), 0, 0, 0);
        bar.addView(titleView, titleParams);
        return bar;
    }

    private void goBack() {
        if (showingModels) {
            renderProviders();
            return;
        }
        finish();
    }

    private void renderProviders() {
        showingModels = false;
        titleView.setText(pageTitle());
        root.removeAllViews();

        TextView note = Ui.text(this, transcription
                ? "Choose a voice provider, then choose the transcription model to use."
                : "Choose a transform provider, then choose the cleanup model to use.", 14, false, Ui.MUTED);
        note.setPadding(0, 0, 0, dp(8));
        root.addView(note);

        LinearLayout current = section(root, "Current");
        current.addView(row(selectionTitle(), selectionSubtitle(), "Selected", null));

        LinearLayout providers = section(root, "Providers");
        String[] ids = allProviderIds();
        for (int i = 0; i < ids.length; i++) {
            providers.addView(providerRow(ids[i]));
            if (i < ids.length - 1) {
                providers.addView(divider());
            }
        }
    }

    private View providerRow(String provider) {
        boolean supported = supportsMode(provider);
        boolean current = provider.equals(currentProvider());
        String accessory;
        if (!supported) {
            accessory = "Unavailable";
        } else {
            accessory = current ? "Current" : ">";
        }
        LinearLayout row = row(Prefs.providerLabel(provider), providerSubtitle(provider, supported, current), accessory, v -> {
            if (!supported) {
                Toast.makeText(this, capability(provider), Toast.LENGTH_SHORT).show();
                return;
            }
            activeProvider = provider;
            renderModels(provider);
        });
        row.setAlpha(supported ? 1f : 0.52f);
        return row;
    }

    private void renderModels(String provider) {
        showingModels = true;
        activeProvider = provider;
        titleView.setText(Prefs.providerLabel(provider));
        root.removeAllViews();

        TextView note = Ui.text(this, "Choose a model. Saving here also sets " + Prefs.providerLabel(provider) + " as the "
                + (transcription ? "voice input" : "transform") + " provider.", 14, false, Ui.MUTED);
        note.setPadding(0, 0, 0, dp(8));
        root.addView(note);

        LinearLayout providerSection = section(root, "Provider");
        providerSection.addView(row(Prefs.providerLabel(provider), providerModelSubtitle(provider), "Change", v -> renderProviders()));

        if (cloudApiRequired(provider) && !Prefs.hasApiKeyForProvider(this, provider)) {
            LinearLayout setup = section(root, "Setup");
            setup.addView(row("API key", Prefs.providerLabel(provider) + " key required for cloud use", ">", v -> startActivity(new Intent(this, ApiKeysActivity.class))));
        }

        if (liveModelListSupported(provider)) {
            LinearLayout settings = section(root, "View");
            showAllInput = new CheckBox(this);
            showAllInput.setChecked(Prefs.showAllOpenAiModels(this));
            settings.addView(checkRow("Show all compatible models", showAllInput, () -> {
                Prefs.setShowAllOpenAiModels(this, showAllInput.isChecked());
                loadModels(provider);
            }));
        }

        list = section(root, Prefs.showAllOpenAiModels(this) && liveModelListSupported(provider) ? "Models" : "Recommended models");
        loadModels(provider);

        if (manualModelAllowed(provider)) {
            LinearLayout manual = section(root, "Manual model ID");
            manualInput = new EditText(this);
            manualInput.setSingleLine(true);
            manualInput.setText(modelForProvider(provider));
            manualInput.setTextColor(Ui.TEXT);
            manualInput.setHintTextColor(Ui.MUTED);
            manualInput.setHint("model-id");
            manualInput.setPadding(dp(16), dp(10), dp(16), dp(10));
            manualInput.setBackgroundColor(0x00000000);
            manual.addView(manualInput);
            manual.addView(divider());
            manual.addView(row("Use manual model", "Save exactly what is typed above", ">", v -> selectModel(manualInput.getText().toString())));
        }
    }

    private void loadModels(String provider) {
        list.removeAllViews();
        list.addView(loadingRow("Loading models..."));
        String apiKey = Prefs.apiKeyForProvider(this, provider);
        boolean showAll = Prefs.showAllOpenAiModels(this);

        if (Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)) {
            renderModelsList(provider, OfflineVoskClient.defaultTranscriptionModels(), OfflineVoskClient.isModelReady(this)
                    ? "The local model is installed and runs on-device."
                    : "This model downloads on first use, then runs on-device.");
            return;
        }
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            renderModelsList(provider, OfflineParakeetClient.defaultTranscriptionModels(), OfflineParakeetClient.isModelReady(this)
                    ? "The high-accuracy local Parakeet model is installed and runs on-device."
                    : "Downloads about 600 MB on first use, then runs high-accuracy English transcription on-device.");
            return;
        }
        if (transcription && Prefs.PROVIDER_XAI.equals(provider)) {
            renderModelsList(provider, XAiClient.defaultTranscriptionModels(), "xAI speech-to-text currently uses this Grok transcription model.");
            return;
        }
        if (transcription && Prefs.PROVIDER_DEEPGRAM.equals(provider)) {
            renderModelsList(provider, DeepgramClient.defaultTranscriptionModels(), "Deepgram Nova models are recommended for pre-recorded dictation.");
            return;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            renderModelsList(provider, defaultModels(provider), "Add a " + Prefs.providerLabel(provider) + " key to refresh live model availability.");
            return;
        }

        String loadedProvider = provider;
        executor.execute(() -> {
            List<String> models;
            String message = showAll ? "All compatible models from " + Prefs.providerLabel(loadedProvider) + "." : "Recommended models only.";
            try {
                if (Prefs.PROVIDER_OPENAI.equals(loadedProvider)) {
                    List<String> all = OpenAiClient.listModels(apiKey);
                    if (transcription) {
                        models = showAll ? OpenAiClient.transcriptionModelsFrom(all) : OpenAiClient.recommendedTranscriptionModelsFrom(all);
                    } else {
                        models = showAll ? OpenAiClient.transformModelsFrom(all) : OpenAiClient.recommendedTransformModelsFrom(all);
                    }
                } else if (Prefs.PROVIDER_XAI.equals(loadedProvider)) {
                    models = showAll ? XAiClient.transformModelsFrom(XAiClient.listModels(apiKey)) : defaultModels(loadedProvider);
                } else if (Prefs.PROVIDER_ANTHROPIC.equals(loadedProvider)) {
                    models = showAll ? AnthropicClient.transformModelsFrom(AnthropicClient.listModels(apiKey)) : defaultModels(loadedProvider);
                } else {
                    models = defaultModels(loadedProvider);
                }
            } catch (Exception e) {
                models = defaultModels(loadedProvider);
                message = "Using defaults because live model loading failed.";
            }
            List<String> finalModels = new ArrayList<>(models);
            String finalMessage = message;
            runOnUiThread(() -> {
                if (showingModels && loadedProvider.equals(activeProvider)) {
                    renderModelsList(loadedProvider, finalModels, finalMessage);
                }
            });
        });
    }

    private void renderModelsList(String provider, List<String> models, String message) {
        list.removeAllViews();
        if (message != null && !message.trim().isEmpty()) {
            TextView note = Ui.text(this, message, 13, false, Ui.MUTED);
            note.setPadding(dp(16), dp(14), dp(16), dp(14));
            list.addView(note);
            list.addView(divider());
        }
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            list.addView(modelRow(provider, model));
            if (i < models.size() - 1) {
                list.addView(divider());
            }
        }
    }

    private View modelRow(String provider, String model) {
        boolean current = provider.equals(currentProvider()) && model.equalsIgnoreCase(currentModel());
        return row(model, current ? "Current" : modelDescription(model), current ? "Selected" : ">", v -> selectModel(model));
    }

    private LinearLayout row(String title, String subtitle, String accessory, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(listener != null);
        if (listener != null) {
            row.setOnClickListener(listener);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, title, 16, true, Ui.TEXT));
        TextView sub = Ui.text(this, subtitle, 13, false, Ui.MUTED);
        sub.setPadding(0, dp(5), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int accessorySize = ">".equals(accessory) ? 20 : 13;
        row.addView(Ui.text(this, accessory, accessorySize, true, selectedAccessory(accessory) ? Ui.ACCENT : Ui.MUTED));
        return row;
    }

    private View checkRow(String title, CheckBox checkBox, Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(10), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
            onChanged.run();
        });
        row.addView(Ui.text(this, title, 16, true, Ui.TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        checkBox.setOnClickListener(v -> onChanged.run());
        row.addView(checkBox);
        return row;
    }

    private LinearLayout section(LinearLayout parent, String title) {
        TextView label = Ui.text(this, title, 12, true, Ui.MUTED);
        label.setAllCaps(true);
        label.setLetterSpacing(0.04f);
        label.setPadding(0, dp(18), 0, dp(7));
        parent.addView(label);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 18, Ui.DIVIDER));
        parent.addView(section);
        return section;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        params.setMargins(dp(16), 0, 0, 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private View loadingRow(String text) {
        TextView view = Ui.text(this, text, 15, false, Ui.MUTED);
        view.setPadding(dp(16), dp(18), dp(16), dp(18));
        return view;
    }

    private void selectModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            Toast.makeText(this, "Enter a model ID first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (transcription
                && Prefs.PROVIDER_OPENAI.equals(activeProvider)
                && OpenAiClient.isRealtimeOnlyTranscriptionModel(trimmed)) {
            Toast.makeText(
                    this,
                    "GPT Live Transcribe is not compatible with recorded dictation. Choose GPT Transcribe.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (transcription) {
            Prefs.setTranscriptionProvider(this, activeProvider);
            Prefs.setTranscriptionModel(this, trimmed);
        } else {
            Prefs.setTransformProvider(this, activeProvider);
            Prefs.setTransformModel(this, trimmed);
        }
        Toast.makeText(this, transcription ? "Voice model saved" : "Transform model saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String selectionTitle() {
        return Prefs.providerLabel(currentProvider());
    }

    private String selectionSubtitle() {
        return currentModel();
    }

    private String providerSubtitle(String provider, boolean supported, boolean current) {
        if (!supported) {
            return capability(provider);
        }
        if (current) {
            return "Current: " + currentModel();
        }
        if (cloudApiRequired(provider) && !Prefs.hasApiKeyForProvider(this, provider)) {
            return capability(provider) + " - API key needed";
        }
        return capability(provider);
    }

    private String providerModelSubtitle(String provider) {
        if (provider.equals(currentProvider())) {
            return "Current model: " + currentModel();
        }
        if (cloudApiRequired(provider) && !Prefs.hasApiKeyForProvider(this, provider)) {
            return "API key needed before use";
        }
        return "Ready to choose a model";
    }

    private String capability(String provider) {
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
            return "Transform only";
        }
        if (Prefs.PROVIDER_DEEPGRAM.equals(provider)) {
            return "Transcription only";
        }
        if (Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)) {
            return OfflineVoskClient.isModelReady(this)
                    ? "Transcription only, offline model installed"
                    : "Transcription only, small local model";
        }
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.isModelReady(this)
                    ? "Transcription only, high-accuracy model installed"
                    : "Transcription only, high-accuracy local model";
        }
        if (Prefs.PROVIDER_XAI.equals(provider)) {
            return transcription ? "Grok speech-to-text" : "Grok transform";
        }
        return transcription ? "OpenAI speech-to-text" : "OpenAI transform";
    }

    private List<String> defaultModels(String provider) {
        if (transcription) {
            if (Prefs.PROVIDER_XAI.equals(provider)) {
                return XAiClient.defaultTranscriptionModels();
            }
            if (Prefs.PROVIDER_DEEPGRAM.equals(provider)) {
                return DeepgramClient.defaultTranscriptionModels();
            }
            if (Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)) {
                return OfflineVoskClient.defaultTranscriptionModels();
            }
            if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
                return OfflineParakeetClient.defaultTranscriptionModels();
            }
            return OpenAiClient.defaultTranscriptionModels();
        }
        if (Prefs.PROVIDER_XAI.equals(provider)) {
            return XAiClient.defaultTransformModels();
        }
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
            return AnthropicClient.defaultTransformModels();
        }
        return OpenAiClient.defaultTransformModels();
    }

    private String modelForProvider(String provider) {
        if (provider.equals(currentProvider())) {
            return currentModel();
        }
        return transcription ? Prefs.defaultTranscriptionModel(provider) : Prefs.defaultTransformModel(provider);
    }

    private String currentProvider() {
        return transcription ? Prefs.transcriptionProvider(this) : Prefs.transformProvider(this);
    }

    private String currentModel() {
        return transcription ? Prefs.transcriptionModel(this) : Prefs.transformModel(this);
    }

    private boolean supportsMode(String provider) {
        return transcription ? Prefs.supportsTranscription(provider) : Prefs.supportsTransform(provider);
    }

    private boolean cloudApiRequired(String provider) {
        return !Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)
                && !Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider);
    }

    private boolean liveModelListSupported(String provider) {
        return Prefs.PROVIDER_OPENAI.equals(provider)
                || (!transcription && (Prefs.PROVIDER_XAI.equals(provider) || Prefs.PROVIDER_ANTHROPIC.equals(provider)));
    }

    private boolean manualModelAllowed(String provider) {
        return !Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)
                && !Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider);
    }

    private boolean selectedAccessory(String accessory) {
        return "Selected".equals(accessory) || "Current".equals(accessory);
    }

    private String[] allProviderIds() {
        return new String[]{
                Prefs.PROVIDER_OPENAI,
                Prefs.PROVIDER_XAI,
                Prefs.PROVIDER_ANTHROPIC,
                Prefs.PROVIDER_DEEPGRAM,
                Prefs.PROVIDER_OFFLINE_PARAKEET,
                Prefs.PROVIDER_OFFLINE_VOSK
        };
    }

    private String pageTitle() {
        return transcription ? "Voice input" : "Transform";
    }

    private String modelDescription(String model) {
        String lower = model.toLowerCase();
        if ("gpt-transcribe".equals(lower)) {
            return "Recommended for recorded dictation";
        }
        if (lower.contains("mini")) {
            return "Faster and cheaper";
        }
        if (lower.contains("haiku")) {
            return "Fast Claude model";
        }
        if (lower.contains("sonnet")) {
            return "Balanced Claude model";
        }
        if (lower.contains("grok")) {
            return lower.contains("transcribe") ? "xAI speech-to-text" : "xAI transform model";
        }
        if (lower.contains("nova")) {
            return "Deepgram speech-to-text";
        }
        if (lower.contains("vosk")) {
            return "Small local offline speech-to-text";
        }
        if (lower.contains("parakeet")) {
            return "High-accuracy local English speech-to-text";
        }
        if (lower.contains("transcribe")) {
            return "Speech-to-text";
        }
        if (lower.contains("whisper")) {
            return "Legacy Whisper transcription";
        }
        return "Higher quality, usually slower";
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
