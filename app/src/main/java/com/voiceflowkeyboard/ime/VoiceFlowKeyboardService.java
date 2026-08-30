package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VoiceFlowKeyboardService extends InputMethodService {
    private static final int KEY_VISUAL_GAP_DP = 3;
    private static final int CORRECTION_DELAY_MS = 120;
    private static final int SHIFT_DOUBLE_TAP_MS = 350;
    private static final int SPACE_CURSOR_HOLD_MS = 280;
    private static final int SPACE_CURSOR_STEP_DP = 10;
    private static final Map<String, String> COMMON_TYPOS = commonTypos();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * Separate from {@link #executor} on purpose. That one is a single thread
     * shared with cloud transcription, retone, transform and model downloads;
     * a queued download runs for minutes and would head-of-line block every
     * keystroke's correction behind it.
     */
    private final ExecutorService typingExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TextView> keyButtons = new ArrayList<>();
    private final List<TextView> letterButtons = new ArrayList<>();

    private Palette colors;
    private FrameLayout keyboardSurface;
    private LinearLayout keyboardPanel;
    private LinearLayout voiceStyleOverlay;
    private LinearLayout chipStrip;
    private HorizontalScrollView chipScroller;
    private HorizontalScrollView voiceStyleScroller;
    private TextView statusText;
    private ImageButton translationButton;
    private ImageButton createButton;
    private ImageButton instructionButton;
    private ImageButton micButton;
    private TextView cancelRecordingButton;
    private ImageButton topHistoryButton;
    private TextView shiftButton;
    /** Space bars in the current layout: one normally, two when split. */
    private final List<TextView> spaceButtons = new ArrayList<>();
    private boolean recording;
    private boolean processing;
    private boolean translationCapture;
    private boolean creationCapture;
    private boolean instructionCapture;
    private boolean shift;
    private boolean autoShift;
    private boolean capsLock;
    private boolean symbolsMode;
    private boolean symbolsMoreMode;
    private boolean historyMode;
    private InputMode inputMode = InputMode.ENGLISH;
    private PinyinEngine pinyinEngine;
    private PinyinSession pinyinSession;
    private boolean deleteHeld;
    private boolean spaceCursorMode;
    private boolean offlineRecordingSession;
    private String offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
    private float downX;
    private float downY;
    private float rootDownX;
    private float rootDownY;
    private float spaceCursorLastStepX;
    private boolean rootSwipeConsumed;
    private boolean rootSwipeTracking;
    private boolean rootSwipeStartedOnSpace;
    private boolean rootSwipeStartedOnExpressionSlider;
    private boolean rootSwipeStartedOnVoiceStyleScroller;
    private boolean panelAnimating;
    private boolean retoneMode;
    private boolean funStylesExpanded;
    private long deleteHoldStartMs;
    private long lastShiftTapMs;
    private String selectedPreset;
    private int selectedExpression;
    private String lastVoiceRawTranscript = "";
    private String lastVoiceInsertedText = "";
    private String lastVoicePreset = "";
    private int lastVoiceExpression = Prefs.DEFAULT_EXPRESSION;
    private String lastVoiceOperation = VoiceHistoryItem.OPERATION_DICTATION;
    private String lastVoiceTargetLanguage = "";
    private String lastVoiceHistoryId = "";
    private int lastVoiceSelectionEnd = -1;
    private MediaRecorder recorder;
    private AudioRecord offlineRecorder;
    private Thread offlineRecordThread;
    private volatile boolean offlineRecordLoop;
    private File currentAudioFile;
    private File currentPcmFile;
    private EnglishEngine englishEngine;
    private Runnable deleteRepeatRunnable;
    private Runnable spaceCursorRunnable;
    private Runnable statusSpinnerRunnable;
    private ObjectAnimator translationLoadingAnimator;
    private ObjectAnimator createLoadingAnimator;
    private ObjectAnimator micLoadingAnimator;
    private ObjectAnimator instructionLoadingAnimator;
    private SeekBar expressionSlider;
    private Runnable correctionRunnable;
    private String statusSpinnerBase = "";
    private String translationTargetLanguage = "";
    private String instructionSourceText = "";
    private String pendingAutoCorrectWord = "";
    private String pendingAutoCorrectReplacement = "";
    private final List<String> pendingAutoCorrectSuggestions = new ArrayList<>();
    private String pendingAutoCompletePrefix = "";
    private final List<String> pendingAutoCompleteSuggestions = new ArrayList<>();
    private final Set<String> historyOriginalPreviewIds = new HashSet<>();
    private String lastAutoCorrectOriginal = "";
    private String lastAutoCorrectReplacement = "";
    private boolean pinyinCandidateStripExpanded;
    private int correctionGeneration;
    private int statusSpinnerStep;

    @Override
    public View onCreateInputView() {
        colors = Palette.from(this);
        // The framework calls this again after a configuration change, so this
        // is the one place geometry needs to be derived.
        metrics = KeyboardMetrics.from(getResources().getConfiguration());
        selectedPreset = Prefs.activePreset(this);
        selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
        if (!chineseAvailable() && inputMode.isChinese()) {
            // Chinese was switched off in Settings while the keyboard was away.
            inputMode = InputMode.ENGLISH;
            pinyinSession = null;
        }
        LinearLayout root = new SwipeRootLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(5), dp(5), dp(5), dp(6));
        root.setBackgroundColor(colors.background);

        root.addView(buildStrip());
        keyboardPanel = buildKeyboardPanel();
        keyboardPanel.setOnTouchListener(this::handleSwipe);
        keyboardSurface = new FrameLayout(this);
        keyboardSurface.addView(keyboardPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        voiceStyleOverlay = new LinearLayout(this);
        voiceStyleOverlay.setVisibility(View.GONE);
        keyboardSurface.addView(voiceStyleOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(keyboardSurface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(metrics.keyHeightDp * 4)
        ));
        showIdleChips();
        updateAutoCapitalization();
        return root;
    }

    @Override
    public void onDestroy() {
        stopRecorderSilently();
        executor.shutdownNow();
        typingExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        cancelRecordingIfKeyboardShouldReset();
        settlePinyin(PinyinSession.SettleReason.EDITOR_GONE);
        clearAutoCorrection();
        clearLastAutoCorrection();
        clearLastVoiceInsertion();
        if (!recording) {
            hideVoiceStyleOverlay();
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        restoreKeyboardForActiveInput();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // Now that the field is known, start the 1.3 MB spelling lexicon parse
        // if this one can use suggestions. Waiting for the first keystroke
        // would put the parse in the worst possible place; onCreate is too
        // early, because there is no field yet and a password box never needs
        // it at all.
        if (shouldTypingAssistance()) {
            englishEngine().prepare();
        }
        restoreKeyboardForActiveInput();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        restoreKeyboardForActiveInput();
    }

    private void restoreKeyboardForActiveInput() {
        if (recording) {
            showCaptureRecordingState();
        } else if (!processing) {
            resetToRegularKeyboard();
        }
    }

    @Override
    public void onFinishInput() {
        cancelRecordingIfKeyboardShouldReset();
        // The editor is going away: drop the buffer without writing anything.
        // Committing here risks landing text in whatever field comes next.
        settlePinyin(PinyinSession.SettleReason.EDITOR_GONE);
        clearAutoCorrection();
        clearLastAutoCorrection();
        clearLastVoiceInsertion();
        super.onFinishInput();
    }

    @Override
    public void onWindowHidden() {
        cancelRecordingIfKeyboardShouldReset();
        if (!recording && !processing) {
            resetToRegularKeyboard();
        }
        super.onWindowHidden();
    }

    @Override
    public void onUpdateSelection(
            int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd
    ) {
        super.onUpdateSelection(
                oldSelStart,
                oldSelEnd,
                newSelStart,
                newSelEnd,
                candidatesStart,
                candidatesEnd
        );
        // The cursor left the composing span, so the buffer no longer describes
        // where the text would land. Leave what is on screen and stop composing.
        //
        // Deliberately conservative: a missing composing region (candidatesStart
        // < 0, which some editors and WebViews always report) is NOT treated as
        // having moved away. Doing so would settle on our own setComposingText
        // callback and kill the composition on every single keystroke. A stale
        // buffer in such an editor is cleaned up by the other settle points.
        if (composingPinyin()
                && candidatesStart >= 0
                && (newSelStart < candidatesStart || newSelEnd > candidatesEnd)) {
            settlePinyin(PinyinSession.SettleReason.CURSOR_MOVED);
        }
        if (!lastVoiceRawTranscript.isEmpty()
                && lastVoiceSelectionEnd >= 0
                && (newSelStart != lastVoiceSelectionEnd || newSelEnd != lastVoiceSelectionEnd)) {
            clearLastVoiceInsertion();
        }
    }

    private boolean handleSwipe(View view, MotionEvent event) {
        return false;
    }

    private void cancelRecordingIfKeyboardShouldReset() {
        if (recording && !processing && Prefs.cancelRecordingWhenHidden(this)) {
            cancelRecording();
        }
    }

    private void resetToRegularKeyboard() {
        if (keyboardPanel == null || recording || processing) {
            return;
        }
        retoneMode = false;
        historyMode = false;
        symbolsMode = false;
        symbolsMoreMode = false;
        shift = false;
        capsLock = false;
        lastShiftTapMs = 0;
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        setRetoneTopControls(false);
        setHistoryControlsActive(false);
        populateKeyboardPanel(keyboardPanel);
        hideChipStrip();
        showIdleChips();
        updateAutoCapitalization();
        setStatus("Ready");
    }

    private boolean isHorizontalSwipe(float deltaX, float deltaY) {
        return Math.abs(deltaX) > dp(18) && Math.abs(deltaX) > Math.abs(deltaY) * 1.18f;
    }

    private boolean handleRootSwipe(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            rootDownX = event.getRawX();
            rootDownY = event.getRawY();
            rootSwipeConsumed = false;
            rootSwipeTracking = false;
            rootSwipeStartedOnSpace = isRawPointInsideAny(spaceButtons, rootDownX, rootDownY);
            rootSwipeStartedOnExpressionSlider = isRawPointInside(expressionSlider, rootDownX, rootDownY);
            rootSwipeStartedOnVoiceStyleScroller = isRawPointInside(
                    voiceStyleScroller,
                    rootDownX,
                    rootDownY
            );
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            animatePanelReset();
            rootSwipeConsumed = false;
            rootSwipeTracking = false;
            return false;
        }
        if (rootSwipeConsumed) {
            if (action == MotionEvent.ACTION_UP) {
                rootSwipeConsumed = false;
                rootSwipeTracking = false;
            }
            return true;
        }
        if (rootSwipeStartedOnSpace
                || rootSwipeStartedOnExpressionSlider
                || rootSwipeStartedOnVoiceStyleScroller
                || (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP)) {
            return false;
        }
        float deltaX = event.getRawX() - rootDownX;
        float deltaY = event.getRawY() - rootDownY;
        if (!rootSwipeTracking && (!canStartRootSwipe(deltaX, deltaY) || !canUseRootSwipe(deltaX))) {
            return false;
        }
        rootSwipeTracking = true;
        if (action == MotionEvent.ACTION_MOVE) {
            updateSwipePreview(deltaX);
            return true;
        }

        rootSwipeConsumed = true;
        rootSwipeTracking = false;
        boolean committed = Math.abs(deltaX) >= swipeCommitDistance();
        if (committed && canUseRootSwipe(deltaX)) {
            if (recording) {
                animatePanelReset();
                cyclePreset(deltaX < 0 ? 1 : -1);
                return true;
            }
            if (historyMode && deltaX > 0) {
                hideHistoryPanelAnimated();
                return true;
            }
        }
        animatePanelReset();
        return true;
    }

    private boolean canUseRootSwipe(float deltaX) {
        if (recording) {
            return !translationCapture && !creationCapture && !instructionCapture;
        }
        if (processing || panelAnimating) {
            return false;
        }
        return historyMode && deltaX > 0;
    }

    private boolean canStartRootSwipe(float deltaX, float deltaY) {
        if (!historyMode && !recording) {
            return false;
        }
        return isHorizontalSwipe(deltaX, deltaY);
    }

    private int swipeCommitDistance() {
        int panelWidth = keyboardPanel == null ? 0 : keyboardPanel.getWidth();
        if (!historyMode && !recording) {
            return Math.max(dp(96), panelWidth / 4);
        }
        return Math.max(dp(46), panelWidth / 7);
    }

    private void updateSwipePreview(float deltaX) {
        if (keyboardPanel == null || recording) {
            return;
        }
        int width = Math.max(keyboardPanel.getWidth(), dp(320));
        float max = width * 0.28f;
        float constrained = Math.max(-max, Math.min(max, deltaX));
        keyboardPanel.animate().cancel();
        keyboardPanel.setTranslationX(constrained * 0.45f);
        keyboardPanel.setAlpha(1f - Math.min(0.24f, Math.abs(constrained) / width));
    }

    private void animatePanelReset() {
        if (keyboardPanel == null || panelAnimating) {
            return;
        }
        keyboardPanel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(110)
                .start();
    }

    private boolean isRawPointInside(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private boolean isRawPointInsideAny(List<? extends View> views, float rawX, float rawY) {
        for (View view : views) {
            if (isRawPointInside(view, rawX, rawY)) {
                return true;
            }
        }
        return false;
    }

    private LinearLayout buildStrip() {
        // The input view can be rebuilt while the service and pinyin session
        // survive a configuration change. The new toolbar starts uncollapsed;
        // showPinyinCandidates() will expand it again when needed.
        pinyinCandidateStripExpanded = false;
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, 0, 0, dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout statusSlot = new FrameLayout(this);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, dp(36), 1f);
        statusParams.setMargins(dp(2), dp(2), dp(5), dp(2));
        top.addView(statusSlot, statusParams);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextColor(colors.text);
        statusText.setTextSize(14);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        statusText.setMarqueeRepeatLimit(2);
        statusText.setHorizontallyScrolling(true);
        statusText.setSelected(true);
        statusText.setAutoSizeTextTypeUniformWithConfiguration(
                11,
                14,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP
        );
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(6), 0, dp(10), 0);
        statusText.setBackgroundColor(Color.TRANSPARENT);
        statusSlot.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        chipStrip = new LinearLayout(this);
        chipStrip.setOrientation(LinearLayout.HORIZONTAL);
        chipStrip.setGravity(Gravity.CENTER_VERTICAL);
        chipScroller = new HorizontalScrollView(this);
        chipScroller.setHorizontalScrollBarEnabled(false);
        chipScroller.setVisibility(View.GONE);
        chipScroller.addView(chipStrip);
        statusSlot.addView(chipScroller, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        cancelRecordingButton = toolButton("Cancel");
        cancelRecordingButton.setTextColor(colors.onDanger);
        cancelRecordingButton.setBackground(keyBackground(colors.danger, true));
        cancelRecordingButton.setVisibility(View.INVISIBLE);
        cancelRecordingButton.setOnClickListener(v -> {
            haptic(v);
            if (retoneMode) {
                closeRetoneOverlay("Retone canceled");
            } else {
                cancelRecording();
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(76), dp(32));
        cancelParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        cancelRecordingButton.setLayoutParams(cancelParams);
        top.addView(cancelRecordingButton);

        if (Prefs.translationEnabled(this)) {
            translationLoadingAnimator = null;
            translationButton = translationButton();
            translationButton.setOnClickListener(v -> {
                haptic(v);
                toggleTranslationCapture();
            });
            top.addView(translationButton);
        } else {
            translationButton = null;
        }

        createLoadingAnimator = null;
        createButton = createButton();
        createButton.setOnClickListener(v -> {
            haptic(v);
            toggleCreationCapture();
        });
        top.addView(createButton);

        instructionLoadingAnimator = null;
        instructionButton = instructionButton();
        instructionButton.setOnClickListener(v -> {
            haptic(v);
            toggleInstructionCapture();
        });
        top.addView(instructionButton);

        micLoadingAnimator = null;
        micButton = micButton();
        micButton.setOnClickListener(v -> {
            haptic(v);
            toggleVoiceCapture();
        });
        top.addView(micButton);

        topHistoryButton = plainIconButton(R.drawable.ic_history_24, v -> {
            if (processing) {
                return;
            }
            haptic(v);
            if (historyMode) {
                hideHistoryPanelAnimated();
            } else {
                showHistoryPanelAnimated();
            }
        });
        top.addView(topHistoryButton);

        outer.addView(top);
        return outer;
    }

    private LinearLayout buildKeyboardPanel() {
        keyButtons.clear();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setOnTouchListener(this::handleSwipe);
        populateKeyboardPanel(panel);
        return panel;
    }

    private void populateKeyboardPanel(LinearLayout panel) {
        historyMode = false;
        panel.removeAllViews();
        keyButtons.clear();
        letterButtons.clear();
        shiftButton = null;
        spaceButtons.clear();
        if (symbolsMode) {
            if (symbolsMoreMode) {
                panel.addView(keyRow(new String[]{"[", "]", "{", "}", "#", "%", "^", "*", "+", "="}));
                panel.addView(keyRow(new String[]{"_", "\\", "|", "~", "<", ">", "â‚¬", "Â£", "Â¥", "â€¢"}));
            } else {
                panel.addView(keyRow(new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}));
                panel.addView(keyRow(new String[]{"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""}));
            }
            LinearLayout third = new LinearLayout(this);
            third.setOrientation(LinearLayout.HORIZONTAL);
            third.addView(keyButton(symbolsMoreMode ? "123" : "#+=", 1.2f, v -> toggleMoreSymbolsMode(), true));
            String[] thirdRow = symbolsMoreMode
                    ? new String[]{"`", "â€¦", "â€”", "â€“", "Â¿", "Â¡", "Â°"}
                    : new String[]{".", ",", "?", "!", "'", "_", "+"};
            int symbolSplit = splitIndexFor(KeyboardGeometry.Row.SYMBOLS_THIRD, thirdRow.length);
            for (int i = 0; i < thirdRow.length; i++) {
                if (i == symbolSplit) {
                    third.addView(splitSpacer());
                }
                third.addView(keyButton(thirdRow[i], 1f, v -> commitKey(((TextView) v).getText().toString())));
            }
            third.addView(deleteKey());
            panel.addView(third);
            panel.addView(bottomRow("ABC"));
            return;
        }

        if (inputMode.isKeypad()) {
            addKeypadRows(panel);
            panel.addView(bottomRow("?123"));
            return;
        }

        panel.addView(keyRow("qwertyuiop"));
        panel.addView(letterMiddleRow());

        LinearLayout third = new LinearLayout(this);
        third.setOrientation(LinearLayout.HORIZONTAL);
        shiftButton = keyButton("shift", 1.35f, v -> toggleShift(), true);
        third.addView(shiftButton);
        String bottomLetters = "zxcvbnm";
        int bottomSplit = splitIndexFor(KeyboardGeometry.Row.BOTTOM_LETTERS, bottomLetters.length());
        for (int i = 0; i < bottomLetters.length(); i++) {
            if (i == bottomSplit) {
                third.addView(splitSpacer());
            }
            third.addView(letterKeyButton(String.valueOf(bottomLetters.charAt(i)), 1f));
        }
        third.addView(deleteKey());
        panel.addView(third);
        panel.addView(bottomRow("?123"));
        updateShiftVisuals();
    }

    private void showHistoryPanel() {
        if (keyboardPanel == null || recording || processing) {
            return;
        }
        historyMode = true;
        symbolsMode = false;
        symbolsMoreMode = false;
        shift = false;
        autoShift = false;
        lastShiftTapMs = 0;
        clearAutoCorrection();
        hideChipStrip();
        setHistoryControlsActive(true);
        setStatus("History");
        keyboardPanel.removeAllViews();
        keyButtons.clear();
        letterButtons.clear();
        shiftButton = null;
        spaceButtons.clear();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOnTouchListener(this::handleSwipe);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(metrics.keyHeightDp * 4)
        ));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(4));
        scroll.addView(content);

        List<VoiceHistoryItem> history = Prefs.transcriptHistory(this);
        if (history.isEmpty()) {
            TextView empty = historyText("No transcripts yet.", 14, false);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(keyVisualBackground(colors.key, false));
            content.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(metrics.keyHeightDp * 4)
            ));
        } else {
            for (VoiceHistoryItem item : history) {
                content.addView(historyItemView(item));
            }
        }
        keyboardPanel.addView(scroll);
    }

    private void hideHistoryPanel() {
        if (keyboardPanel == null) {
            return;
        }
        historyMode = false;
        setHistoryControlsActive(false);
        hideChipStrip();
        populateKeyboardPanel(keyboardPanel);
        setStatus(capsLock ? "Caps lock" : "Ready");
        updateAutoCapitalization();
    }

    private void showHistoryPanelAnimated() {
        animateHistoryTransition(true);
    }

    private void hideHistoryPanelAnimated() {
        animateHistoryTransition(false);
    }

    private void animateHistoryTransition(boolean toHistory) {
        if (keyboardPanel == null || panelAnimating || processing) {
            return;
        }
        if (toHistory && (historyMode || recording)) {
            return;
        }
        if (!toHistory && !historyMode) {
            return;
        }
        panelAnimating = true;
        keyboardPanel.animate().cancel();
        int width = Math.max(keyboardPanel.getWidth(), dp(320));
        float outX = toHistory ? -width * 0.34f : width * 0.34f;
        float inX = toHistory ? width * 0.25f : -width * 0.25f;
        keyboardPanel.animate()
                .translationX(outX)
                .alpha(0.24f)
                .setDuration(90)
                .withEndAction(() -> {
                    if (toHistory) {
                        showHistoryPanel();
                    } else {
                        hideHistoryPanel();
                    }
                    keyboardPanel.setTranslationX(inX);
                    keyboardPanel.setAlpha(0.36f);
                    keyboardPanel.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(145)
                            .withEndAction(() -> {
                                keyboardPanel.setTranslationX(0f);
                                keyboardPanel.setAlpha(1f);
                                panelAnimating = false;
                            })
                            .start();
                })
                .start();
    }

    private View historyItemView(VoiceHistoryItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(keyBackground(colors.key, false));
        card.setOnTouchListener(this::handleSwipe);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(dp(2), dp(2), dp(2), dp(6));
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView meta = historyText(historyMeta(item), 11, true);
        meta.setTextColor(colors.text);
        meta.setAlpha(0.62f);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(meta, new LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
        ));
        header.addView(historyOpenButton(item));
        TextView outputMenu = historyOutputButton(item);
        header.addView(outputMenu);
        card.addView(header);

        boolean hasSelectedOutput = item.hasOutputForVariant(item.preset, item.expression);
        boolean transformedPreset = !Prefs.PRESET_RAW.equals(item.preset);
        boolean showingOriginal = transformedPreset
                && (!hasSelectedOutput || historyOriginalPreviewIds.contains(item.id));

        TextView previewMode = historyText(historyPreviewModeText(item, hasSelectedOutput, showingOriginal), 10, true);
        previewMode.setAlpha(0.62f);
        previewMode.setPadding(0, dp(7), 0, 0);
        card.addView(previewMode);

        TextView preview = historyText(compactPreview(historyVisibleText(item)), 14, false);
        preview.setMaxLines(4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(0, dp(4), 0, dp(10));
        card.addView(preview);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout leftActions = new LinearLayout(this);
        leftActions.setOrientation(LinearLayout.HORIZONTAL);
        if (hasSelectedOutput) {
            leftActions.addView(historyActionButton("Copy", v -> copyHistoryText(historyVisibleText(item)), false));
            leftActions.addView(historyActionButton("Paste", v -> pasteHistoryText(historyVisibleText(item)), true));
        } else {
            leftActions.addView(historyActionButton(
                    "Create " + historyOutputLabel(item.preset, item.expression),
                    v -> createHistoryTransform(item, item.preset, item.expression),
                    true
            ));
        }
        actions.addView(leftActions);
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1f));
        if (transformedPreset) {
            actions.addView(historyOriginalToggle(item, preview, previewMode, hasSelectedOutput));
        }
        card.addView(actions);
        return card;
    }

    private String historyVisibleText(VoiceHistoryItem item) {
        boolean hasSelectedOutput = item.hasOutputForVariant(item.preset, item.expression);
        boolean showOriginal = !hasSelectedOutput || historyOriginalPreviewIds.contains(item.id);
        return showOriginal ? item.rawText : item.outputForVariant(item.preset, item.expression);
    }

    private String historyPreviewModeText(VoiceHistoryItem item, boolean hasSelectedOutput, boolean showingOriginal) {
        String selectedVersion = historyOutputLabel(item.preset, item.expression);
        if (item.isTranslation() && !item.targetLanguage.isEmpty()) {
            selectedVersion = compactLanguageName(item.targetLanguage) + " - " + selectedVersion;
        }
        if (!hasSelectedOutput && !Prefs.PRESET_RAW.equals(item.preset)) {
            return "ORIGINAL SHOWN - " + selectedVersion.toUpperCase(Locale.US) + " NOT CREATED";
        }
        if (showingOriginal || Prefs.PRESET_RAW.equals(item.preset)) {
            return item.isTranslation() ? "ORIGINAL SOURCE" : item.isCreation() ? "CREATION REQUEST" : "ORIGINAL";
        }
        return selectedVersion.toUpperCase(Locale.US);
    }

    private Switch historyOriginalToggle(
            VoiceHistoryItem item,
            TextView preview,
            TextView previewMode,
            boolean hasSelectedOutput
    ) {
        Switch toggle = new Switch(this);
        toggle.setText("Show original");
        toggle.setTextSize(11);
        toggle.setTextColor(colors.text);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setIncludeFontPadding(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(6), 0, 0, 0);
        toggle.setMinWidth(0);
        toggle.setMinHeight(0);
        toggle.setChecked(!hasSelectedOutput || historyOriginalPreviewIds.contains(item.id));
        toggle.setEnabled(hasSelectedOutput);
        toggle.setAlpha(hasSelectedOutput ? 0.9f : 0.58f);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            haptic(toggle);
            if (checked) {
                historyOriginalPreviewIds.add(item.id);
            } else {
                historyOriginalPreviewIds.remove(item.id);
            }
            preview.setText(compactPreview(historyVisibleText(item)));
            previewMode.setText(historyPreviewModeText(item, true, checked));
        });
        toggle.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
        ));
        return toggle;
    }

    private String historyMeta(VoiceHistoryItem item) {
        String date = item.timestampMs > 0
                ? DateFormat.format("MMM d, yyyy 'at' h:mm a", item.timestampMs).toString()
                : "Recent";
        if (item.isTranslation() && !item.targetLanguage.isEmpty()) {
            return date + " - " + compactLanguageName(item.targetLanguage);
        }
        return date + (item.isCreation() ? " - Creation" : " - Dictation");
    }

    private TextView historyOutputButton(VoiceHistoryItem item) {
        TextView button = chip(historyOutputLabel(item.preset, item.expression), v -> showHistoryOutputMenu(v, item));
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(11), 0, dp(9), 0);
        button.setMaxWidth(dp(180));
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackground(keyBackground(colors.keyAlt, false));
        Drawable chevron = getDrawable(R.drawable.ic_chevron_down_24);
        if (chevron != null) {
            chevron.setBounds(0, 0, dp(14), dp(14));
            chevron.setTint(colors.text);
            button.setCompoundDrawablePadding(dp(5));
            button.setCompoundDrawables(null, null, chevron, null);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
        );
        params.setMargins(dp(8), dp(1), 0, dp(1));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton historyOpenButton(VoiceHistoryItem item) {
        ImageButton button = plainIconButton(R.drawable.ic_open_in_full_24, v -> {
            haptic(v);
            Intent intent = new Intent(this, TranscriptDetailActivity.class);
            intent.putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, item.id);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        button.setContentDescription("Open full transcript");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        params.setMargins(dp(4), 0, dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private String historyOutputLabel(String preset, int expression) {
        if (Prefs.PRESET_RAW.equals(preset)) {
            return "Original";
        }
        return Prefs.labelForPreset(this, preset) + " - " + Prefs.expressionLabel(expression);
    }

    private void showHistoryOutputMenu(View anchor, VoiceHistoryItem item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        List<String> variants = new ArrayList<>();
        variants.add(Prefs.PRESET_RAW);
        for (String preset : Prefs.selectablePresetValues(this)) {
            String variant = Prefs.historyVariantKey(preset, Prefs.expressionForPreset(this, preset));
            if (!variants.contains(variant)) {
                variants.add(variant);
            }
        }
        for (String variant : item.outputs.keySet()) {
            if (!variants.contains(variant)) {
                variants.add(variant);
            }
        }
        String selectedVariant = item.selectedVariantKey();
        for (int i = 0; i < variants.size(); i++) {
            String variant = variants.get(i);
            String preset = Prefs.historyVariantPreset(variant);
            int expression = Prefs.historyVariantExpression(variant);
            boolean available = item.hasOutputForVariant(preset, expression);
            String label = historyOutputLabel(preset, expression);
            if (!available) {
                label += " (not created)";
            }
            menu.getMenu().add(0, i, i, label)
                    .setCheckable(true)
                    .setChecked(variant.equals(selectedVariant));
        }
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(menuItem -> {
            int index = menuItem.getItemId();
            if (index < 0 || index >= variants.size()) {
                return false;
            }
            String variant = variants.get(index);
            historyOriginalPreviewIds.remove(item.id);
            Prefs.selectTranscriptHistoryVariant(
                    this,
                    item.id,
                    Prefs.historyVariantPreset(variant),
                    Prefs.historyVariantExpression(variant)
            );
            showHistoryPanel();
            return true;
        });
        menu.show();
    }

    private TextView historyActionButton(String text, View.OnClickListener listener, boolean primary) {
        TextView button = chip(text, listener);
        button.setTextSize(11);
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setBackground(keyBackground(primary ? colors.accent : colors.keyAlt, primary));
        if (primary) {
            button.setTextColor(colors.onAccent);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
        );
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView historyText(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(colors.text);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setIncludeFontPadding(false);
        return view;
    }

    private String compactPreview(String text) {
        String compact = text == null ? "" : text.replace('\n', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        return compact.isEmpty() ? "(empty)" : compact;
    }

    private LinearLayout bottomRow(String modeLabel) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        String normalizedMode = modeLabel.replace("?", "");
        bottom.addView(keyButton(normalizedMode, 1.4f, v -> toggleSymbolsMode(), true));
        boolean showLanguageKey = chineseAvailable();
        if (showLanguageKey) {
            // Sits immediately right of 123; the space bar gives up the width.
            bottom.addView(languageKey());
        }
        float spaceWeight = symbolsMode ? 5.45f : 5.7f;
        if (showLanguageKey) {
            spaceWeight -= 1.15f;
        }
        if (metrics.split) {
            // A single space bar spanning the gap would be unreachable in the
            // middle and would visually bridge the two halves. Give each thumb
            // its own; both carry the same tap and cursor-drag behaviour.
            bottom.addView(spaceKey(spaceWeight / 2f));
            bottom.addView(splitSpacer());
            bottom.addView(spaceKey(spaceWeight / 2f));
        } else {
            bottom.addView(spaceKey(spaceWeight));
        }
        if (!symbolsMode) {
            String stop = inputMode.isChinese() ? "ã€‚" : ".";
            bottom.addView(keyButton(stop, 0.9f, v -> commitSeparator(stop)));
        }
        bottom.addView(keyButton("return", 1.65f, v -> sendEnter(), true));
        return bottom;
    }

    private LinearLayout letterMiddleRow() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        // The edge dead zones exist to stop fat-finger misses at the screen
        // border. Split apart, the halves are nowhere near the border and the
        // padding just eats reachable width, so drop it.
        if (!metrics.split) {
            outer.addView(edgeDeadZone(10));
            outer.addView(edgeHitZone("a", 14));
        }
        outer.addView(keyRow("asdfghjkl", KeyboardGeometry.Row.MIDDLE), new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        if (!metrics.split) {
            outer.addView(edgeHitZone("l", 14));
            outer.addView(edgeDeadZone(10));
        }
        return outer;
    }

    private View edgeDeadZone(int widthDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(widthDp),
                keyHeightPx()
        ));
        return view;
    }

    private View edgeHitZone(String key, int widthDp) {
        View view = new View(this);
        view.setClickable(true);
        view.setOnClickListener(v -> {
            haptic(v);
            commitKey(key);
        });
        view.setLayoutParams(new LinearLayout.LayoutParams(
                dp(widthDp),
                keyHeightPx()
        ));
        return view;
    }

    private LinearLayout keyRow(String chars) {
        return keyRow(chars, KeyboardGeometry.Row.TOP);
    }

    private LinearLayout keyRow(String chars, KeyboardGeometry.Row rowId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int splitAt = splitIndexFor(rowId, chars.length());
        for (int i = 0; i < chars.length(); i++) {
            if (i == splitAt) {
                row.addView(splitSpacer());
            }
            row.addView(letterKeyButton(String.valueOf(chars.charAt(i)), 1f));
        }
        return row;
    }

    private LinearLayout keyRow(String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int splitAt = splitIndexFor(KeyboardGeometry.Row.SYMBOLS, labels.length);
        for (int i = 0; i < labels.length; i++) {
            if (i == splitAt) {
                row.addView(splitSpacer());
            }
            row.addView(keyButton(labels[i], 1f, v -> commitKey(((TextView) v).getText().toString())));
        }
        return row;
    }

    private void showIdleChips() {
        // Candidates outrank everything: while composing, the strip is the only
        // way to choose a character.
        if (showPinyinCandidates()) {
            return;
        }
        if (showRetoneChip()) {
            return;
        }
        if (showSpellingSuggestionChip()) {
            return;
        }
        if (showAutoCompleteChips()) {
            return;
        }
        hideChipStrip();
    }

    private boolean showRetoneChip() {
        if (chipStrip == null
                || recording
                || processing
                || historyMode
                || lastVoiceRawTranscript.isEmpty()
                || lastVoiceInsertedText.isEmpty()
                || lastVoiceHistoryId.isEmpty()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        String label = "Retone - " + Prefs.labelForPreset(this, lastVoicePreset)
                + " - " + Prefs.expressionLabel(lastVoiceExpression);
        TextView retone = chip(label, v -> openRetoneOverlay());
        retone.setTextColor(colors.onAccent);
        retone.setBackground(keyBackground(colors.accent, true));
        chipStrip.addView(retone);
        return true;
    }

    private boolean showSpellingSuggestionChip() {
        if (chipStrip == null || recording || processing || pendingAutoCorrectReplacement.isEmpty()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        TextView original = chip(pendingAutoCorrectWord, v -> rejectPendingAutoCorrection());
        original.setBackground(keyBackground(colors.keyAlt, false));
        chipStrip.addView(original);

        TextView best = chip(pendingAutoCorrectReplacement, v -> applySpellingSuggestion());
        best.setTextColor(colors.onAccent);
        best.setBackground(keyBackground(colors.accent, true));
        chipStrip.addView(best);

        String alternate = alternateAutoCorrection();
        if (!alternate.isEmpty()) {
            TextView alt = chip(alternate, v -> applySpellingSuggestion(alternate));
            chipStrip.addView(alt);
        }
        return true;
    }

    private boolean showAutoCompleteChips() {
        if (chipStrip == null || recording || processing || historyMode || pendingAutoCompleteSuggestions.isEmpty()) {
            return false;
        }
        chipStrip.removeAllViews();
        showChipStrip();
        for (String suggestion : pendingAutoCompleteSuggestions) {
            chipStrip.addView(chip(suggestion, v -> applyAutoCompleteSuggestion(((TextView) v).getText().toString())));
        }
        return true;
    }

    private void showRecordingChips() {
        chipStrip.removeAllViews();
        showChipStrip();
        for (String value : Prefs.selectablePresetValues(this)) {
            final String preset = value;
            TextView chip = chip(Prefs.displayLabelForPreset(this, preset), v -> {
                selectedPreset = preset;
                selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
                Prefs.setActivePreset(this, selectedPreset);
                showRecordingChips();
                setStatus(recording ? captureStyleStatus("Recording") : "Preset: " + selectedPresetLabel());
            });
            stylePresetChip(chip, preset.equals(selectedPreset));
            chipStrip.addView(chip);
        }
    }

    private void showVoiceStyleOverlay() {
        if (voiceStyleOverlay == null) {
            return;
        }
        boolean entering = voiceStyleOverlay.getVisibility() != View.VISIBLE;
        if (entering) {
            funStylesExpanded = false;
        }
        voiceStyleOverlay.animate().cancel();
        voiceStyleOverlay.removeAllViews();
        voiceStyleOverlay.setOrientation(LinearLayout.VERTICAL);
        voiceStyleOverlay.setPadding(dp(6), dp(3), dp(6), dp(5));
        voiceStyleOverlay.setBackgroundColor(Color.argb(
                248,
                Color.red(colors.background),
                Color.green(colors.background),
                Color.blue(colors.background)
        ));

        String context = retoneMode
                ? "Retone"
                : translationCapture && !translationTargetLanguage.isEmpty()
                        ? "Voice style - " + compactLanguageName(translationTargetLanguage)
                        : "Voice style";
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView header = historyText(context, 12, true);
        header.setTextColor(colors.text);
        header.setAlpha(0.76f);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(6), 0);
        headerRow.addView(header, new LinearLayout.LayoutParams(
                0,
                dp(24)
                , 1f
        ));
        if (retoneMode) {
            TextView apply = historyActionButton("Apply", v -> applyRetone(), true);
            headerRow.addView(apply);
        }
        voiceStyleOverlay.addView(headerRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26)
        ));

        List<PromptProfile> styles = Prefs.promptProfiles(this);
        List<PromptProfile> regularStyles = new ArrayList<>();
        List<PromptProfile> funStyles = new ArrayList<>();
        for (PromptProfile style : styles) {
            if (Prefs.isFunVoiceStyle(style.id)) {
                funStyles.add(style);
            } else {
                regularStyles.add(style);
            }
        }
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        voiceStyleScroller = scroller;
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setSmoothScrollingEnabled(true);
        scroller.setHorizontalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(dp(24));
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setContentDescription("Swipe left or right to browse voice styles");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(1), 0, dp(5), 0);
        TextView selectedButton = null;
        for (PromptProfile style : regularStyles) {
            TextView button = addVoiceStyleButton(row, style);
            if (style.id.equals(selectedPreset)) {
                selectedButton = button;
            }
        }
        if (!funStyles.isEmpty()) {
            TextView toggle = funStylesToggleButton(funStyles);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
            );
            toggleParams.setMargins(dp(8), dp(2), dp(2), dp(2));
            row.addView(toggle, toggleParams);
            if (!funStylesExpanded && Prefs.isFunVoiceStyle(selectedPreset)) {
                selectedButton = toggle;
            }
            if (funStylesExpanded) {
                for (PromptProfile style : funStyles) {
                    TextView button = addVoiceStyleButton(row, style);
                    if (style.id.equals(selectedPreset)) {
                        selectedButton = button;
                    }
                }
            }
        }
        scroller.addView(row);
        voiceStyleOverlay.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        ));
        TextView buttonToReveal = selectedButton;
        if (buttonToReveal != null) {
            scroller.post(() -> {
                int centeredX = buttonToReveal.getLeft()
                        - Math.max(0, (scroller.getWidth() - buttonToReveal.getWidth()) / 2);
                scroller.smoothScrollTo(Math.max(0, centeredX), 0);
            });
        }

        View divider = new View(this);
        divider.setBackgroundColor(colors.stroke);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.setMargins(dp(3), dp(3), dp(3), dp(2));
        voiceStyleOverlay.addView(divider, dividerParams);
        voiceStyleOverlay.addView(expressionControl(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        voiceStyleOverlay.setVisibility(View.VISIBLE);
        voiceStyleOverlay.setClickable(true);
        if (entering) {
            voiceStyleOverlay.setAlpha(0f);
            voiceStyleOverlay.animate().alpha(1f).setDuration(140).start();
        } else {
            voiceStyleOverlay.setAlpha(1f);
        }
    }

    private TextView voiceStyleButton(PromptProfile style) {
        boolean selected = style.id.equals(selectedPreset);
        TextView button = historyText(style.displayName(), 12, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinWidth(dp(68));
        button.setMaxWidth(dp(150));
        button.setTextColor(selected ? colors.onAccent : colors.text);
        button.setBackground(keyBackground(selected ? colors.accent : colors.keyAlt, selected));
        button.setContentDescription("Voice style " + style.name + (selected ? ", selected" : ""));
        button.setOnClickListener(v -> {
            if ((!recording && !retoneMode) || processing || instructionCapture) {
                return;
            }
            haptic(v);
            selectedPreset = style.id;
            selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
            Prefs.setActivePreset(this, selectedPreset);
            showVoiceStyleOverlay();
            setStatus(captureStyleStatus(retoneMode ? "Retone" : "Recording"));
        });
        return button;
    }

    private TextView addVoiceStyleButton(LinearLayout row, PromptProfile style) {
        TextView button = voiceStyleButton(style);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
        return button;
    }

    private TextView funStylesToggleButton(List<PromptProfile> funStyles) {
        PromptProfile selectedFunStyle = null;
        for (PromptProfile style : funStyles) {
            if (style.id.equals(selectedPreset)) {
                selectedFunStyle = style;
                break;
            }
        }
        boolean selectedAndCollapsed = selectedFunStyle != null && !funStylesExpanded;
        String title = selectedAndCollapsed
                ? selectedFunStyle.displayName()
                : "ðŸŽ­ Fun (" + funStyles.size() + ")";
        TextView button = historyText(title + (funStylesExpanded ? "  â—‚" : "  â–¸"), 12, true);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinWidth(dp(92));
        button.setMaxWidth(dp(180));
        button.setTextColor(selectedAndCollapsed ? colors.onAccent : colors.text);
        button.setBackground(keyBackground(
                selectedAndCollapsed ? colors.accent : colors.keyAlt,
                selectedAndCollapsed
        ));
        button.setContentDescription((funStylesExpanded ? "Collapse" : "Expand")
                + " fun voice styles, " + funStyles.size() + " available");
        button.setOnClickListener(v -> {
            if ((!recording && !retoneMode) || processing || instructionCapture) {
                return;
            }
            haptic(v);
            funStylesExpanded = !funStylesExpanded;
            showVoiceStyleOverlay();
        });
        return button;
    }

    private View expressionControl() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), 0, dp(4), 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = historyText("Expression", 11, true);
        title.setAlpha(0.72f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(22), 1f));
        TextView current = historyText(Prefs.expressionLabel(selectedExpression), 11, true);
        current.setTextColor(colors.accent);
        current.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        current.setPadding(dp(8), 0, dp(5), 0);
        titleRow.addView(current, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(22)
        ));
        panel.addView(titleRow);

        List<TextView> detentLabels = new ArrayList<>();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Reserved", "Subtle", "Natural", "Lively", "Expressive"};
        for (int i = 0; i < names.length; i++) {
            TextView label = historyText(names[i], 9, i == selectedExpression);
            label.setGravity(Gravity.CENTER);
            styleExpressionDetentLabel(label, i == selectedExpression);
            detentLabels.add(label);
            labels.addView(label, new LinearLayout.LayoutParams(0, dp(18), 1f));
        }

        SeekBar slider = new SeekBar(this);
        expressionSlider = slider;
        slider.setMax(Prefs.EXPRESSION_EXPRESSIVE);
        slider.setProgress(selectedExpression);
        slider.setPadding(dp(7), 0, dp(7), 0);
        slider.setContentDescription("Expression: " + Prefs.expressionLabel(selectedExpression));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int lastProgress = selectedExpression;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedExpression = Prefs.sanitizeExpression(progress);
                current.setText(Prefs.expressionLabel(selectedExpression));
                seekBar.setContentDescription("Expression: " + Prefs.expressionLabel(selectedExpression));
                for (int i = 0; i < detentLabels.size(); i++) {
                    styleExpressionDetentLabel(detentLabels.get(i), i == selectedExpression);
                }
                if (fromUser && progress != lastProgress) {
                    haptic(seekBar);
                }
                lastProgress = progress;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.setExpressionForPreset(VoiceFlowKeyboardService.this, selectedPreset, selectedExpression);
                setStatus(captureStyleStatus(retoneMode ? "Retone" : "Recording"));
            }
        });
        panel.addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
        ));
        panel.addView(labels);
        return panel;
    }

    private void styleExpressionDetentLabel(TextView label, boolean selected) {
        label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        label.setTextColor(selected ? colors.accent : colors.text);
        label.setAlpha(selected ? 1f : 0.56f);
    }

    private void hideVoiceStyleOverlay() {
        if (voiceStyleOverlay == null) {
            return;
        }
        voiceStyleOverlay.animate().cancel();
        voiceStyleOverlay.setVisibility(View.GONE);
        voiceStyleOverlay.removeAllViews();
        voiceStyleOverlay.setAlpha(1f);
        voiceStyleScroller = null;
        expressionSlider = null;
    }

    private void showChipStrip() {
        if (statusText != null) {
            statusText.setVisibility(View.GONE);
        }
        if (chipScroller != null) {
            chipScroller.setVisibility(View.VISIBLE);
        }
        if (chipStrip != null) {
            chipStrip.setVisibility(View.VISIBLE);
        }
    }

    private void hideChipStrip() {
        setPinyinCandidateStripExpanded(false);
        if (chipStrip != null) {
            chipStrip.removeAllViews();
            chipStrip.setVisibility(View.GONE);
        }
        if (chipScroller != null) {
            chipScroller.setVisibility(View.GONE);
        }
        if (statusText != null) {
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private void setHistoryControlsActive(boolean active) {
        if (translationButton != null) {
            translationButton.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        }
        if (createButton != null) {
            createButton.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        }
        if (instructionButton != null) {
            instructionButton.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        }
        if (micButton != null) {
            micButton.setVisibility(active ? View.INVISIBLE : View.VISIBLE);
        }
        if (topHistoryButton != null) {
            topHistoryButton.setVisibility(View.VISIBLE);
            topHistoryButton.setImageResource(active ? R.drawable.ic_keyboard_24 : R.drawable.ic_history_24);
        }
        updateRecordingControls();
    }

    private TextView chip(String text, View.OnClickListener listener) {
        TextView chip = toolButton(text);
        chip.setOnClickListener(v -> {
            haptic(v);
            listener.onClick(v);
        });
        return chip;
    }

    private void showPresetMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] presets = Prefs.selectablePresetValues(this);
        for (int i = 0; i < presets.length; i++) {
            menu.getMenu().add(0, i, i, Prefs.labelForPreset(this, presets[i]));
        }
        menu.getMenu().add(1, 100, 100, "Settings");
        menu.setOnMenuItemClickListener(item -> {
            haptic(anchor);
            if (item.getItemId() == 100) {
                openSettings();
                return true;
            }
            int index = item.getItemId();
            if (index >= 0 && index < presets.length) {
                selectedPreset = presets[index];
                selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
                Prefs.setActivePreset(this, selectedPreset);
                if (recording) {
                    showRecordingChips();
                    setStatus(captureStyleStatus("Recording"));
                } else {
                    setStatus("Preset: " + selectedPresetLabel());
                }
                return true;
            }
            return false;
        });
        menu.show();
    }

    private String presetDropdownText() {
        return labelForPreset(selectedPreset) + " â–¾";
    }

    private TextView toolButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(colors.text);
        view.setBackground(keyBackground(colors.key, false));
        view.setClickable(true);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        view.setLayoutParams(params);
        return view;
    }

    private ImageButton micButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_mic_24);
        button.setColorFilter(colors.text);
        button.setBackground(ovalBackground(colors.key, false));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton instructionButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_wand_24);
        button.setColorFilter(colors.text);
        button.setBackground(ovalBackground(colors.key, false));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription("Voice instruction");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton createButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_create_text_24);
        button.setColorFilter(colors.text);
        button.setBackground(ovalBackground(colors.key, false));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription("Create and append text");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton translationButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_translate_24);
        button.setColorFilter(colors.text);
        button.setBackground(ovalBackground(colors.key, false));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription("Translate voice to " + Prefs.translationTargetLanguage(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private ImageButton plainIconButton(int drawableRes, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setColorFilter(colors.text);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private TextView keyButton(String text, float weight, View.OnClickListener listener) {
        return keyButton(text, weight, listener, false);
    }

    private TextView keyButton(String text, float weight, View.OnClickListener listener, boolean utility) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(text.length() > 5 ? 10 : text.length() > 3 ? 11 : 18);
        view.setTypeface(utility || text.length() > 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(colors.text);
        view.setBackground(keyVisualBackground(utility ? colors.keyAlt : colors.key, false));
        view.setClickable(true);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
        view.setOnClickListener(v -> {
            haptic(v);
            listener.onClick(v);
        });
        view.setOnTouchListener(this::handleSwipe);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, keyHeightPx(), weight);
        params.setMargins(0, 0, 0, 0);
        view.setLayoutParams(params);
        keyButtons.add(view);
        return view;
    }

    private TextView letterKeyButton(String value, float weight) {
        TextView key = keyButton(displayLetter(value), weight, v -> commitKey(value));
        key.setTag(value);
        letterButtons.add(key);
        return key;
    }

    private String displayLetter(String value) {
        return isShiftActive() ? value.toUpperCase(Locale.US) : value.toLowerCase(Locale.US);
    }

    private TextView spaceKey(float weight) {
        TextView key = keyButton("space", weight, v -> {
        });
        spaceButtons.add(key);
        key.setOnTouchListener(this::handleSpaceTouch);
        return key;
    }

    private TextView deleteKey() {
        TextView key = keyButton("del", 1.35f, v -> {
        }, true);
        key.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                haptic(view);
                startDeleteHold();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopDeleteHold();
                return true;
            }
            return true;
        });
        return key;
    }

    private void stylePresetChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? colors.onAccent : colors.text);
        chip.setBackground(keyBackground(selected ? colors.accent : colors.key, selected));
    }

    private void toggleShift() {
        if (recording || processing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (capsLock) {
            capsLock = false;
            shift = false;
            autoShift = false;
            lastShiftTapMs = 0;
        } else if (autoShift && !shift) {
            autoShift = false;
            lastShiftTapMs = 0;
        } else if (shift && lastShiftTapMs > 0 && now - lastShiftTapMs <= SHIFT_DOUBLE_TAP_MS) {
            capsLock = true;
            shift = true;
            autoShift = false;
            lastShiftTapMs = 0;
        } else {
            shift = !shift;
            autoShift = false;
            lastShiftTapMs = shift ? now : 0;
        }
        updateShiftVisuals();
        setStatus(capsLock ? "Caps lock" : shift ? "Shift on" : "Ready");
    }

    private boolean isShiftActive() {
        return shift || autoShift || capsLock;
    }

    private void updateShiftVisuals() {
        boolean active = isShiftActive();
        for (TextView button : letterButtons) {
            Object tag = button.getTag();
            if (tag instanceof String) {
                button.setText(displayLetter((String) tag));
            }
        }
        if (shiftButton != null) {
            shiftButton.setText(capsLock ? "caps" : "shift");
            shiftButton.setTextColor(active ? colors.onAccent : colors.text);
            shiftButton.setBackground(keyVisualBackground(active ? colors.accent : colors.keyAlt, active));
        }
    }

    private boolean handleSpaceTouch(View view, MotionEvent event) {
        if (recording || processing) {
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            haptic(view);
            spaceCursorMode = false;
            spaceCursorLastStepX = event.getX();
            if (spaceCursorRunnable != null) {
                mainHandler.removeCallbacks(spaceCursorRunnable);
            }
            spaceCursorRunnable = () -> {
                if (recording || processing) {
                    return;
                }
                spaceCursorMode = true;
                clearAutoCorrection();
                setStatus("Cursor");
                haptic(view);
            };
            mainHandler.postDelayed(spaceCursorRunnable, SPACE_CURSOR_HOLD_MS);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (spaceCursorMode) {
                moveCursorFromSpaceDrag(event.getX());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean wasCursorMode = spaceCursorMode;
            stopSpaceCursorTracking();
            if (!wasCursorMode) {
                if (composingPinyin()) {
                    // Standard pinyin behaviour: space accepts the top candidate
                    // and does not insert a space of its own.
                    settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
                } else {
                    commitSeparator(" ");
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            stopSpaceCursorTracking();
            return true;
        }
        return true;
    }

    private void moveCursorFromSpaceDrag(float x) {
        int stepPx = Math.max(1, dp(SPACE_CURSOR_STEP_DP));
        int steps = (int) ((x - spaceCursorLastStepX) / stepPx);
        if (steps == 0) {
            return;
        }
        moveCursorBy(steps);
        spaceCursorLastStepX += steps * stepPx;
    }

    private void stopSpaceCursorTracking() {
        if (spaceCursorRunnable != null) {
            mainHandler.removeCallbacks(spaceCursorRunnable);
            spaceCursorRunnable = null;
        }
        spaceCursorMode = false;
    }

    private void moveCursorBy(int delta) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || delta == 0) {
            return;
        }
        if (!moveCursorWithExtractedText(connection, delta)) {
            int keyCode = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            for (int i = 0; i < Math.abs(delta); i++) {
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            }
        }
        clearLastVoiceInsertion();
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private boolean moveCursorWithExtractedText(InputConnection connection, int delta) {
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted == null || extracted.text == null) {
            return false;
        }
        int selection = extracted.selectionEnd >= 0 ? extracted.selectionEnd : extracted.selectionStart;
        if (selection < 0) {
            return false;
        }
        int min = extracted.startOffset;
        int max = extracted.startOffset + extracted.text.length();
        int current = Math.max(min, Math.min(max, extracted.startOffset + selection));
        int target = Math.max(min, Math.min(max, current + delta));
        if (target == current) {
            return true;
        }
        return connection.setSelection(target, target);
    }

    private void setKeyboardLocked(boolean locked) {
        for (TextView button : keyButtons) {
            button.setEnabled(!locked);
        }
        if (keyboardPanel != null) {
            keyboardPanel.setAlpha(locked ? 0.38f : 1f);
        }
    }

    private void commitKey(String value) {
        if (recording || processing) {
            return;
        }
        // In Chinese mode the letter and keypad keys feed the composer instead
        // of the editor. Symbols mode is unaffected and still types literally.
        if (!symbolsMode && handleChineseKey(value)) {
            return;
        }
        boolean letterKey = !symbolsMode && value.length() == 1 && Character.isLetter(value.charAt(0));
        String text = symbolsMode ? value : (isShiftActive() ? value.toUpperCase(Locale.US) : value.toLowerCase(Locale.US));
        if (isSeparator(text)) {
            commitSeparator(text);
            return;
        }
        commitText(text);
        if (letterKey && (shift || autoShift) && !capsLock) {
            shift = false;
            autoShift = false;
            lastShiftTapMs = 0;
            updateShiftVisuals();
        }
        if (isAutoCorrectWordCharacter(text)) {
            scheduleAutoCorrection();
        } else {
            clearAutoCorrection();
        }
    }

    private void commitSeparator(String separator) {
        if (recording || processing) {
            return;
        }
        lastShiftTapMs = 0;
        if (inputMode.isChinese()) {
            // Take the best reading first, then emit Chinese punctuation. None of
            // the English autocorrect or phrase-replacement machinery applies.
            settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
            commitText(fullWidthPunctuation(separator));
            showIdleChips();
            return;
        }
        if (applyTypingRule(separator)) {
            clearAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        // Spelling help is deliberately suggest-only. A separator commits
        // exactly what the user typed; only tapping a chip replaces a word.
        applyRecentPhraseReplacement();
        commitText(separator);
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    /**
     * Applies double-space-to-full-stop, or drops a space before closing
     * punctuation. Returns true when it handled the key.
     *
     * <p>Spelling suggestions are never applied from this path. A manually
     * chosen suggestion can only be reverted while it is still immediately in
     * front of the cursor; inserting punctuation naturally ends that window.
     */
    private boolean applyTypingRule(String separator) {
        if (!shouldTypingAssistance()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(2, 0);
        TypingRules.Outcome outcome = TypingRules.forSeparator(separator, before);
        if (!outcome.applies()) {
            return false;
        }
        clearLastVoiceInsertion();
        clearLastAutoCorrection();
        connection.deleteSurroundingText(outcome.deleteBefore, 0);
        connection.commitText(outcome.insert, 1);
        return true;
    }

    private void commitText(String text) {
        clearLastVoiceInsertion();
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.commitText(text, 1);
        }
    }

    private String insertVoiceText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        String prepared = prepareVoiceOutput(text);
        if (prepared.isEmpty()) {
            return "";
        }
        if (needsLeadingSpace(connection, prepared)) {
            prepared = " " + prepared;
        }
        if (!connection.commitText(prepared, 1)) {
            return "";
        }
        clearAutoCorrection();
        updateAutoCapitalization();
        return prepared;
    }

    private String appendCreatedText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            int end = Math.max(0, extracted.startOffset + extracted.text.length());
            if (!connection.setSelection(end, end)) {
                return "";
            }
        } else {
            CharSequence selected = connection.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                CharSequence before = connection.getTextBeforeCursor(200000, 0);
                int selectionEnd = (before == null ? 0 : before.length()) + selected.length();
                if (!connection.setSelection(selectionEnd, selectionEnd)) {
                    return "";
                }
            }
        }
        return insertVoiceText(text);
    }

    private void rememberLastVoiceInsertion(
            String rawTranscript,
            String insertedText,
            String preset,
            int expression,
            String operation,
            String targetLanguage,
            String historyId
    ) {
        if (rawTranscript == null
                || rawTranscript.trim().isEmpty()
                || insertedText == null
                || insertedText.isEmpty()
                || historyId == null
                || historyId.isEmpty()) {
            clearLastVoiceInsertion();
            return;
        }
        lastVoiceRawTranscript = rawTranscript.trim();
        lastVoiceInsertedText = insertedText;
        lastVoicePreset = preset;
        lastVoiceExpression = Prefs.sanitizeExpression(expression);
        lastVoiceOperation = VoiceHistoryItem.OPERATION_TRANSLATION.equals(operation)
                ? VoiceHistoryItem.OPERATION_TRANSLATION
                : VoiceHistoryItem.OPERATION_CREATION.equals(operation)
                        ? VoiceHistoryItem.OPERATION_CREATION
                        : VoiceHistoryItem.OPERATION_DICTATION;
        lastVoiceTargetLanguage = targetLanguage == null ? "" : targetLanguage;
        lastVoiceHistoryId = historyId;
        lastVoiceSelectionEnd = currentSelectionEnd();
    }

    private void clearLastVoiceInsertion() {
        lastVoiceRawTranscript = "";
        lastVoiceInsertedText = "";
        lastVoicePreset = "";
        lastVoiceExpression = Prefs.DEFAULT_EXPRESSION;
        lastVoiceOperation = VoiceHistoryItem.OPERATION_DICTATION;
        lastVoiceTargetLanguage = "";
        lastVoiceHistoryId = "";
        lastVoiceSelectionEnd = -1;
        if (chipStrip != null && !recording && !processing && !retoneMode) {
            showIdleChips();
        }
    }

    private void openRetoneOverlay() {
        if (processing || recording || lastVoiceRawTranscript.isEmpty() || lastVoiceHistoryId.isEmpty()) {
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Retone requires a connection");
            return;
        }
        String provider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, provider)) {
            setStatus("Add a " + Prefs.providerLabel(provider) + " key to retone");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        selectedPreset = lastVoicePreset;
        selectedExpression = lastVoiceExpression;
        retoneMode = true;
        hideChipStrip();
        setKeyboardLocked(true);
        setRetoneTopControls(true);
        showVoiceStyleOverlay();
        updateRecordingControls();
        setStatus(captureStyleStatus("Retone"));
    }

    private void closeRetoneOverlay(String status) {
        retoneMode = false;
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        setRetoneTopControls(false);
        updateRecordingControls();
        showIdleChips();
        setStatus(status);
    }

    private void setRetoneTopControls(boolean active) {
        if (translationButton != null) {
            translationButton.setEnabled(!active);
            translationButton.setAlpha(active ? 0.45f : 1f);
        }
        if (createButton != null) {
            createButton.setEnabled(!active);
            createButton.setAlpha(active ? 0.45f : 1f);
        }
        if (instructionButton != null) {
            instructionButton.setEnabled(!active);
            instructionButton.setAlpha(active ? 0.45f : 1f);
        }
        if (micButton != null) {
            micButton.setEnabled(!active);
            micButton.setAlpha(active ? 0.45f : 1f);
        }
        if (topHistoryButton != null) {
            topHistoryButton.setEnabled(!active);
            topHistoryButton.setAlpha(active ? 0.45f : 1f);
        }
    }

    private void applyRetone() {
        if (!retoneMode || processing) {
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Retone requires a connection");
            return;
        }
        final String raw = lastVoiceRawTranscript;
        final String oldInserted = lastVoiceInsertedText;
        final String historyId = lastVoiceHistoryId;
        final String operation = lastVoiceOperation;
        final String targetLanguage = lastVoiceTargetLanguage;
        final String preset = selectedPreset;
        final int expression = selectedExpression;

        Prefs.setActivePreset(this, preset);
        Prefs.setExpressionForPreset(this, preset, expression);
        retoneMode = false;
        processing = true;
        hideVoiceStyleOverlay();
        setRetoneTopControls(false);
        updateRecordingControls();
        setKeyboardLocked(true);
        startStatusSpinner("Retoning: " + labelForPreset(preset) + " - " + Prefs.expressionLabel(expression));
        executor.execute(() -> {
            try {
                String result;
                if (VoiceHistoryItem.OPERATION_TRANSLATION.equals(operation)) {
                    result = TransformClient.translate(this, raw, targetLanguage, preset, expression);
                } else if (VoiceHistoryItem.OPERATION_CREATION.equals(operation)) {
                    result = TransformClient.createText(this, raw, preset, expression);
                } else {
                    result = TransformClient.transform(this, raw, preset, expression);
                }
                if (result == null || result.trim().isEmpty()) {
                    throw new IllegalStateException("Retone returned no text.");
                }
                mainHandler.post(() -> {
                    Prefs.updateTranscriptHistory(this, historyId, raw, result, preset, expression);
                    String replacement = replaceLastVoiceInsertion(oldInserted, result);
                    if (!replacement.isEmpty()) {
                        rememberLastVoiceInsertion(
                                raw,
                                replacement,
                                preset,
                                expression,
                                operation,
                                targetLanguage,
                                historyId
                        );
                        finishProcessingState("Retoned - " + labelForPreset(preset)
                                + " - " + Prefs.expressionLabel(expression));
                    } else {
                        clearLastVoiceInsertion();
                        finishProcessingState("Retone saved in history; field changed");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Retone failed: " + concise(e)));
            }
        });
    }

    private String replaceLastVoiceInsertion(String oldInserted, String newText) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || oldInserted == null || oldInserted.isEmpty()) {
            return "";
        }
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            return "";
        }
        CharSequence before = connection.getTextBeforeCursor(oldInserted.length(), 0);
        if (before == null || !oldInserted.contentEquals(before)) {
            return "";
        }
        String prepared = prepareVoiceOutput(newText);
        if (prepared.isEmpty()) {
            return "";
        }
        if (oldInserted.startsWith(" ") && !prepared.startsWith(" ")) {
            prepared = " " + prepared;
        }
        connection.beginBatchEdit();
        try {
            if (!connection.deleteSurroundingText(oldInserted.length(), 0)) {
                return "";
            }
            return connection.commitText(prepared, 1) ? prepared : "";
        } finally {
            connection.endBatchEdit();
            clearAutoCorrection();
            updateAutoCapitalization();
        }
    }

    private int currentSelectionEnd() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return -1;
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        return extracted == null ? -1 : extracted.selectionEnd;
    }

    private boolean replaceWholeFieldText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null || text == null || text.trim().isEmpty()) {
            return false;
        }
        connection.beginBatchEdit();
        try {
            boolean selectedAll = connection.performContextMenuAction(android.R.id.selectAll);
            if (!selectedAll) {
                ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
                if (extracted == null || extracted.text == null) {
                    return false;
                }
                int start = Math.max(0, extracted.startOffset);
                if (!connection.setSelection(start, start + extracted.text.length())) {
                    return false;
                }
            }
            boolean replaced = connection.commitText(text, 1);
            if (replaced) {
                clearLastVoiceInsertion();
                clearAutoCorrection();
                updateAutoCapitalization();
            }
            return replaced;
        } finally {
            connection.endBatchEdit();
        }
    }

    private void pasteHistoryText(String text) {
        if (text == null || text.trim().isEmpty()) {
            setStatus("Nothing to paste");
            return;
        }
        clearLastVoiceInsertion();
        hideHistoryPanel();
        insertVoiceText(text);
        setStatus("Pasted");
    }

    private void copyHistoryText(String text) {
        if (text == null || text.trim().isEmpty()) {
            setStatus("Nothing to copy");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            setStatus("Clipboard unavailable");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow transcript", text.trim()));
        setStatus("Copied");
    }

    private void createHistoryTransform(VoiceHistoryItem item, String preset, int expression) {
        if (item.rawText.trim().isEmpty()) {
            setStatus("No raw transcript saved");
            return;
        }
        if (Prefs.PRESET_RAW.equals(preset)) {
            Prefs.selectTranscriptHistoryVariant(this, item.id, Prefs.PRESET_RAW, Prefs.DEFAULT_EXPRESSION);
            showHistoryPanel();
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("No connection for transform");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        String styleLabel = historyOutputLabel(preset, expression);
        startStatusSpinner(item.isTranslation()
                ? "Translating: " + styleLabel
                : "Creating " + styleLabel);
        executor.execute(() -> {
            try {
                String result;
                if (item.isTranslation()) {
                    result = TransformClient.translate(this, item.rawText, item.targetLanguage, preset, expression);
                } else if (item.isCreation()) {
                    result = TransformClient.createText(this, item.rawText, preset, expression);
                } else {
                    result = TransformClient.transform(this, item.rawText, preset, expression);
                }
                mainHandler.post(() -> {
                    Prefs.updateTranscriptHistory(this, item.id, item.rawText, result, preset, expression);
                    stopStatusSpinner();
                    recording = false;
                    processing = false;
                    setKeyboardLocked(false);
                    setHistoryControlsActive(true);
                    showHistoryPanel();
                    setStatus((item.isTranslation() ? "Translated - " : "Created ") + styleLabel);
                });
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Create failed: " + concise(e)));
            }
        });
    }

    private String prepareVoiceOutput(String text) {
        String result = text == null ? "" : text.trim();
        result = applyPhraseReplacements(result);
        result = removeShortTrailingPeriod(result);
        return result;
    }

    private String applyPhraseReplacements(String text) {
        return PersonalVocabulary.applyReplacements(text, Prefs.allPhraseReplacements(this));
    }

    private String removeShortTrailingPeriod(String text) {
        if (text.indexOf('\n') >= 0 || !text.endsWith(".") || text.endsWith("...")) {
            return text;
        }
        String withoutPeriod = text.substring(0, text.length() - 1).trim();
        int words = countWords(withoutPeriod);
        if (words > 0 && words < 5) {
            return withoutPeriod;
        }
        return text;
    }

    private int countWords(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            boolean word = Character.isLetterOrDigit(text.charAt(i));
            if (word && !inWord) {
                count++;
            }
            inWord = word;
        }
        return count;
    }

    private boolean needsLeadingSpace(InputConnection connection, String text) {
        if (text.isEmpty() || startsWithSpacingOrPunctuation(text)) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(1, 0);
        if (before == null || before.length() == 0) {
            return false;
        }
        char previous = before.charAt(before.length() - 1);
        return !Character.isWhitespace(previous) && "([{/'\"".indexOf(previous) < 0;
    }

    private boolean startsWithSpacingOrPunctuation(String text) {
        char first = text.charAt(0);
        return Character.isWhitespace(first) || ".,?!:;)]}/'\"".indexOf(first) >= 0;
    }

    private void deleteOne() {
        if (recording || processing) {
            return;
        }
        // While composing, backspace shortens the pinyin buffer rather than
        // deleting text the user has already committed.
        if (composingPinyin()) {
            pinyinSession.backspace();
            if (composingPinyin()) {
                refreshComposingText();
            } else {
                // The buffer is empty, so settle() would report NOTHING and the
                // editor would keep an orphaned composing span. Clear it here.
                clearComposingRegion();
                showIdleChips();
            }
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            clearLastVoiceInsertion();
            if (deleteSelectionIfAny(connection)) {
                scheduleAutoCorrection();
                updateAutoCapitalization();
                return;
            }
            if (undoLastAutoCorrectionIfPossible(connection)) {
                updateAutoCapitalization();
                return;
            }
            clearLastAutoCorrection();
            connection.deleteSurroundingText(1, 0);
        }
        scheduleAutoCorrection();
        updateAutoCapitalization();
    }

    private void deletePreviousWord() {
        if (recording || processing) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        clearLastVoiceInsertion();
        if (deleteSelectionIfAny(connection)) {
            scheduleAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        CharSequence before = connection.getTextBeforeCursor(80, 0);
        if (before == null || before.length() == 0) {
            return;
        }
        int i = before.length() - 1;
        int count = 0;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            i--;
            count++;
        }
        while (i >= 0 && !Character.isWhitespace(before.charAt(i))) {
            i--;
            count++;
        }
        clearLastAutoCorrection();
        connection.deleteSurroundingText(Math.max(count, 1), 0);
        updateAutoCapitalization();
    }

    private boolean deleteSelectionIfAny(InputConnection connection) {
        CharSequence selected = connection.getSelectedText(0);
        if (selected == null || selected.length() == 0) {
            return false;
        }
        clearLastAutoCorrection();
        clearAutoCorrection();
        connection.commitText("", 1);
        return true;
    }

    private void startDeleteHold() {
        if (recording || processing) {
            return;
        }
        deleteHeld = true;
        deleteHoldStartMs = System.currentTimeMillis();
        deleteOne();
        if (deleteRepeatRunnable == null) {
            deleteRepeatRunnable = () -> {
                if (!deleteHeld) {
                    return;
                }
                long heldMs = System.currentTimeMillis() - deleteHoldStartMs;
                if (heldMs >= 3000) {
                    deletePreviousWord();
                    mainHandler.postDelayed(deleteRepeatRunnable, 320);
                } else {
                    deleteOne();
                    mainHandler.postDelayed(deleteRepeatRunnable, 75);
                }
            };
        }
        mainHandler.postDelayed(deleteRepeatRunnable, 350);
    }

    private void stopDeleteHold() {
        deleteHeld = false;
        if (deleteRepeatRunnable != null) {
            mainHandler.removeCallbacks(deleteRepeatRunnable);
        }
    }

    private void scheduleAutoCorrection() {
        if (!shouldAutoCorrectTyping()) {
            clearAutoCorrection();
            return;
        }
        if (correctionRunnable == null) {
            correctionRunnable = this::requestAutoCorrectionForCurrentWord;
        }
        mainHandler.removeCallbacks(correctionRunnable);
        mainHandler.postDelayed(correctionRunnable, CORRECTION_DELAY_MS);
    }

    private void requestAutoCorrectionForCurrentWord() {
        if (!shouldAutoCorrectTyping()) {
            clearAutoCorrection();
            return;
        }
        String word = currentWordBeforeCursor();
        if (word.length() < 2 || containsDigit(word)) {
            clearAutoCorrection();
            return;
        }
        if (Prefs.isLearnedWord(this, word)) {
            clearAutoCorrection();
            return;
        }

        // The explicit table still outranks the lexicon. It is the only thing
        // that can fix a first-letter transposition ("hte") or expand a
        // contraction the lexicon has no typo for ("dont"), because correction
        // proper never changes the first letter.
        String fallback = fallbackCorrectionFor(word);
        if (!fallback.isEmpty()) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add(fallback);
            setPendingAutoCorrection(word, suggestions);
            return;
        }

        EnglishEngine engine = englishEngine();
        engine.prepare();
        if (!engine.isReady()) {
            // Still parsing, or the asset is unreadable. Say nothing rather
            // than guessing from a half-built lexicon.
            clearAutoCorrection();
            return;
        }
        engine.suggest(word, 3, ++correctionGeneration);
    }

    private EnglishEngine englishEngine() {
        if (englishEngine == null) {
            englishEngine = new EnglishEngine(
                    getAssets(), typingExecutor, mainHandler, this::applyEnglishSuggestions);
        }
        return englishEngine;
    }

    /**
     * Publishes a finished background pass, if it is still wanted.
     *
     * <p>Everything the request was predicated on is rechecked here, because
     * arbitrarily much can happen between dispatch and delivery: the user keeps
     * typing, switches field, switches to Chinese, or starts recording. The
     * generation check alone is not enough â€” {@link #clearAutoCorrection()} also
     * bumps it, so a stale result is dropped even when no newer request exists.
     */
    private void applyEnglishSuggestions(EnglishEngine.Suggestions suggestions) {
        if (suggestions.generation != correctionGeneration || !shouldAutoCorrectTyping()) {
            return;
        }
        String word = currentWordBeforeCursor();
        if (!suggestions.word.equals(word)) {
            // The word moved on under us; a newer request is already in flight.
            return;
        }
        if (Prefs.isLearnedWord(this, word)) {
            clearAutoCorrection();
            return;
        }
        if (suggestions.correction.isEmpty()) {
            setPendingAutoComplete(word, suggestions.completions);
            return;
        }
        List<String> cased = new ArrayList<>();
        for (String candidate : suggestions.correction.words) {
            addSuggestion(cased, matchCase(word, candidate));
        }
        if (cased.isEmpty()) {
            setPendingAutoComplete(word, suggestions.completions);
            return;
        }
        setPendingAutoCorrection(word, cased);
    }

    private boolean applySpellingSuggestion() {
        return applySpellingSuggestion(pendingAutoCorrectReplacement);
    }

    private boolean applySpellingSuggestion(String replacement) {
        if (replacement == null || replacement.trim().isEmpty()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        String word = currentWordBeforeCursor();
        if (!word.equals(pendingAutoCorrectWord)) {
            clearAutoCorrection();
            return false;
        }
        connection.deleteSurroundingText(word.length(), 0);
        connection.commitText(replacement, 1);
        rememberLastAutoCorrection(word, replacement);
        clearAutoCorrection();
        return true;
    }

    private void rejectPendingAutoCorrection() {
        if (!pendingAutoCorrectWord.isEmpty()) {
            learnAutoCorrectionWord(pendingAutoCorrectWord);
            setStatus("Kept " + pendingAutoCorrectWord);
        }
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private String alternateAutoCorrection() {
        for (String suggestion : pendingAutoCorrectSuggestions) {
            if (!suggestion.equals(pendingAutoCorrectReplacement)) {
                return suggestion;
            }
        }
        return "";
    }

    private void setPendingAutoCorrection(String word, List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            clearAutoCorrection();
            return;
        }
        pendingAutoCorrectWord = word;
        pendingAutoCompletePrefix = "";
        pendingAutoCompleteSuggestions.clear();
        pendingAutoCorrectSuggestions.clear();
        for (String suggestion : suggestions) {
            addSuggestion(pendingAutoCorrectSuggestions, suggestion);
        }
        if (pendingAutoCorrectSuggestions.isEmpty()) {
            clearAutoCorrection();
            return;
        }
        pendingAutoCorrectReplacement = pendingAutoCorrectSuggestions.get(0);
        // There is intentionally no auto-accept flag. The scorer may rank a
        // candidate, but replacing typed text always requires a chip tap.
        showIdleChips();
    }

    private void setPendingAutoComplete(String prefix, List<String> completions) {
        pendingAutoCorrectWord = "";
        pendingAutoCorrectReplacement = "";
        pendingAutoCorrectSuggestions.clear();
        pendingAutoCompletePrefix = prefix == null ? "" : prefix;
        pendingAutoCompleteSuggestions.clear();
        if (pendingAutoCompletePrefix.length() < 2 || containsDigit(pendingAutoCompletePrefix)) {
            hideChipStrip();
            return;
        }
        if (completions != null) {
            for (String completion : completions) {
                addCompletion(
                        pendingAutoCompleteSuggestions,
                        matchCase(pendingAutoCompletePrefix, completion));
                if (pendingAutoCompleteSuggestions.size() >= 3) {
                    break;
                }
            }
        }
        showIdleChips();
    }

    private void applyAutoCompleteSuggestion(String suggestion) {
        if (suggestion == null || suggestion.trim().isEmpty()) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        String word = currentWordBeforeCursor();
        if (!word.equals(pendingAutoCompletePrefix)) {
            clearAutoCorrection();
            return;
        }
        connection.deleteSurroundingText(word.length(), 0);
        connection.commitText(suggestion.trim() + " ", 1);
        clearLastAutoCorrection();
        clearAutoCorrection();
        updateAutoCapitalization();
    }

    private void clearAutoCorrection() {
        pendingAutoCorrectWord = "";
        pendingAutoCorrectReplacement = "";
        pendingAutoCorrectSuggestions.clear();
        pendingAutoCompletePrefix = "";
        pendingAutoCompleteSuggestions.clear();
        // Invalidate anything already running on the typing executor. Removing
        // the debounce callback only stops work that has not started; a pass
        // already in flight would otherwise come back and repopulate the chips
        // we are clearing here.
        correctionGeneration++;
        if (correctionRunnable != null) {
            mainHandler.removeCallbacks(correctionRunnable);
        }
        if (chipStrip != null && !recording && !processing) {
            hideChipStrip();
        }
    }

    private void addSuggestion(List<String> suggestions, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : suggestions) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        if (suggestions.size() < 2) {
            suggestions.add(trimmed);
        }
    }

    private void addCompletion(List<String> suggestions, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        for (String existing : suggestions) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        suggestions.add(trimmed);
    }

    private void rememberLastAutoCorrection(String original, String replacement) {
        lastAutoCorrectOriginal = original == null ? "" : original;
        lastAutoCorrectReplacement = replacement == null ? "" : replacement;
    }

    private void clearLastAutoCorrection() {
        lastAutoCorrectOriginal = "";
        lastAutoCorrectReplacement = "";
    }

    private boolean undoLastAutoCorrectionIfPossible(InputConnection connection) {
        if (lastAutoCorrectOriginal.isEmpty() || lastAutoCorrectReplacement.isEmpty()) {
            return false;
        }
        String tail = lastAutoCorrectReplacement;
        if (tail.isEmpty()) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(tail.length(), 0);
        if (before == null || !tail.contentEquals(before)) {
            clearLastAutoCorrection();
            return false;
        }
        connection.deleteSurroundingText(tail.length(), 0);
        connection.commitText(lastAutoCorrectOriginal, 1);
        learnAutoCorrectionWord(lastAutoCorrectOriginal);
        clearLastAutoCorrection();
        clearAutoCorrection();
        setStatus("Reverted");
        return true;
    }

    private void learnAutoCorrectionWord(String word) {
        if (!shouldLearnAutoCorrectionWord(word)) {
            return;
        }
        Prefs.learnWord(this, word);
    }

    private boolean shouldLearnAutoCorrectionWord(String word) {
        if (word == null || COMMON_TYPOS.containsKey(word.toLowerCase(Locale.US)) || containsDigit(word)) {
            return false;
        }
        String trimmed = word.trim();
        if (trimmed.length() < 2 || trimmed.length() > 40) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!isAutoCorrectWordCharacter(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void updateAutoCapitalization() {
        if (capsLock || shift || symbolsMode || recording || processing) {
            if (autoShift) {
                autoShift = false;
                updateShiftVisuals();
            }
            return;
        }
        boolean next = shouldAutoCapitalize();
        if (autoShift != next) {
            autoShift = next;
            updateShiftVisuals();
        }
    }

    private boolean shouldAutoCapitalize() {
        if (!shouldTypingAssistance()) {
            return false;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return false;
        }
        CharSequence before = connection.getTextBeforeCursor(80, 0);
        if (before == null || before.length() == 0) {
            return true;
        }
        for (int i = before.length() - 1; i >= 0; i--) {
            char value = before.charAt(i);
            if (value == '\n') {
                return true;
            }
            if (Character.isWhitespace(value)) {
                continue;
            }
            return value == '.' || value == '?' || value == '!';
        }
        return true;
    }

    private boolean shouldTypingAssistance() {
        // English spelling help has nothing useful to say about pinyin, and
        // auto-capitalisation would fight the composer. Off for the whole of
        // Chinese mode, not just while a buffer is active.
        if (inputMode.isChinese()) {
            return false;
        }
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) {
            return true;
        }
        int inputType = info.inputType;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        if ((inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
            return false;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return !isTypingAssistanceBlockedVariation(variation);
    }

    private boolean shouldAutoCorrectTyping() {
        if (recording || processing || symbolsMode) {
            return false;
        }
        return shouldTypingAssistance();
    }

    private boolean shouldAllowVoiceCapture() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) {
            return true;
        }
        int inputType = info.inputType;
        if ((inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        return !isPasswordVariation(variation);
    }

    private boolean isTypingAssistanceBlockedVariation(int variation) {
        return isPasswordVariation(variation)
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_URI;
    }

    private boolean isPasswordVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private String currentWordBeforeCursor() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        CharSequence before = connection.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) {
            return "";
        }
        int end = before.length();
        int start = end;
        while (start > 0 && isAutoCorrectWordCharacter(before.charAt(start - 1))) {
            start--;
        }
        return before.subSequence(start, end).toString();
    }

    private boolean isAutoCorrectWordCharacter(String text) {
        return text.length() == 1 && isAutoCorrectWordCharacter(text.charAt(0));
    }

    private boolean isAutoCorrectWordCharacter(char value) {
        return Character.isLetter(value) || value == '\'';
    }

    private boolean isSeparator(String text) {
        return " ".equals(text)
                || ".".equals(text)
                || ",".equals(text)
                || "?".equals(text)
                || "!".equals(text)
                || ":".equals(text)
                || ";".equals(text);
    }

    private boolean containsDigit(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isDigit(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String fallbackCorrectionFor(String word) {
        String replacement = COMMON_TYPOS.get(word.toLowerCase(Locale.US));
        return replacement == null ? "" : matchCase(word, replacement);
    }

    private String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase(Locale.US))) {
            return replacement.toUpperCase(Locale.US);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return replacement.substring(0, 1).toUpperCase(Locale.US) + replacement.substring(1);
        }
        return replacement;
    }

    private void applyRecentPhraseReplacement() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        CharSequence before = connection.getTextBeforeCursor(96, 0);
        if (before == null || before.length() == 0) {
            return;
        }
        String text = before.toString();
        PersonalVocabulary.ReplacementMatch match = PersonalVocabulary.findReplacementAtEnd(
                text,
                Prefs.allPhraseReplacements(this)
        );
        if (match != null) {
            connection.deleteSurroundingText(match.matchedLength, 0);
            connection.commitText(match.replacement, 1);
        }
    }

    private void sendEnter() {
        if (recording || processing) {
            return;
        }
        // A half-typed reading must be resolved before the editor action fires,
        // or the composing span can be submitted as raw latin.
        if (composingPinyin()) {
            settlePinyin(PinyinSession.SettleReason.ACCEPT_TOP);
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        clearLastVoiceInsertion();
        applyRecentPhraseReplacement();
        EditorInfo info = getCurrentInputEditorInfo();
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (connection.performEditorAction(action)) {
                return;
            }
        }
        boolean multiline = info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (multiline) {
            connection.commitText("\n", 1);
            clearAutoCorrection();
            updateAutoCapitalization();
            return;
        }
        clearAutoCorrection();
        updateAutoCapitalization();
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    // ------------------------------------------------------------- Responsive

    /**
     * Screen-derived geometry for one build of the keyboard.
     *
     * <p>Recomputed in {@link #onCreateInputView()}, which the framework calls
     * again after a configuration change: InputMethodService.onConfigurationChanged
     * runs resetStateForNewConfiguration -> initViews, and initViews nulls the
     * cached input view. That is also why the IME service must NOT declare
     * android:configChanges â€” doing so marks those diffs handled and suppresses
     * the rebuild.
     */
    private static final class KeyboardMetrics {
        final int keyHeightDp;
        final boolean split;
        final int gutterDp;

        private KeyboardMetrics(int keyHeightDp, boolean split, int gutterDp) {
            this.keyHeightDp = keyHeightDp;
            this.split = split;
            this.gutterDp = gutterDp;
        }

        static KeyboardMetrics from(Configuration configuration) {
            boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
            boolean split = KeyboardGeometry.shouldSplit(configuration.smallestScreenWidthDp);
            return new KeyboardMetrics(
                    KeyboardGeometry.keyHeightDp(landscape, split),
                    split,
                    split ? KeyboardGeometry.gutterDp(configuration.screenWidthDp) : 0
            );
        }
    }

    private KeyboardMetrics metrics =
            new KeyboardMetrics(KeyboardGeometry.KEY_HEIGHT_DP, false, 0);

    private int keyHeightPx() {
        return dp(metrics.keyHeightDp);
    }

    private int splitIndexFor(KeyboardGeometry.Row row, int keyCount) {
        return KeyboardGeometry.splitIndex(row, keyCount, metrics.split);
    }

    /** The gap between the two halves. */
    private View splitSpacer() {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(metrics.gutterDp), keyHeightPx()));
        return spacer;
    }

    // ---------------------------------------------------------------- Chinese

    /** Chinese punctuation for the keys that would otherwise emit ASCII. */
    private static String fullWidthPunctuation(String ascii) {
        switch (ascii) {
            case ".":
                return "ã€‚";
            case ",":
                return "ï¼Œ";
            case "?":
                return "ï¼Ÿ";
            case "!":
                return "ï¼";
            case ":":
                return "ï¼š";
            case ";":
                return "ï¼›";
            default:
                return ascii;
        }
    }

    private boolean chineseAvailable() {
        return Prefs.chineseInputEnabled(this);
    }

    private boolean composingPinyin() {
        return pinyinSession != null && pinyinSession.isComposing();
    }

    private PinyinEngine pinyinEngine() {
        if (pinyinEngine == null) {
            pinyinEngine = new PinyinEngine(getAssets(), executor, mainHandler, this::onPinyinEngineReady);
        }
        return pinyinEngine;
    }

    private void onPinyinEngineReady() {
        if (!inputMode.isChinese()) {
            return;
        }
        PinyinComposer composer = pinyinEngine().composerFor(inputMode);
        if (composer == null) {
            if (pinyinEngine().hasFailed()) {
                setStatus("Chinese unavailable");
            }
            return;
        }
        if (pinyinSession == null) {
            pinyinSession = new PinyinSession(composer);
        } else {
            pinyinSession.setComposer(composer);
        }
        setStatus(inputMode.isKeypad() ? "ä¸­æ–‡ ä¹é”®" : "ä¸­æ–‡ å…¨é”®");
        showIdleChips();
    }

    /**
     * Applies a settlement to the editor. Every path out of a composition goes
     * through here so composing text can never be left behind.
     */
    private void applySettlement(PinyinSession.Settlement settlement) {
        if (settlement == null || settlement.isNothing()) {
            return;
        }
        if (settlement.action == PinyinSession.Action.CLEAR_STATE_ONLY) {
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        switch (settlement.action) {
            case COMMIT:
                connection.commitText(settlement.text, 1);
                break;
            case KEEP_RAW:
                connection.finishComposingText();
                break;
            case DISCARD:
                connection.setComposingText("", 1);
                connection.finishComposingText();
                break;
            default:
                break;
        }
    }

    /** Ends any composition in progress. Safe to call when nothing is composing. */
    private void settlePinyin(PinyinSession.SettleReason reason) {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.settle(reason));
        refreshComposingText();
    }

    /** Mirrors the raw buffer into the editor as underlined composing text. */
    private void refreshComposingText() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        if (composingPinyin()) {
            connection.setComposingText(pinyinSession.rawText(), 1);
        }
        showIdleChips();
    }

    /** Removes any composing span from the editor without committing it. */
    private void clearComposingRegion() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        connection.setComposingText("", 1);
        connection.finishComposingText();
    }

    /**
     * Routes a typed character into the pinyin buffer.
     *
     * @return true when Chinese input consumed the key
     */
    private boolean handleChineseKey(String value) {
        if (!inputMode.isChinese() || value.length() != 1) {
            return false;
        }
        // Only take characters this layout can actually spell with. Anything
        // else (punctuation reaching commitKey, say) falls through to the normal
        // path rather than being swallowed into the buffer.
        char typed = value.charAt(0);
        boolean usable = inputMode.isKeypad()
                ? (typed >= '2' && typed <= '9')
                : Character.isLetter(typed) && typed < 128;
        if (!usable) {
            return false;
        }
        if (pinyinSession == null) {
            // Engine still loading. Swallow the keystroke rather than emitting
            // raw latin into a Chinese message.
            pinyinEngine().prepare(inputMode);
            setStatus("Loading Chineseâ€¦");
            return true;
        }
        if (!pinyinSession.append(value.charAt(0))) {
            return true;
        }
        refreshComposingText();
        return true;
    }

    private void selectPinyinCandidate(int index) {
        if (pinyinSession == null) {
            return;
        }
        applySettlement(pinyinSession.select(index));
        if (composingPinyin()) {
            refreshComposingText();
        } else {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) {
                connection.finishComposingText();
            }
            showIdleChips();
        }
    }

    /** Candidate strip. Highest priority: it owns the strip while composing. */
    private boolean showPinyinCandidates() {
        if (chipStrip == null || !composingPinyin() || recording || processing || historyMode) {
            setPinyinCandidateStripExpanded(false);
            return false;
        }
        List<PinyinCandidate> candidates = pinyinSession.candidates();
        setPinyinCandidateStripExpanded(true);
        chipStrip.removeAllViews();
        chipScroller.scrollTo(0, 0);
        showChipStrip();
        if (candidates.isEmpty()) {
            TextView raw = pinyinCandidateChip(pinyinSession.rawText(), v -> { });
            raw.setTextColor(colors.status);
            chipStrip.addView(raw);
            return true;
        }
        for (int i = 0; i < candidates.size(); i++) {
            final int index = i;
            TextView candidate = pinyinCandidateChip(
                    candidates.get(i).text,
                    v -> selectPinyinCandidate(index));
            if (i == 0) {
                candidate.setTextColor(colors.onAccent);
                candidate.setBackground(keyBackground(colors.accent, true));
            }
            chipStrip.addView(candidate);
        }
        return true;
    }

    /**
     * Gives pinyin candidates the complete toolbar width while composing. The
     * action buttons return as soon as the composition is committed or cleared.
     */
    private void setPinyinCandidateStripExpanded(boolean expanded) {
        if (pinyinCandidateStripExpanded == expanded) {
            return;
        }
        pinyinCandidateStripExpanded = expanded;
        if (expanded) {
            if (cancelRecordingButton != null) {
                cancelRecordingButton.setVisibility(View.GONE);
            }
            if (translationButton != null) {
                translationButton.setVisibility(View.GONE);
            }
            if (createButton != null) {
                createButton.setVisibility(View.GONE);
            }
            if (instructionButton != null) {
                instructionButton.setVisibility(View.GONE);
            }
            if (micButton != null) {
                micButton.setVisibility(View.GONE);
            }
            if (topHistoryButton != null) {
                topHistoryButton.setVisibility(View.GONE);
            }
            return;
        }
        setHistoryControlsActive(historyMode);
    }

    /** Larger, easier-to-hit text than the compact English/status chips. */
    private TextView pinyinCandidateChip(String text, View.OnClickListener listener) {
        TextView candidate = chip(text, listener);
        candidate.setTextSize(18);
        candidate.setMinWidth(dp(48));
        candidate.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36));
        params.setMargins(dp(2), 0, dp(2), 0);
        candidate.setLayoutParams(params);
        return candidate;
    }

    private void setInputMode(InputMode next) {
        if (next == inputMode) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.MODE_CHANGED);
        inputMode = next;
        symbolsMode = false;
        symbolsMoreMode = false;
        shift = false;
        autoShift = false;
        capsLock = false;
        lastShiftTapMs = 0;
        clearAutoCorrection();
        if (inputMode.isChinese()) {
            Prefs.setChineseLayout(this, inputMode.prefsLayout());
            pinyinEngine().prepare(inputMode);
            PinyinComposer composer = pinyinEngine().composerFor(inputMode);
            if (composer == null) {
                pinyinSession = null;
                setStatus("Loading Chineseâ€¦");
            } else if (pinyinSession == null) {
                pinyinSession = new PinyinSession(composer);
            } else {
                pinyinSession.setComposer(composer);
            }
        } else {
            pinyinSession = null;
            // Coming back from Chinese, where typing assistance was suppressed
            // outright, so the lexicon may never have been asked for.
            englishEngine().prepare();
            setStatus("Ready");
        }
        if (keyboardPanel != null) {
            populateKeyboardPanel(keyboardPanel);
        }
        if (!inputMode.isChinese()) {
            updateAutoCapitalization();
        }
        showIdleChips();
    }

    private void toggleLanguage() {
        if (recording || processing) {
            return;
        }
        setInputMode(inputMode.isChinese()
                ? InputMode.ENGLISH
                : InputMode.chineseFor(Prefs.chineseLayout(this)));
    }

    private void showChineseLayoutMenu(View anchor) {
        if (recording || processing) {
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "ä¸­æ–‡ å…¨é”® (full pinyin)");
        menu.getMenu().add(0, 2, 1, "ä¸­æ–‡ ä¹é”® (9-key)");
        menu.getMenu().add(0, 3, 2, "English");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    setInputMode(InputMode.CHINESE_QWERTY);
                    return true;
                case 2:
                    setInputMode(InputMode.CHINESE_KEYPAD);
                    return true;
                default:
                    setInputMode(InputMode.ENGLISH);
                    return true;
            }
        });
        menu.show();
    }

    /** The language key: tap to toggle, long-press to pick the Chinese layout. */
    private TextView languageKey() {
        TextView key = keyButton(inputMode.keyLabel(), 1.15f, v -> toggleLanguage(), true);
        key.setOnLongClickListener(v -> {
            showChineseLayoutMenu(v);
            return true;
        });
        return key;
    }

    /** Samsung-style 3x4 pinyin keypad: eight letter groups plus function keys. */
    private void addKeypadRows(LinearLayout panel) {
        String[][] groups = {
                {"ABC", "2"}, {"DEF", "3"}, {"GHI", "4"},
                {"JKL", "5"}, {"MNO", "6"}, {"PQRS", "7"},
                {"TUV", "8"}, {"WXYZ", "9"}
        };
        int cursor = 0;
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3 && cursor < groups.length; column++, cursor++) {
                final String digit = groups[cursor][1];
                TextView group = keyButton(groups[cursor][0], 1f, v -> commitKey(digit));
                // keyButton sizes text by label length, which would render ABC at
                // 18sp next to PQRS at 11sp. The keypad wants one uniform size.
                group.setTextSize(15);
                group.setTypeface(Typeface.DEFAULT_BOLD);
                line.addView(group);
            }
            if (row == 0) {
                line.addView(deleteKey());
            } else if (row == 1) {
                line.addView(keyButton("ï¼Œ", 1.35f, v -> commitSeparator("ï¼Œ"), true));
            } else {
                // The last row is one letter group short; ã€‚ fills it so every
                // row keeps the same four-column geometry.
                line.addView(keyButton("ã€‚", 1f, v -> commitSeparator("ã€‚")));
                line.addView(keyButton("ï¼Ÿ", 1.35f, v -> commitSeparator("ï¼Ÿ"), true));
            }
            panel.addView(line);
        }
    }

    private void toggleSymbolsMode() {
        if (recording || processing || keyboardPanel == null) {
            return;
        }
        settlePinyin(PinyinSession.SettleReason.MODE_CHANGED);
        symbolsMode = !symbolsMode;
        symbolsMoreMode = false;
        shift = false;
        autoShift = false;
        lastShiftTapMs = 0;
        populateKeyboardPanel(keyboardPanel);
        if (!symbolsMode) {
            updateAutoCapitalization();
        }
        setStatus(symbolsMode ? "Symbols" : capsLock ? "Caps lock" : "Ready");
    }

    private void toggleMoreSymbolsMode() {
        if (recording || processing || keyboardPanel == null) {
            return;
        }
        haptic(keyboardPanel);
        symbolsMoreMode = !symbolsMoreMode;
        populateKeyboardPanel(keyboardPanel);
        setStatus(symbolsMoreMode ? "More symbols" : "Symbols");
    }

    private void toggleVoiceCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (translationCapture || creationCapture || instructionCapture) {
                setStatus(translationCapture
                        ? "Tap the translate button to finish"
                        : creationCapture
                                ? "Tap the create button to finish"
                                : "Tap the instruction button to finish");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!recording && !shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in this field");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        // Resolve any pending reading before audio starts, so the buffer cannot
        // interleave with the transcript.
        settlePinyin(PinyinSession.SettleReason.RECORDING_STARTED);
        String provider = Prefs.transcriptionProvider(this);
        boolean chinese = dictationLanguage().isChinese();

        // The bundled offline models are English-only. Left alone they would
        // transcribe Mandarin into confident nonsense rather than failing, which
        // is worse than refusing: the speaker gets no signal anything went wrong.
        if (chinese && isOfflineTranscriptionProvider(provider)) {
            setStatus("Chinese needs OpenAI or Deepgram");
            return;
        }
        if (chinese && !Prefs.supportsChineseDictation(provider)) {
            setStatus("Chinese not supported by " + Prefs.providerLabel(provider));
            return;
        }
        if (chinese && !hasNetworkConnectivity()) {
            // Do not silently drop to the offline English model.
            setStatus("Chinese dictation needs a connection");
            return;
        }

        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else if (!hasNetworkConnectivity()) {
            toggleOfflineFallbackRecording(provider);
        } else {
            toggleCloudRecording();
        }
    }

    /**
     * Language for the recording about to start. Snapshotted here rather than
     * read later, because the providers need it at request-build time and
     * Deepgram in particular can only be told one language.
     */
    private DictationLanguage dictationLanguage() {
        return inputMode.isChinese() ? DictationLanguage.CHINESE : DictationLanguage.AUTO;
    }

    private void toggleCreationCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!creationCapture || translationCapture || instructionCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Text creation requires a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for text creation");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        creationCapture = true;
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            creationCapture = false;
            setCreateVisual(false, true);
            setMicVisual(false, true);
        }
    }

    private void toggleInstructionCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!instructionCapture || translationCapture || creationCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Voice instructions require a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for voice instructions");
            return;
        }
        String sourceText = currentEditableFieldText();
        if (sourceText.trim().isEmpty()) {
            setStatus("Add text to the field first");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        instructionCapture = true;
        instructionSourceText = sourceText;
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            instructionCapture = false;
            instructionSourceText = "";
            setInstructionVisual(false, true);
            setMicVisual(false, true);
        }
    }

    private void toggleTranslationCapture() {
        if (processing) {
            return;
        }
        if (recording) {
            if (!translationCapture) {
                setStatus("Finish or cancel the current recording first");
                return;
            }
            if (offlineRecordingSession) {
                stopOfflineRecordingAndTranscribe();
            } else {
                stopCloudRecordingAndTranscribe();
            }
            return;
        }
        if (!shouldAllowVoiceCapture()) {
            setStatus("Voice disabled in password fields");
            return;
        }
        if (!hasNetworkConnectivity()) {
            setStatus("Translation requires a connection");
            return;
        }
        String transformProvider = Prefs.transformProvider(this);
        if (!Prefs.hasApiKeyForProvider(this, transformProvider)) {
            setStatus("Add a " + Prefs.providerLabel(transformProvider) + " key for translation");
            return;
        }
        if (historyMode) {
            hideHistoryPanel();
        }
        translationCapture = true;
        translationTargetLanguage = Prefs.translationTargetLanguage(this);
        String provider = Prefs.transcriptionProvider(this);
        if (isOfflineTranscriptionProvider(provider)) {
            toggleOfflineRecording(provider);
        } else {
            toggleCloudRecording();
        }
        if (!recording && !processing) {
            translationCapture = false;
            translationTargetLanguage = "";
            setTranslationVisual(false, true);
            setInstructionVisual(false, true);
            setMicVisual(false, true);
        }
    }

    private String currentEditableFieldText() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            return "";
        }
        ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        if (extracted != null && extracted.text != null) {
            return extracted.text.toString();
        }
        CharSequence before = connection.getTextBeforeCursor(200000, 0);
        CharSequence selected = connection.getSelectedText(0);
        CharSequence after = connection.getTextAfterCursor(200000, 0);
        return String.valueOf(before == null ? "" : before)
                + String.valueOf(selected == null ? "" : selected)
                + String.valueOf(after == null ? "" : after);
    }

    private void cancelRecording() {
        if (!recording || processing) {
            return;
        }
        File audio = currentAudioFile;
        File pcm = currentPcmFile;
        if (offlineRecordingSession) {
            stopOfflineRecorderOnly();
        } else {
            stopCloudRecorderOnly();
        }
        deleteTempFile(audio);
        deleteTempFile(pcm);
        finishProcessingState("Recording canceled.");
    }

    private void toggleOfflineFallbackRecording(String selectedProvider) {
        if (recording) {
            stopOfflineRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        String fallbackProvider = installedOfflineFallbackProvider();
        if (fallbackProvider == null) {
            setStatus("No connection. Connect once to download offline model.");
            return;
        }
        startOfflineRecordingNow("Offline fallback from " + Prefs.providerLabel(selectedProvider), fallbackProvider);
    }

    private void toggleCloudRecording() {
        if (recording) {
            stopCloudRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        try {
            selectedPreset = Prefs.activePreset(this);
            selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
            currentAudioFile = File.createTempFile("voiceflow-keyboard-", ".m4a", getCacheDir());
            recorder = createRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentAudioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            offlineRecordingSession = false;
            setKeyboardLocked(true);
            showCaptureRecordingState();
        } catch (Exception e) {
            stopRecorderSilently();
            setStatus("Recording failed: " + concise(e));
        }
    }

    private MediaRecorder createRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(this);
        }
        return new MediaRecorder();
    }

    private void stopCloudRecordingAndTranscribe() {
        File audio = currentAudioFile;
        stopCloudRecorderOnly();
        if (audio == null || !audio.exists() || audio.length() == 0) {
            finishProcessingState("No audio captured.");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        showCaptureProcessingState();
        startStatusSpinner(creationCapture
                ? "Transcribing creation request"
                : instructionCapture ? "Transcribing instruction" : translationCapture ? "Transcribing for translation" : "Transcribing");
        String presetForThisRecording = selectedPreset;
        int expressionForThisRecording = selectedExpression;
        boolean creationForThisRecording = creationCapture;
        boolean instructionForThisRecording = instructionCapture;
        String sourceForThisInstruction = instructionSourceText;
        boolean translationForThisRecording = translationCapture;
        String targetForThisTranslation = translationTargetLanguage;
        DictationLanguage languageForThisRecording = dictationLanguage();
        executor.execute(() -> {
            try {
                String transcript = TranscriptionClient.transcribe(this, audio, languageForThisRecording);
                processTranscribedCapture(
                        transcript,
                        presetForThisRecording,
                        expressionForThisRecording,
                        creationForThisRecording,
                        instructionForThisRecording,
                        sourceForThisInstruction,
                        translationForThisRecording,
                        targetForThisTranslation
                );
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState(concise(e)));
            } finally {
                if (!audio.delete()) {
                    audio.deleteOnExit();
                }
            }
        });
    }

    private boolean shouldTransform(String preset) {
        return Prefs.enableTransform(this)
                && !Prefs.PRESET_RAW.equals(preset)
                && hasNetworkConnectivity();
    }

    private void toggleOfflineRecording(String provider) {
        if (recording) {
            stopOfflineRecordingAndTranscribe();
            return;
        }
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (!hasAudioPermission()) {
            setStatus("Open settings and grant microphone permission.");
            openSettings();
            return;
        }
        if (!isOfflineModelReady(provider)) {
            prepareOfflineModel(provider);
            return;
        }
        startOfflineRecordingNow("Recording offline", provider);
    }

    private void prepareOfflineModel(String provider) {
        processing = true;
        setKeyboardLocked(true);
        micButton.setEnabled(false);
        setMicVisual(true, false);
        startStatusSpinner("Downloading " + Prefs.providerLabel(provider));
        executor.execute(() -> {
            try {
                ensureOfflineModel(provider);
                mainHandler.post(() -> finishProcessingState(Prefs.providerLabel(provider) + " ready. Tap mic to record."));
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState("Offline setup failed: " + concise(e)));
            }
        });
    }

    private void startOfflineRecordingNow(String statusPrefix, String provider) {
        if (voiceCallOwnsMicrophone()) {
            setStatus("Call active - voice typing is unavailable");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Open settings and grant microphone permission.");
            return;
        }
        int minBuffer = AudioRecord.getMinBufferSize(
                offlineSampleRate(provider),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            setStatus("Offline recorder unavailable.");
            return;
        }
        int bufferSize = Math.max(minBuffer, offlineSampleRate(provider) * 2);
        selectedPreset = Prefs.activePreset(this);
        selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
        try {
            currentPcmFile = File.createTempFile("voiceflow-keyboard-", ".pcm", getCacheDir());
            offlineRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    offlineSampleRate(provider),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (offlineRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                stopOfflineRecorderOnly();
                setStatus("Offline recorder failed to initialize.");
                return;
            }
            offlineRecordLoop = true;
            offlineRecorder.startRecording();
            File target = currentPcmFile;
            AudioRecord activeRecorder = offlineRecorder;
            offlineRecordThread = new Thread(
                    () -> writeOfflinePcm(activeRecorder, target, bufferSize),
                    "VoiceFlowOfflineRecorder"
            );
            offlineRecordThread.start();
            recording = true;
            offlineRecordingSession = true;
            offlineRecordingProvider = provider;
            setKeyboardLocked(true);
            showCaptureRecordingState();
        } catch (Exception e) {
            stopOfflineRecorderOnly();
            setStatus("Offline recording failed: " + concise(e));
        }
    }

    private void writeOfflinePcm(AudioRecord activeRecorder, File target, int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        try (FileOutputStream out = new FileOutputStream(target)) {
            while (offlineRecordLoop) {
                int read = activeRecorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } catch (Exception e) {
            if (offlineRecordLoop) {
                postStatus("Offline recording interrupted.");
            }
        }
    }

    private void stopOfflineRecordingAndTranscribe() {
        File pcm = currentPcmFile;
        stopOfflineRecorderOnly();
        if (pcm == null || !pcm.exists() || pcm.length() == 0) {
            finishProcessingState("No audio captured.");
            return;
        }
        processing = true;
        setKeyboardLocked(true);
        showCaptureProcessingState();
        startStatusSpinner(creationCapture
                ? "Transcribing creation request"
                : instructionCapture ? "Transcribing instruction" : translationCapture ? "Transcribing for translation" : "Transcribing");
        String presetForThisRecording = selectedPreset;
        int expressionForThisRecording = selectedExpression;
        String providerForThisRecording = offlineRecordingProvider;
        boolean creationForThisRecording = creationCapture;
        boolean instructionForThisRecording = instructionCapture;
        String sourceForThisInstruction = instructionSourceText;
        boolean translationForThisRecording = translationCapture;
        String targetForThisTranslation = translationTargetLanguage;
        executor.execute(() -> {
            try {
                String transcript = transcribeOfflinePcm(providerForThisRecording, pcm);
                processTranscribedCapture(
                        transcript,
                        presetForThisRecording,
                        expressionForThisRecording,
                        creationForThisRecording,
                        instructionForThisRecording,
                        sourceForThisInstruction,
                        translationForThisRecording,
                        targetForThisTranslation
                );
            } catch (Exception e) {
                mainHandler.post(() -> finishProcessingState(concise(e)));
            } finally {
                if (!pcm.delete()) {
                    pcm.deleteOnExit();
                }
            }
        });
    }

    private void processTranscribedCapture(
            String transcript,
            String preset,
            int expression,
            boolean creation,
            boolean instruction,
            String instructionSource,
            boolean translation,
            String targetLanguage
    ) throws Exception {
        String normalizedTranscript = applyPhraseReplacements(
                transcript == null ? "" : transcript.trim()
        );
        if (translation) {
            String statusLanguage = compactLanguageName(targetLanguage);
            postStatusSpinner("Translating to " + statusLanguage);
            String result = TransformClient.translate(
                    this,
                    normalizedTranscript,
                    targetLanguage,
                    preset,
                    expression
            );
            if (result == null || result.trim().isEmpty()) {
                throw new IllegalStateException("Translation returned no text.");
            }
            mainHandler.post(() -> {
                String historyId = Prefs.addTranscriptHistory(
                        this,
                        normalizedTranscript,
                        result,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_TRANSLATION,
                        targetLanguage
                );
                String inserted = insertVoiceText(result);
                rememberLastVoiceInsertion(
                        normalizedTranscript,
                        inserted,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_TRANSLATION,
                        targetLanguage,
                        historyId
                );
                finishProcessingState("Translated - " + labelForPreset(preset));
            });
            return;
        }
        if (creation) {
            postStatusSpinner("Creating text");
            String result = TransformClient.createText(
                    this,
                    normalizedTranscript,
                    preset,
                    expression
            );
            if (result == null || result.trim().isEmpty()) {
                throw new IllegalStateException("Text creation returned no text.");
            }
            mainHandler.post(() -> {
                String historyId = Prefs.addTranscriptHistory(
                        this,
                        normalizedTranscript,
                        result,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_CREATION,
                        ""
                );
                String inserted = appendCreatedText(result);
                if (inserted.isEmpty()) {
                    finishProcessingState("Could not append created text");
                    return;
                }
                rememberLastVoiceInsertion(
                        normalizedTranscript,
                        inserted,
                        preset,
                        expression,
                        VoiceHistoryItem.OPERATION_CREATION,
                        "",
                        historyId
                );
                finishProcessingState("Created and appended");
            });
            return;
        }
        if (instruction) {
            postStatusSpinner("Applying instruction");
            String result = TransformClient.applyInstruction(
                    this,
                    instructionSource,
                    normalizedTranscript
            );
            mainHandler.post(() -> {
                if (replaceWholeFieldText(result)) {
                    finishProcessingState("Text updated");
                } else {
                    finishProcessingState("Could not replace this field");
                }
            });
            return;
        }

        String finalText = normalizedTranscript;
        String finalStatus = "Inserted";
        if (shouldTransform(preset)) {
            postStatusSpinner("Transforming: " + labelForPreset(preset));
            try {
                finalText = TransformClient.transform(
                        this,
                        normalizedTranscript,
                        preset,
                        expression
                );
            } catch (Exception transformError) {
                finalStatus = "Inserted raw";
            }
        }
        String result = finalText;
        String status = finalStatus;
        mainHandler.post(() -> {
            String historyId = Prefs.addTranscriptHistory(
                    this,
                    normalizedTranscript,
                    result,
                    preset,
                    expression,
                    VoiceHistoryItem.OPERATION_DICTATION,
                    ""
            );
            String inserted = insertVoiceText(result);
            rememberLastVoiceInsertion(
                    normalizedTranscript,
                    inserted,
                    preset,
                    expression,
                    VoiceHistoryItem.OPERATION_DICTATION,
                    "",
                    historyId
            );
            finishProcessingState(status);
        });
    }

    private void cyclePreset(int direction) {
        String[] presets = Prefs.selectablePresetValues(this);
        int index = Prefs.presetIndex(presets, selectedPreset);
        int next = (index + direction + presets.length) % presets.length;
        selectedPreset = presets[next];
        selectedExpression = Prefs.expressionForPreset(this, selectedPreset);
        Prefs.setActivePreset(this, selectedPreset);
        showVoiceStyleOverlay();
        setStatus(captureStyleStatus("Recording"));
    }

    private int presetIndex(String preset) {
        return Prefs.presetIndex(Prefs.selectablePresetValues(this), preset);
    }

    private String selectedPresetLabel() {
        return labelForPreset(selectedPreset);
    }

    private String labelForPreset(String preset) {
        return Prefs.labelForPreset(this, preset);
    }

    private void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private void stopRecorderSilently() {
        stopCloudRecorderOnly();
        stopOfflineRecorderOnly();
        finishProcessingState("Ready");
    }

    private void stopCloudRecorderOnly() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.release();
            recorder = null;
        }
        recording = false;
        offlineRecordingSession = false;
        offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
        currentAudioFile = null;
    }

    private void stopOfflineRecorderOnly() {
        offlineRecordLoop = false;
        AudioRecord activeRecorder = offlineRecorder;
        offlineRecorder = null;
        if (activeRecorder != null) {
            try {
                activeRecorder.stop();
            } catch (Exception ignored) {
            }
            activeRecorder.release();
        }
        Thread thread = offlineRecordThread;
        offlineRecordThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        recording = false;
        offlineRecordingSession = false;
        offlineRecordingProvider = Prefs.PROVIDER_OFFLINE_VOSK;
        currentPcmFile = null;
    }

    private void finishProcessingState(String status) {
        stopStatusSpinner();
        recording = false;
        processing = false;
        retoneMode = false;
        translationCapture = false;
        translationTargetLanguage = "";
        creationCapture = false;
        instructionCapture = false;
        instructionSourceText = "";
        stopDeleteHold();
        hideVoiceStyleOverlay();
        setKeyboardLocked(false);
        setRetoneTopControls(false);
        if (translationButton != null) {
            setTranslationVisual(false, true);
        }
        if (createButton != null) {
            setCreateVisual(false, true);
        }
        if (instructionButton != null) {
            setInstructionVisual(false, true);
        }
        if (micButton != null) {
            micButton.setEnabled(true);
            setMicVisual(false, true);
        }
        if (chipStrip != null) {
            showIdleChips();
        }
        setStatus(status);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOfflineTranscriptionProvider(String provider) {
        return Prefs.PROVIDER_OFFLINE_VOSK.equals(provider)
                || Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider);
    }

    private String installedOfflineFallbackProvider() {
        if (OfflineParakeetClient.isModelReady(this)) {
            return Prefs.PROVIDER_OFFLINE_PARAKEET;
        }
        if (OfflineVoskClient.isModelReady(this)) {
            return Prefs.PROVIDER_OFFLINE_VOSK;
        }
        return null;
    }

    private boolean isOfflineModelReady(String provider) {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.isModelReady(this);
        }
        return OfflineVoskClient.isModelReady(this);
    }

    private void ensureOfflineModel(String provider) throws Exception {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            OfflineParakeetClient.ensureModel(this);
        } else {
            OfflineVoskClient.ensureModel(this);
        }
    }

    private String transcribeOfflinePcm(String provider, File pcm) throws Exception {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.transcribePcm(this, pcm);
        }
        return OfflineVoskClient.transcribePcm(this, pcm);
    }

    private int offlineSampleRate(String provider) {
        if (Prefs.PROVIDER_OFFLINE_PARAKEET.equals(provider)) {
            return OfflineParakeetClient.SAMPLE_RATE;
        }
        return OfflineVoskClient.SAMPLE_RATE;
    }

    private boolean hasNetworkConnectivity() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void postStatus(String status) {
        mainHandler.post(() -> setStatus(status));
    }

    private void startStatusSpinner(String base) {
        hideChipStrip();
        if (translationCapture) {
            startTranslationLoading();
        } else if (creationCapture) {
            startCreateLoading();
        } else if (instructionCapture) {
            startInstructionLoading();
        } else {
            startMicLoading();
        }
        statusSpinnerBase = base;
        statusSpinnerStep = 0;
        if (statusSpinnerRunnable == null) {
            statusSpinnerRunnable = () -> {
                if (!processing) {
                    return;
                }
                String dots = statusSpinnerStep == 0 ? "." : statusSpinnerStep == 1 ? ".." : "...";
                setStatus(statusSpinnerBase + dots);
                statusSpinnerStep = (statusSpinnerStep + 1) % 3;
                mainHandler.postDelayed(statusSpinnerRunnable, 450);
            };
        }
        mainHandler.removeCallbacks(statusSpinnerRunnable);
        mainHandler.post(statusSpinnerRunnable);
    }

    private void postStatusSpinner(String base) {
        mainHandler.post(() -> startStatusSpinner(base));
    }

    private void stopStatusSpinner() {
        if (statusSpinnerRunnable != null) {
            mainHandler.removeCallbacks(statusSpinnerRunnable);
        }
        stopMicLoading();
        stopCreateLoading();
        stopInstructionLoading();
        stopTranslationLoading();
    }

    private void startMicLoading() {
        if (micButton == null) {
            return;
        }
        micButton.setVisibility(View.VISIBLE);
        micButton.setEnabled(false);
        micButton.setImageResource(R.drawable.ic_loading_24);
        micButton.setColorFilter(colors.text);
        micButton.setBackground(ovalBackground(colors.key, false));
        micButton.setAlpha(1f);
        if (micLoadingAnimator == null) {
            micLoadingAnimator = ObjectAnimator.ofFloat(micButton, View.ROTATION, 0f, 360f);
            micLoadingAnimator.setDuration(850);
            micLoadingAnimator.setInterpolator(new LinearInterpolator());
            micLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!micLoadingAnimator.isStarted()) {
            micLoadingAnimator.start();
        }
    }

    private void stopMicLoading() {
        if (micLoadingAnimator != null) {
            micLoadingAnimator.cancel();
        }
        if (micButton != null) {
            micButton.setRotation(0f);
            micButton.setImageResource(R.drawable.ic_mic_24);
        }
    }

    private void startInstructionLoading() {
        if (instructionButton == null) {
            return;
        }
        instructionButton.setVisibility(View.VISIBLE);
        instructionButton.setEnabled(false);
        instructionButton.setImageResource(R.drawable.ic_loading_24);
        instructionButton.setColorFilter(colors.text);
        instructionButton.setBackground(ovalBackground(colors.key, false));
        instructionButton.setAlpha(1f);
        if (instructionLoadingAnimator == null) {
            instructionLoadingAnimator = ObjectAnimator.ofFloat(instructionButton, View.ROTATION, 0f, 360f);
            instructionLoadingAnimator.setDuration(850);
            instructionLoadingAnimator.setInterpolator(new LinearInterpolator());
            instructionLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!instructionLoadingAnimator.isStarted()) {
            instructionLoadingAnimator.start();
        }
    }

    private void stopInstructionLoading() {
        if (instructionLoadingAnimator != null) {
            instructionLoadingAnimator.cancel();
        }
        if (instructionButton != null) {
            instructionButton.setRotation(0f);
            instructionButton.setImageResource(R.drawable.ic_wand_24);
        }
    }

    private void startCreateLoading() {
        if (createButton == null) {
            return;
        }
        createButton.setVisibility(View.VISIBLE);
        createButton.setEnabled(false);
        createButton.setImageResource(R.drawable.ic_loading_24);
        createButton.setColorFilter(colors.text);
        createButton.setBackground(ovalBackground(colors.key, false));
        createButton.setAlpha(1f);
        if (createLoadingAnimator == null) {
            createLoadingAnimator = ObjectAnimator.ofFloat(createButton, View.ROTATION, 0f, 360f);
            createLoadingAnimator.setDuration(850);
            createLoadingAnimator.setInterpolator(new LinearInterpolator());
            createLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!createLoadingAnimator.isStarted()) {
            createLoadingAnimator.start();
        }
    }

    private void stopCreateLoading() {
        if (createLoadingAnimator != null) {
            createLoadingAnimator.cancel();
        }
        if (createButton != null) {
            createButton.setRotation(0f);
            createButton.setImageResource(R.drawable.ic_create_text_24);
        }
    }

    private void startTranslationLoading() {
        if (translationButton == null) {
            return;
        }
        translationButton.setVisibility(View.VISIBLE);
        translationButton.setEnabled(false);
        translationButton.setImageResource(R.drawable.ic_loading_24);
        translationButton.setColorFilter(colors.text);
        translationButton.setBackground(ovalBackground(colors.key, false));
        translationButton.setAlpha(1f);
        if (translationLoadingAnimator == null) {
            translationLoadingAnimator = ObjectAnimator.ofFloat(translationButton, View.ROTATION, 0f, 360f);
            translationLoadingAnimator.setDuration(850);
            translationLoadingAnimator.setInterpolator(new LinearInterpolator());
            translationLoadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!translationLoadingAnimator.isStarted()) {
            translationLoadingAnimator.start();
        }
    }

    private void stopTranslationLoading() {
        if (translationLoadingAnimator != null) {
            translationLoadingAnimator.cancel();
        }
        if (translationButton != null) {
            translationButton.setRotation(0f);
            translationButton.setImageResource(R.drawable.ic_translate_24);
        }
    }

    private void setStatus(String status) {
        if (statusText != null) {
            statusText.setText(status);
            statusText.setContentDescription(status);
        }
    }

    private boolean voiceCallOwnsMicrophone() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return false;
        }
        int mode = audioManager.getMode();
        return mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private String concise(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable keyBackground(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(7));
        if (!selected) {
            drawable.setStroke(dp(1), colors.stroke);
        }
        return drawable;
    }

    private InsetDrawable keyVisualBackground(int color, boolean selected) {
        return new InsetDrawable(
                keyBackground(color, selected),
                dp(KEY_VISUAL_GAP_DP),
                dp(KEY_VISUAL_GAP_DP),
                dp(KEY_VISUAL_GAP_DP),
                dp(KEY_VISUAL_GAP_DP)
        );
    }

    private GradientDrawable ovalBackground(int color, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (!selected) {
            drawable.setStroke(dp(1), colors.stroke);
        }
        return drawable;
    }

    private void setMicVisual(boolean active, boolean enabled) {
        if (micButton == null) {
            return;
        }
        if (!processing) {
            micButton.setImageResource(R.drawable.ic_mic_24);
        }
        micButton.setEnabled(enabled);
        micButton.setColorFilter(active ? colors.onDanger : colors.text);
        micButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        micButton.setAlpha(enabled ? 1f : 0.72f);
        updateRecordingControls();
    }

    private void setInstructionVisual(boolean active, boolean enabled) {
        if (instructionButton == null) {
            return;
        }
        if (!processing) {
            instructionButton.setImageResource(R.drawable.ic_wand_24);
        }
        instructionButton.setEnabled(enabled);
        instructionButton.setColorFilter(active ? colors.onDanger : colors.text);
        instructionButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        instructionButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void setCreateVisual(boolean active, boolean enabled) {
        if (createButton == null) {
            return;
        }
        if (!processing) {
            createButton.setImageResource(R.drawable.ic_create_text_24);
        }
        createButton.setEnabled(enabled);
        createButton.setColorFilter(active ? colors.onDanger : colors.text);
        createButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        createButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void setTranslationVisual(boolean active, boolean enabled) {
        if (translationButton == null) {
            return;
        }
        if (!processing) {
            translationButton.setImageResource(R.drawable.ic_translate_24);
        }
        translationButton.setEnabled(enabled);
        translationButton.setColorFilter(active ? colors.onDanger : colors.text);
        translationButton.setBackground(ovalBackground(active ? colors.danger : colors.key, active));
        translationButton.setAlpha(enabled ? 1f : 0.55f);
        updateRecordingControls();
    }

    private void showCaptureRecordingState() {
        if (translationCapture) {
            hideChipStrip();
            setMicVisual(false, false);
            setCreateVisual(false, false);
            setInstructionVisual(false, false);
            setTranslationVisual(true, true);
            showVoiceStyleOverlay();
            setStatus(captureStyleStatus("Recording"));
            return;
        }
        if (creationCapture) {
            hideChipStrip();
            setMicVisual(false, false);
            setTranslationVisual(false, false);
            setInstructionVisual(false, false);
            setCreateVisual(true, true);
            showVoiceStyleOverlay();
            setStatus(captureStyleStatus("Creating"));
            return;
        }
        if (instructionCapture) {
            hideChipStrip();
            hideVoiceStyleOverlay();
            setMicVisual(false, false);
            setTranslationVisual(false, false);
            setCreateVisual(false, false);
            setInstructionVisual(true, true);
            setStatus("Recording instruction");
            return;
        }
        setTranslationVisual(false, false);
        setCreateVisual(false, false);
        setInstructionVisual(false, false);
        setMicVisual(true, true);
        hideChipStrip();
        showVoiceStyleOverlay();
        setStatus(captureStyleStatus("Recording"));
    }

    private void showCaptureProcessingState() {
        hideVoiceStyleOverlay();
        if (translationCapture) {
            setMicVisual(false, false);
            setCreateVisual(false, false);
            setInstructionVisual(false, false);
            setTranslationVisual(true, false);
            return;
        }
        if (creationCapture) {
            setMicVisual(false, false);
            setTranslationVisual(false, false);
            setInstructionVisual(false, false);
            setCreateVisual(true, false);
            return;
        }
        if (instructionCapture) {
            setMicVisual(false, false);
            setTranslationVisual(false, false);
            setCreateVisual(false, false);
            setInstructionVisual(true, false);
            return;
        }
        setTranslationVisual(false, false);
        setCreateVisual(false, false);
        setInstructionVisual(false, false);
        setMicVisual(true, false);
    }

    private void updateRecordingControls() {
        if (cancelRecordingButton == null) {
            return;
        }
        boolean canCancel = !historyMode && (recording || retoneMode) && !processing;
        cancelRecordingButton.setVisibility(canCancel ? View.VISIBLE : View.INVISIBLE);
        cancelRecordingButton.setEnabled(canCancel);
    }

    private void haptic(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private String captureStyleStatus(String prefix) {
        return prefix + ": " + selectedPresetLabel();
    }

    private String compactLanguageName(String language) {
        int qualifierStart = language.indexOf(" (");
        return qualifierStart > 0 ? language.substring(0, qualifierStart) : language;
    }

    private static final class Palette {
        final int background;
        final int status;
        final int key;
        final int keyAlt;
        final int text;
        final int stroke;
        final int accent;
        final int onAccent;
        final int danger;
        final int onDanger;

        private Palette(
                int background,
                int status,
                int key,
                int keyAlt,
                int text,
                int stroke,
                int accent,
                int onAccent,
                int danger,
                int onDanger
        ) {
            this.background = background;
            this.status = status;
            this.key = key;
            this.keyAlt = keyAlt;
            this.text = text;
            this.stroke = stroke;
            this.accent = accent;
            this.onAccent = onAccent;
            this.danger = danger;
            this.onDanger = onDanger;
        }

        static Palette from(VoiceFlowKeyboardService service) {
            boolean night = (service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int accent = service.resolveThemeColor(android.R.attr.colorAccent, night ? Color.rgb(100, 181, 246) : Color.rgb(25, 103, 210));
            if (night) {
                return new Palette(
                        Color.rgb(32, 33, 36),
                        Color.rgb(45, 46, 50),
                        Color.rgb(58, 59, 63),
                        Color.rgb(74, 75, 80),
                        Color.rgb(241, 243, 244),
                        Color.rgb(82, 83, 88),
                        accent,
                        Color.WHITE,
                        Color.rgb(198, 40, 40),
                        Color.WHITE
                );
            }
            return new Palette(
                    Color.rgb(238, 240, 243),
                    Color.rgb(247, 248, 250),
                    Color.WHITE,
                    Color.rgb(224, 228, 233),
                    Color.rgb(31, 35, 40),
                    Color.rgb(218, 223, 230),
                    accent,
                    Color.WHITE,
                    Color.rgb(191, 54, 12),
                    Color.WHITE
            );
        }
    }

    private int resolveThemeColor(int attr, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            return value.data;
        }
        return fallback;
    }

    private static Map<String, String> commonTypos() {
        Map<String, String> typos = new HashMap<>();
        typos.put("teh", "the");
        typos.put("hte", "the");
        typos.put("liek", "like");
        typos.put("becuase", "because");
        typos.put("becasue", "because");
        typos.put("definately", "definitely");
        typos.put("seperate", "separate");
        typos.put("recieve", "receive");
        typos.put("adress", "address");
        typos.put("wierd", "weird");
        typos.put("thier", "their");
        typos.put("freind", "friend");
        typos.put("dont", "don't");
        typos.put("cant", "can't");
        typos.put("wont", "won't");
        typos.put("im", "I'm");
        typos.put("ive", "I've");
        typos.put("ill", "I'll");
        return typos;
    }

    private final class SwipeRootLayout extends LinearLayout {
        SwipeRootLayout(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean wasTracking = rootSwipeTracking;
            if (handleRootSwipe(event)) {
                if (!wasTracking && rootSwipeTracking) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
