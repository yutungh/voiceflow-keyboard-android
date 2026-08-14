package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class PromptEditorActivity extends Activity {
    static final String EXTRA_PROMPT_ID = "prompt_id";

    private String promptId;
    private EditText nameInput;
    private EditText promptInput;
    private EditText styleGuidanceInput;
    private TextView iconValue;
    private String selectedIcon = "";

    private static final String[] ICON_VALUES = {
            "", "💬", "🙂", "💼", "❤️", "🏠", "👥", "✨", "🎨", "🎉", "🧠", "📝", "⚡", "🌿", "🔥"
    };
    private static final String[] ICON_LABELS = {
            "None", "💬  Chat", "🙂  Friendly", "💼  Work", "❤️  Partner", "🏠  Family",
            "👥  Group", "✨  Custom", "🎨  Creative", "🎉  Fun", "🧠  Thoughtful",
            "📝  Writing", "⚡  Direct", "🌿  Calm", "🔥  Energetic", "Custom emoji…"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        promptId = getIntent().getStringExtra(EXTRA_PROMPT_ID);
        if (promptId == null || promptId.trim().isEmpty()) {
            promptId = Prefs.PRESET_CASUAL;
        }
        setTitle("Edit voice style");
        setContentView(buildContent());
    }

    private View buildContent() {
        PromptProfile profile = Prefs.promptProfile(this, promptId);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Ui.BACKGROUND);

        screen.addView(topBar(profile.name));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(24));
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout details = section(content, "Details");
        nameInput = input("Name", 1);
        nameInput.setText(profile.name);
        details.addView(field("Name", nameInput));
        selectedIcon = profile.icon;
        details.addView(divider());
        details.addView(iconRow());
        if (Prefs.canDeletePromptProfile(promptId)) {
            details.addView(divider());
            String action = Prefs.isHideableTemplate(promptId) ? "Hide style" : "Delete style";
            String detail = Prefs.isHideableTemplate(promptId)
                    ? "Remove from the recording picker"
                    : "Permanently remove this custom style";
            details.addView(actionRow(action, detail, Ui.DANGER, Ui.DANGER_SOFT, v -> confirmDelete()));
        }

        LinearLayout style = section(content, "Tone and audience");
        styleGuidanceInput = input("Describe how this should sound to the recipient", 7);
        styleGuidanceInput.setText(Prefs.styleGuidanceForPreset(this, profile.id));
        styleGuidanceInput.setGravity(Gravity.TOP | Gravity.START);
        style.addView(styleGuidanceInput);

        LinearLayout prompt = section(content, "Dictation cleanup");
        promptInput = input("Prompt", 18);
        promptInput.setText(Prefs.promptForPreset(this, profile.id));
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        prompt.addView(promptInput);

        return screen;
    }

    private View topBar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(bar, dp(16), dp(10), dp(16), dp(10));

        TextView back = Ui.topAction(this, "Back", false);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView label = Ui.text(this, title, 18, true, Ui.TEXT);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMargins(dp(12), 0, dp(12), 0);
        bar.addView(label, labelParams);

        TextView save = Ui.topAction(this, "Save", true);
        save.setOnClickListener(v -> savePrompt());
        bar.addView(save);
        return bar;
    }

    private LinearLayout section(LinearLayout root, String title) {
        TextView label = Ui.text(this, title, 12, true, Ui.MUTED);
        label.setAllCaps(true);
        label.setLetterSpacing(0.04f);
        label.setPadding(0, dp(18), 0, dp(7));
        root.addView(label);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(12), dp(10), dp(12), dp(12));
        section.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 18, Ui.DIVIDER));
        root.addView(section);
        return section;
    }

    private View field(String label, EditText input) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(Ui.text(this, label, 13, true, Ui.MUTED));
        field.addView(input);
        return field;
    }

    private View actionRow(String title, String subtitle, int color, int background, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(Ui.rounded(this, background, 14));
        row.setClickable(true);
        row.setOnClickListener(listener);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, title, 15, true, color));
        labels.addView(Ui.text(this, subtitle, 12, false, Ui.MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(this, ">", 18, true, color));
        return row;
    }

    private View iconRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        row.setClickable(true);
        row.setOnClickListener(v -> showIconPicker());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, "Style icon", 15, true, Ui.TEXT));
        TextView detail = Ui.text(this, "Optional emoji shown in the keyboard", 12, false, Ui.MUTED);
        detail.setPadding(0, dp(3), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        iconValue = Ui.text(this, "", 22, true, Ui.TEXT);
        iconValue.setGravity(Gravity.CENTER);
        iconValue.setMinWidth(dp(52));
        iconValue.setMinHeight(dp(40));
        iconValue.setPadding(dp(10), 0, dp(10), 0);
        iconValue.setBackground(Ui.rounded(this, Ui.SURFACE_ALT, 14));
        updateIconValue();
        row.addView(iconValue);
        return row;
    }

    private void showIconPicker() {
        int checked = -1;
        for (int i = 0; i < ICON_VALUES.length; i++) {
            if (ICON_VALUES[i].equals(selectedIcon)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose a style icon")
                .setSingleChoiceItems(ICON_LABELS, checked, (dialog, which) -> {
                    if (which < ICON_VALUES.length) {
                        selectedIcon = ICON_VALUES[which];
                        updateIconValue();
                        dialog.dismiss();
                    } else {
                        dialog.dismiss();
                        showCustomIconDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomIconDialog() {
        EditText custom = input("Emoji", 1);
        custom.setSingleLine(true);
        custom.setText(selectedIcon);
        custom.setSelection(custom.getText().length());
        int side = dp(20);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(side, dp(4), side, 0);
        wrapper.addView(custom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(this)
                .setTitle("Custom emoji")
                .setMessage("Enter one emoji or a short symbol.")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use", (dialog, which) -> {
                    selectedIcon = Prefs.sanitizeStyleIcon(custom.getText().toString());
                    updateIconValue();
                })
                .show();
    }

    private void updateIconValue() {
        if (iconValue == null) {
            return;
        }
        boolean empty = selectedIcon == null || selectedIcon.isEmpty();
        iconValue.setText(empty ? "None" : selectedIcon);
        iconValue.setTextSize(empty ? 12 : 22);
        iconValue.setTextColor(empty ? Ui.MUTED : Ui.TEXT);
    }

    private void savePrompt() {
        Prefs.savePromptProfile(
                this,
                promptId,
                nameInput.getText().toString(),
                selectedIcon,
                promptInput.getText().toString(),
                styleGuidanceInput.getText().toString()
        );
        Toast.makeText(this, "Voice style saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        boolean hideable = Prefs.isHideableTemplate(promptId);
        new AlertDialog.Builder(this)
                .setTitle(hideable ? "Hide voice style?" : "Delete voice style?")
                .setMessage(hideable
                        ? "Your wording is kept, and you can add this style again from Settings."
                        : "This permanently removes the custom style from Settings and the recording picker.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(hideable ? "Hide" : "Delete", (dialog, which) -> {
                    Prefs.deletePromptProfile(this, promptId);
                    Toast.makeText(this, hideable ? "Voice style hidden" : "Voice style deleted", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }

    private EditText input(String hint, int lines) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(lines == 1);
        input.setMinLines(lines);
        input.setPadding(0, dp(7), 0, dp(3));
        input.setBackgroundColor(0x00000000);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        params.setMargins(0, dp(10), 0, dp(10));
        divider.setLayoutParams(params);
        return divider;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
