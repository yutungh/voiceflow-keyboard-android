package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class SettingsActivity extends Activity {
    private TextView offlineFallbackValue;
    private TextView activeProfileValue;
    private CheckBox transformEnabledInput;
    private CheckBox translationEnabledInput;
    private TextView translationLanguageValue;
    private TextView chineseLayoutValue;
    private String selectedPreset;
    private boolean created;
    private boolean downloadingOfflineModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        requestAudioPermission();
        setTitle("VoiceFlow Keyboard");
        setContentView(buildContent());
        created = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (created) {
            setContentView(buildContent());
        }
    }

    private View buildContent() {
        selectedPreset = Prefs.activePreset(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(root, dp(16), dp(14), dp(16), dp(20));
        scroll.addView(root);

        root.addView(header());

        LinearLayout setup = section(root, "Setup");
        setup.addView(row("API keys", apiKeySummary(), ">", v -> startActivity(new Intent(this, ApiKeysActivity.class))));
        setup.addView(divider());
        setup.addView(row("Active keyboard", activeKeyboardSummary(), ">", v -> showInputMethodPicker()));

        LinearLayout voice = section(root, "Voice input");
        voice.addView(row("Voice model", modelSelectionSummary(true), ">", v -> openModelPicker(true)));
        voice.addView(divider());
        offlineFallbackValue = rowValue(offlineFallbackSummary());
        voice.addView(row("Offline fallback", offlineFallbackValue, ">", v -> prepareOfflineFallbackModel()));

        LinearLayout transform = section(root, "Text transform");
        transformEnabledInput = new CheckBox(this);
        transformEnabledInput.setChecked(Prefs.enableTransform(this));
        transform.addView(checkboxRow("Transform transcript", transformEnabledInput, this::saveCurrentSettings));
        transform.addView(divider());
        transform.addView(row("Transform model", modelSelectionSummary(false), ">", v -> openModelPicker(false)));
        transform.addView(divider());
        activeProfileValue = rowValue(Prefs.displayLabelForPreset(this, selectedPreset));
        transform.addView(row("Default voice style", activeProfileValue, ">", v -> showProfileDialog()));

        LinearLayout translation = section(root, "Translation");
        translationEnabledInput = new CheckBox(this);
        translationEnabledInput.setChecked(Prefs.translationEnabled(this));
        translation.addView(checkboxRow("Show translate button", translationEnabledInput, () -> {
            Prefs.setTranslationEnabled(this, translationEnabledInput.isChecked());
        }));
        translation.addView(divider());
        translationLanguageValue = rowValue(Prefs.translationTargetLanguage(this));
        translation.addView(row("Target language", translationLanguageValue, ">", v -> showTranslationLanguageDialog()));

        LinearLayout chinese = section(root, "Chinese input");
        CheckBox chineseEnabledInput = new CheckBox(this);
        chineseEnabledInput.setChecked(Prefs.chineseInputEnabled(this));
        chinese.addView(checkboxDetailRow(
                "Enable Chinese input",
                "Adds a 中/EN key beside 123 for pinyin typing and Chinese dictation",
                chineseEnabledInput,
                () -> {
                    Prefs.setChineseInputEnabled(this, chineseEnabledInput.isChecked());
                    setContentView(buildContent());
                }
        ));
        if (Prefs.chineseInputEnabled(this)) {
            chinese.addView(divider());
            chineseLayoutValue = rowValue(Prefs.chineseLayoutLabel(Prefs.chineseLayout(this)));
            chinese.addView(row("Pinyin layout", chineseLayoutValue, ">", v -> showChineseLayoutDialog()));
        }

        LinearLayout prompts = section(root, "Voice styles");
        List<PromptProfile> profiles = Prefs.promptProfiles(this);
        for (int i = 0; i < profiles.size(); i++) {
            PromptProfile profile = profiles.get(i);
            prompts.addView(voiceStyleRow(profile));
            prompts.addView(divider());
        }
        for (PromptProfile template : Prefs.hiddenVoiceStyleTemplates(this)) {
            String templateDetail = Prefs.isRelationshipStyle(template.id)
                    ? "Add relationship style"
                    : "Add built-in style";
            prompts.addView(row("+ " + template.displayName(), templateDetail, ">", v -> {
                Prefs.addVoiceStyleTemplate(this, template.id);
                setContentView(buildContent());
            }));
            prompts.addView(divider());
        }
        prompts.addView(row("+ New voice style", "Create a custom style", ">", v -> showNewPromptDialog()));

        LinearLayout replacements = section(root, "Personal vocabulary");
        replacements.addView(row(
                "Names, jargon, and commands",
                replacementSummary(),
                ">",
                v -> startActivity(new Intent(this, FindReplaceActivity.class))
        ));

        LinearLayout advanced = section(root, "Advanced");
        CheckBox cancelHiddenRecordingInput = new CheckBox(this);
        cancelHiddenRecordingInput.setChecked(Prefs.cancelRecordingWhenHidden(this));
        advanced.addView(checkboxDetailRow(
                "Cancel hidden recordings",
                "Stop and discard an active recording when the keyboard closes or the app changes",
                cancelHiddenRecordingInput,
                () -> Prefs.setCancelRecordingWhenHidden(this, cancelHiddenRecordingInput.isChecked())
        ));
        advanced.addView(divider());
        advanced.addView(row("Keyboard test", "Open test field", ">", v -> startActivity(new Intent(this, KeyboardTestActivity.class))));
        advanced.addView(divider());
        advanced.addView(row("Android keyboard settings", "System settings", ">", v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));

        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView title = text("VoiceFlow Keyboard", 26, true, Ui.TEXT);
        title.setIncludeFontPadding(false);
        header.addView(title);

        boolean ready = isProviderSetupReady();
        TextView status = text(setupStatus(), 13, true, ready ? Ui.ACCENT : Ui.MUTED);
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(dp(32));
        status.setBackground(Ui.rounded(this, ready ? Ui.ACCENT_SOFT : Ui.SURFACE_ALT, 16));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(0, dp(14), 0, 0);
        header.addView(status, statusParams);
        return header;
    }

    private String setupStatus() {
        if (!Prefs.hasApiKeyForProvider(this, Prefs.transcriptionProvider(this))) {
            return Prefs.providerLabel(Prefs.transcriptionProvider(this)) + " key needed for voice input";
        }
        if (Prefs.enableTransform(this) && !Prefs.hasApiKeyForProvider(this, Prefs.transformProvider(this))) {
            return Prefs.providerLabel(Prefs.transformProvider(this)) + " key needed for transform";
        }
        if (!isVoiceFlowActive()) {
            return "Keyboard installed, not active";
        }
        return "Ready";
    }

    private boolean isProviderSetupReady() {
        return Prefs.hasApiKeyForProvider(this, Prefs.transcriptionProvider(this))
                && (!Prefs.enableTransform(this) || Prefs.hasApiKeyForProvider(this, Prefs.transformProvider(this)));
    }

    private LinearLayout section(LinearLayout root, String title) {
        TextView label = text(title, 13, true, Ui.MUTED);
        label.setAllCaps(true);
        label.setLetterSpacing(0.04f);
        label.setPadding(0, dp(16), 0, dp(6));
        root.addView(label);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 18, Ui.DIVIDER));
        section.setPadding(0, dp(1), 0, dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(2));
        root.addView(section, params);
        return section;
    }

    private String modelSelectionSummary(boolean transcription) {
        String provider = transcription ? Prefs.transcriptionProvider(this) : Prefs.transformProvider(this);
        String model = transcription ? Prefs.transcriptionModel(this) : Prefs.transformModel(this);
        return Prefs.providerLabel(provider) + " - " + model;
    }

    private View row(String title, String value, String accessory, View.OnClickListener listener) {
        return row(title, rowValue(value), accessory, listener);
    }

    private View row(String title, TextView valueView, String accessory, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(14), dp(8), dp(10), dp(8));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setClickable(listener != null);
        if (listener != null) {
            row.setOnClickListener(listener);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(text(title, 16, true, Ui.TEXT));
        valueView.setPadding(0, dp(3), 0, 0);
        labels.addView(valueView);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView end = text(accessory, 20, true, Ui.MUTED);
        end.setGravity(Gravity.CENTER);
        row.addView(end, new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT));
        return row;
    }

    private View voiceStyleRow(PromptProfile profile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(12), dp(7), dp(10), dp(7));
        row.setClickable(true);
        row.setOnClickListener(v -> openPrompt(profile.id));

        if (!profile.icon.isEmpty()) {
            TextView icon = text(profile.icon, 21, false, Ui.TEXT);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.rounded(this, Ui.SURFACE_ALT, 12));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconParams.setMargins(0, 0, dp(11), 0);
            row.addView(icon, iconParams);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(text(profile.name, 16, true, Ui.TEXT));
        TextView detail = text("Tap to customize", 12, false, Ui.MUTED);
        detail.setPadding(0, dp(2), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView end = text(">", 20, true, Ui.MUTED);
        end.setGravity(Gravity.CENTER);
        row.addView(end, new LinearLayout.LayoutParams(dp(26), dp(38)));
        return row;
    }

    private View checkboxRow(String title, CheckBox checkBox, Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(62));
        row.setPadding(dp(16), dp(8), dp(10), dp(8));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setClickable(true);
        row.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
            onChanged.run();
        });

        TextView titleView = text(title, 16, true, Ui.TEXT);
        row.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        checkBox.setOnClickListener(v -> onChanged.run());
        row.addView(checkBox);
        return row;
    }

    private View checkboxDetailRow(
            String title,
            String subtitle,
            CheckBox checkBox,
            Runnable onChanged
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(66));
        row.setPadding(dp(14), dp(8), dp(10), dp(8));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
            onChanged.run();
        });

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, true, Ui.TEXT));
        TextView detail = text(subtitle, 12, false, Ui.MUTED);
        detail.setPadding(0, dp(3), dp(8), 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        checkBox.setOnClickListener(v -> onChanged.run());
        row.addView(checkBox);
        return row;
    }

    private TextView rowValue(String value) {
        TextView text = text(value == null || value.trim().isEmpty() ? "Not set" : value.trim(), 13, false, Ui.MUTED);
        text.setSingleLine(true);
        return text;
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

    private void openModelPicker(boolean transcription) {
        Intent intent = new Intent(this, ModelPickerActivity.class);
        intent.putExtra(ModelPickerActivity.EXTRA_MODE, transcription ? ModelPickerActivity.MODE_TRANSCRIPTION : ModelPickerActivity.MODE_TRANSFORM);
        startActivity(intent);
    }

    private void showProfileDialog() {
        String[] values = Prefs.selectablePresetValues(this);
        String[] labels = Prefs.labelsForPresets(this, values);
        new AlertDialog.Builder(this)
                .setTitle("Default voice style")
                .setItems(labels, (dialog, which) -> {
                    selectedPreset = values[which];
                    activeProfileValue.setText(labels[which]);
                    saveCurrentSettings();
                })
                .show();
    }

    private void showChineseLayoutDialog() {
        String[] values = {Prefs.CHINESE_LAYOUT_QWERTY, Prefs.CHINESE_LAYOUT_KEYPAD};
        String[] labels = new String[values.length];
        int checked = 0;
        String selected = Prefs.chineseLayout(this);
        for (int i = 0; i < values.length; i++) {
            labels[i] = Prefs.chineseLayoutLabel(values[i]);
            if (values[i].equals(selected)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Pinyin layout")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setChineseLayout(this, values[which]);
                    if (chineseLayoutValue != null) {
                        chineseLayoutValue.setText(Prefs.chineseLayoutLabel(values[which]));
                    }
                    dialog.dismiss();
                })
                .show();
    }

    private void showTranslationLanguageDialog() {
        String[] languages = Prefs.translationLanguages();
        String selected = Prefs.translationTargetLanguage(this);
        int checked = 0;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(selected)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Target language")
                .setSingleChoiceItems(languages, checked, (dialog, which) -> {
                    Prefs.setTranslationTargetLanguage(this, languages[which]);
                    translationLanguageValue.setText(languages[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCurrentSettings() {
        Prefs.save(
                this,
                Prefs.openAiApiKey(this),
                Prefs.transcriptionProvider(this),
                Prefs.transformProvider(this),
                Prefs.transcriptionModel(this),
                Prefs.transformModel(this),
                transformEnabledInput.isChecked(),
                selectedPreset
        );
    }

    private void openPrompt(String id) {
        Intent intent = new Intent(this, PromptEditorActivity.class);
        intent.putExtra(PromptEditorActivity.EXTRA_PROMPT_ID, id);
        startActivity(intent);
    }

    private void showNewPromptDialog() {
        EditText nameInput = input("Voice style name", 1, false);
        nameInput.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("New voice style")
                .setView(nameInput)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String id = Prefs.addPromptProfile(this, nameInput.getText().toString());
                    openPrompt(id);
                })
                .show();
    }

    private String apiKeySummary() {
        int count = Prefs.savedApiKeyCount(this);
        if (count == 0) {
            return "OpenAI not set";
        }
        if (count == 1 && Prefs.hasOpenAiApiKey(this)) {
            return "OpenAI connected";
        }
        return count + " keys saved";
    }

    private String replacementSummary() {
        int count = Prefs.userPhraseReplacements(this).size();
        return count == 0 ? "None" : count + " saved";
    }

    private String offlineFallbackSummary() {
        if (OfflineParakeetClient.isModelReady(this)) {
            return "Parakeet ready";
        }
        if (OfflineVoskClient.isModelReady(this)) {
            return "Vosk ready";
        }
        return downloadingOfflineModel ? "Downloading..." : "Download compact fallback";
    }

    private void prepareOfflineFallbackModel() {
        if (OfflineParakeetClient.isModelReady(this) || OfflineVoskClient.isModelReady(this)) {
            Toast.makeText(this, offlineFallbackSummary(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloadingOfflineModel) {
            return;
        }
        downloadingOfflineModel = true;
        if (offlineFallbackValue != null) {
            offlineFallbackValue.setText("Downloading...");
        }
        new Thread(() -> {
            try {
                OfflineVoskClient.ensureModel(getApplicationContext());
                runOnUiThread(() -> {
                    downloadingOfflineModel = false;
                    if (offlineFallbackValue != null) {
                        offlineFallbackValue.setText(offlineFallbackSummary());
                    }
                    Toast.makeText(this, "Offline fallback is ready", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Download failed" : e.getMessage();
                runOnUiThread(() -> {
                    downloadingOfflineModel = false;
                    if (offlineFallbackValue != null) {
                        offlineFallbackValue.setText(offlineFallbackSummary());
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Offline fallback")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "VoiceFlowOfflineModelDownload").start();
    }

    private String activeKeyboardSummary() {
        return isVoiceFlowActive() ? "VoiceFlow" : "Choose keyboard";
    }

    private boolean isVoiceFlowActive() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.contains("com.voiceflowkeyboard.ime/.VoiceFlowKeyboardService");
    }

    private void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showInputMethodPicker();
        }
    }

    private EditText input(String hint, int lines, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(lines == 1);
        input.setMinLines(lines);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) {
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return text;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
