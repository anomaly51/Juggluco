package tk.glucodata;

import static android.app.Activity.RESULT_OK;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice-first, backend-owned intake conversation.
 *
 * <p>The dashboard plus button opens this single surface for food, insulin,
 * text, voice and photos. Clear reported facts are applied by the backend in
 * one transactional turn and remain reversible from the same conversation.</p>
 */
final class IntakeComposer {
    private enum Mode { CHOOSER, INSULIN, MEAL, MANUAL_MEAL }

    private static final int REQUEST_MEAL_CAMERA = 0x6F41;
    private static final int REQUEST_MEAL_GALLERY = 0x6F42;
    private static final int REQUEST_RECORD_AUDIO = 0x6F43;
    private static final int MAX_PHOTOS_PER_MESSAGE = 24;
    private static final int MAX_IMAGE_EDGE = 2400;
    private static final int PREVIEW_EDGE = 320;
    private static final int VOICE_MAX_DURATION_MS = 60_000;
    private static final long STALE_MEDIA_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final int INLINE_ERROR_NONE = 0;
    private static final int INLINE_ERROR_CONNECTION = 1;
    private static final int INLINE_ERROR_RETRY_TURN = 2;
    private static final int INLINE_ERROR_RESOLVE_TURN = 3;
    private static final int INLINE_ERROR_UNDO = 4;
    private static final int INLINE_ERROR_VOICE = 5;
    private static final int CONTROL_NONE = 0;
    private static final int CONTROL_REVISION = 1;
    private static final int CONTROL_DELETE = 2;

    private static final class ChatLine {
        final boolean user;
        final String text;
        final int photoCount;
        final boolean persistent;

        ChatLine(boolean user, String text, int photoCount) {
            this(user, text, photoCount, true);
        }

        ChatLine(boolean user, String text, int photoCount,
                boolean persistent) {
            this.user = user;
            this.text = IntakeEvent.clean(text);
            this.photoCount = photoCount;
            this.persistent = persistent;
        }
    }

    private final MainActivity activity;
    private final IntakeRepository repository;
    private final int previousSoftInputMode;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaExecutor =
            Executors.newSingleThreadExecutor();
    private final List<File> temporaryFiles = Collections.synchronizedList(
            new ArrayList<>());
    private final ArrayList<File> pendingPhotos = new ArrayList<>();
    private final ArrayList<ChatLine> chatLines = new ArrayList<>();
    private final IntakeChatCardHistory actionCardHistory =
            new IntakeChatCardHistory();
    private final IntakeDraft insulinDraft = new IntakeDraft();
    private String intakeClientSessionId = UUID.randomUUID().toString();
    private final Runnable backendConfigurationListener;

    private View root;
    private TextView backendStatus;
    private Mode mode = Mode.CHOOSER;
    private boolean closed;
    private boolean busy;
    private boolean destroying;
    private boolean intakeSessionStarting;
    private int intakeSessionGeneration;
    private boolean sessionFailureShown;
    private String intakeSessionId = "";
    private String lastActionId = "";
    private Runnable afterIntakeSessionReady;
    private IntakeChatTurn lastIntakeTurn;
    /** Last reversible backend action, independent from later clarification. */
    private IntakeChatTurn lastActionTurn;
    /** Keeps the last action card as a durable tombstone after Undo. */
    private boolean lastActionDeleted;
    private IntakeRepository.Cancellable intakeTurnCall;
    private File intakeTurnAudio;
    private final ArrayList<File> activeIntakePhotos = new ArrayList<>();
    private String retryClientTurnId = "";
    /** Fingerprint of the backend that owns the pending idempotency key. */
    private String retryBackendFingerprint = "";
    private String retryTurnText = "";
    private long retryTurnOccurredAtMs;
    private File retryTurnAudio;
    private final ArrayList<File> retryTurnPhotos = new ArrayList<>();
    private boolean retryTurnDefinitiveFailure;
    private boolean retryTurnCommitMayHaveOccurred;
    private int retryTurnControlKind = CONTROL_NONE;
    private File deferredIntakeAudio;
    private int renderedChatLineCount;

    // Insulin screen state.
    private EditText insulinDose;
    private Spinner insulinProduct;
    private Button insulinSave;
    private TextView insulinTime;
    private int insulinProductIndex;

    // Offline-capable structured meal screen state.
    private long manualMealOccurredAtMs = System.currentTimeMillis();
    private EditText manualMealName;
    private EditText manualMealCarbs;
    private EditText manualMealPortion;
    private TextView manualMealTime;
    private Button manualMealSave;

    // Meal conversation state.
    private final String mealClientEventId = UUID.randomUUID().toString();
    private long mealOccurredAtMs = System.currentTimeMillis();
    private boolean mealTimeExplicitForNextTurn;
    private long pendingMealOccurredAtMs = mealOccurredAtMs;
    private String mealSessionId = "";
    private boolean mealTimeUpdating;
    private boolean mealTimeSyncUnknown;
    private boolean confirmAfterTimeUpdate;
    private boolean mealReadyToConfirm;
    private boolean mealConfirming;
    private boolean mealSending;
    private MealChatSession.Proposal mealProposal;
    private ScrollView mealScroll;
    private LinearLayout mealMessages;
    private LinearLayout mealActionHistory;
    private HorizontalScrollView attachmentScroll;
    private LinearLayout attachmentList;
    private TextView mealTime;
    private EditText mealInput;
    private View mealAttach;
    private View mealVoice;
    private ImageView mealVoiceIcon;
    private ProgressBar mealVoiceProgress;
    private TextView intakeVoiceHint;
    private TextView intakeInlineError;
    private int intakeInlineErrorMessage;
    private int intakeInlineErrorAction = INLINE_ERROR_NONE;
    private View mealSend;
    private ImageView mealSendIcon;
    private ProgressBar mealSendProgress;
    private View proposalCard;
    private TextView proposalTitle;
    private TextView proposalMeal;
    private TextView proposalCarbs;
    private TextView proposalAbsorption;
    private TextView proposalConfidence;
    private TextView proposalWarnings;
    private TextView proposalTime;
    private TextView proposalHint;
    private Button mealConfirm;
    private Button intakeReconcile;
    private boolean correctionMode;
    private String correctionSummary = "";
    private int pendingPhotoImports;

    // Camera and voice state.
    private File pendingCameraFile;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;
    private boolean recorderReachedLimit;
    /** Consumed only after the first unified-chat root is actually attached. */
    private boolean initialVoiceAutoStartPending = true;
    private boolean recordPermissionRequestInFlight;
    private int voiceStatusMessage;
    private boolean transcribing;
    private int transcriptionGeneration;
    private String deferredTranscript = "";
    private File transcriptionFile;
    private IntakeRepository.Cancellable transcriptionCall;

    IntakeComposer(MainActivity activity) {
        this.activity = activity;
        repository = IntakeRepository.get(activity);
        backendConfigurationListener = this::onBackendConfigurationChanged;
        repository.addConfigurationListener(backendConfigurationListener);
        previousSoftInputMode = activity.getWindow().getAttributes()
                .softInputMode;
        chatLines.add(new ChatLine(false,
                activity.getString(R.string.meal_chat_intro), 0, false));
        restorePendingTurnState();
        cleanStaleMedia();
    }

    boolean isShowing() {
        return !closed && root != null && root.getParent() != null;
    }

    void show() {
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showMeal();
        MainActivity.setonback(this::handleSystemBack);
    }

    private void showChooserInternal() {
        mode = Mode.CHOOSER;
        replaceRoot(R.layout.modern_intake_chooser);
        root.findViewById(R.id.intake_close)
                .setOnClickListener(view -> close(true));
        root.findViewById(R.id.intake_choose_insulin)
                .setOnClickListener(view -> showInsulin());
        root.findViewById(R.id.intake_choose_meal)
                .setOnClickListener(view -> showMeal());
        root.findViewById(R.id.intake_choose_manual_meal)
                .setOnClickListener(view -> showManualMeal());
        bindBackendStatus();
    }

    private void showInsulin() {
        if (busy || closed) return;
        mode = Mode.INSULIN;
        replaceRoot(R.layout.modern_insulin_composer);
        root.findViewById(R.id.intake_back_button)
                .setOnClickListener(view -> childBack());
        insulinTime = root.findViewById(R.id.intake_time);
        insulinDose = root.findViewById(R.id.insulin_dose);
        insulinProduct = root.findViewById(R.id.insulin_product_spinner);
        insulinSave = root.findViewById(R.id.insulin_save);
        insulinProduct.setAdapter(new InsulinProductAdapter());
        insulinProduct.setDropDownWidth(Math.max(dp(240),
                activity.getResources().getDisplayMetrics().widthPixels
                        - dp(32)));
        insulinProduct.setSelection(insulinProductIndex, false);
        insulinTime.setOnClickListener(view -> pickTime(false));
        insulinSave.setOnClickListener(view -> saveInsulin());
        insulinDose.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveInsulin();
                return true;
            }
            return false;
        });
        bindBackendStatus();
        updateInsulinTime();
        insulinDose.postDelayed(() -> {
            if (closed || mode != Mode.INSULIN || busy) return;
            insulinDose.requestFocus();
            insulinDose.setSelection(insulinDose.length());
            InputMethodManager keyboard = (InputMethodManager)
                    activity.getSystemService(MainActivity.INPUT_METHOD_SERVICE);
            if (keyboard != null) {
                keyboard.showSoftInput(insulinDose,
                        InputMethodManager.SHOW_IMPLICIT);
            }
        }, 180L);
    }

    private final class InsulinProductAdapter extends BaseAdapter {
        @Override public int getCount() { return 2; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView,
                ViewGroup parent) {
            return productView(position, convertView, false);
        }

        @Override public View getDropDownView(int position, View convertView,
                ViewGroup parent) {
            return productView(position, convertView, true);
        }

        private View productView(int position, View convertView,
                boolean dropdown) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(activity);
            int name = position == 0 ? R.string.insulin_novorapid
                    : R.string.insulin_tresiba;
            int detail = position == 0 ? R.string.insulin_novorapid_detail
                    : R.string.insulin_tresiba_detail;
            view.setText(activity.getString(name) + "  ·  "
                    + activity.getString(detail));
            view.setTextColor(ContextCompat.getColor(activity,
                    R.color.modern_secondary_text_primary));
            view.setTextSize(dropdown ? 14.0f : 15.0f);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setPadding(dp(dropdown ? 18 : 4), 0,
                    dp(dropdown ? 18 : 12), 0);
            view.setBackgroundColor(ContextCompat.getColor(activity,
                    dropdown ? R.color.modern_secondary_surface_raised
                            : android.R.color.transparent));
            view.setMinHeight(dp(dropdown ? 56 : 48));
            return view;
        }
    }

    private void saveInsulin() {
        if (busy || mode != Mode.INSULIN) return;
        float dose;
        try {
            dose = parseNumber(insulinDose.getText().toString());
        } catch (NumberFormatException error) {
            dose = 0.0f;
        }
        if (!(dose > 0.0f) || dose > 500.0f) {
            toast(R.string.insulin_invalid_dose);
            insulinDose.requestFocus();
            return;
        }
        insulinProductIndex = Math.max(0, Math.min(1,
                insulinProduct.getSelectedItemPosition()));
        insulinDraft.insulinUnits = dose;
        insulinDraft.insulinName = insulinProductIndex == 0
                ? "NovoRapid" : "Tresiba";
        setInsulinBusy(true);
        repository.createInsulin(insulinDraft.snapshot(),
                new IntakeRepository.Callback<IntakeEvent>() {
            @Override public void onSuccess(IntakeEvent value) {
                if (closed) return;
                toast(R.string.insulin_saved);
                activity.requestRender();
                setInsulinBusy(false);
                close(true);
            }

            @Override public void onError(String message) {
                if (closed) return;
                setInsulinBusy(false);
                toast(activity.getString(R.string.intake_backend_error, message));
            }
        });
    }

    private void setInsulinBusy(boolean value) {
        busy = value;
        if (insulinSave == null) return;
        insulinSave.setEnabled(!value);
        insulinSave.setText(value ? R.string.insulin_saving
                : R.string.insulin_save);
        insulinDose.setEnabled(!value);
        insulinProduct.setEnabled(!value);
        insulinTime.setEnabled(!value);
        root.findViewById(R.id.intake_back_button).setEnabled(!value);
        updateBackendSettingsAvailability();
    }

    private void showManualMeal() {
        if (busy || closed) return;
        mode = Mode.MANUAL_MEAL;
        manualMealOccurredAtMs = System.currentTimeMillis();
        replaceRoot(R.layout.modern_manual_meal_composer);
        root.findViewById(R.id.intake_back_button)
                .setOnClickListener(view -> childBack());
        manualMealName = root.findViewById(R.id.manual_meal_name);
        manualMealCarbs = root.findViewById(R.id.manual_meal_carbs);
        manualMealPortion = root.findViewById(R.id.manual_meal_portion);
        manualMealTime = root.findViewById(R.id.manual_meal_time);
        manualMealSave = root.findViewById(R.id.manual_meal_save);
        manualMealTime.setOnClickListener(view -> pickTime(true));
        manualMealSave.setOnClickListener(view -> saveManualMeal());
        manualMealPortion.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveManualMeal();
                return true;
            }
            return false;
        });
        bindBackendStatus();
        updateManualMealTime();
        manualMealName.postDelayed(() -> {
            if (closed || mode != Mode.MANUAL_MEAL || busy) return;
            manualMealName.requestFocus();
            InputMethodManager keyboard = (InputMethodManager)
                    activity.getSystemService(MainActivity.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(manualMealName,
                    InputMethodManager.SHOW_IMPLICIT);
        }, 180L);
    }

    private void saveManualMeal() {
        if (busy || mode != Mode.MANUAL_MEAL) return;
        String name = IntakeEvent.clean(manualMealName.getText().toString());
        if (name.isEmpty()) {
            toast(R.string.manual_meal_invalid_name);
            manualMealName.requestFocus();
            return;
        }
        float carbs;
        try {
            carbs = parseNumber(manualMealCarbs.getText().toString());
        } catch (NumberFormatException error) {
            carbs = -1.0f;
        }
        if (carbs < 0.0f || carbs > 1_000.0f) {
            toast(R.string.manual_meal_invalid_carbs);
            manualMealCarbs.requestFocus();
            return;
        }
        String portionText = IntakeEvent.clean(
                manualMealPortion.getText().toString());
        Float portion = null;
        if (!portionText.isEmpty()) {
            try {
                portion = parseNumber(portionText);
            } catch (NumberFormatException error) {
                portion = -1.0f;
            }
            if (portion <= 0.0f || portion > 10_000.0f) {
                toast(R.string.manual_meal_invalid_portion);
                manualMealPortion.requestFocus();
                return;
            }
        }
        setManualMealBusy(true);
        repository.createManualMeal(UUID.randomUUID().toString(),
                manualMealOccurredAtMs, name, carbs, portion,
                new IntakeRepository.Callback<IntakeEvent>() {
            @Override public void onSuccess(IntakeEvent value) {
                if (closed) return;
                toast(R.string.manual_meal_saved_locally);
                activity.requestRender();
                setManualMealBusy(false);
                close(true);
            }

            @Override public void onError(String message) {
                if (closed) return;
                setManualMealBusy(false);
                toast(activity.getString(R.string.intake_local_save_error,
                        message));
            }
        });
    }

    private void setManualMealBusy(boolean value) {
        busy = value;
        if (manualMealSave == null) return;
        manualMealSave.setEnabled(!value);
        manualMealSave.setText(value ? R.string.manual_meal_saving
                : R.string.manual_meal_save);
        manualMealName.setEnabled(!value);
        manualMealCarbs.setEnabled(!value);
        manualMealPortion.setEnabled(!value);
        manualMealTime.setEnabled(!value);
        root.findViewById(R.id.intake_back_button).setEnabled(!value);
    }

    private void showMeal() {
        if (busy || closed) return;
        mode = Mode.MEAL;
        replaceRoot(R.layout.modern_meal_chat);
        root.findViewById(R.id.intake_back_button)
                .setOnClickListener(view -> requestClose(true));
        mealScroll = root.findViewById(R.id.meal_chat_scroll);
        mealMessages = root.findViewById(R.id.meal_chat_messages);
        mealActionHistory = root.findViewById(
                R.id.meal_chat_action_history);
        attachmentScroll = root.findViewById(
                R.id.meal_chat_attachment_scroll);
        attachmentList = root.findViewById(R.id.meal_chat_attachments);
        mealTime = root.findViewById(R.id.meal_chat_time);
        mealInput = root.findViewById(R.id.meal_chat_input);
        mealAttach = root.findViewById(R.id.meal_chat_attach);
        mealVoice = root.findViewById(R.id.meal_chat_voice);
        mealVoiceIcon = root.findViewById(R.id.meal_chat_voice_icon);
        mealVoiceProgress = root.findViewById(
                R.id.meal_chat_voice_progress);
        intakeVoiceHint = root.findViewById(R.id.intake_chat_voice_hint);
        intakeVoiceHint.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_POLITE);
        intakeInlineError = root.findViewById(
                R.id.intake_chat_inline_error);
        intakeInlineError.setAccessibilityLiveRegion(
                View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        mealSend = root.findViewById(R.id.meal_chat_send);
        mealSendIcon = root.findViewById(R.id.meal_chat_send_icon);
        mealSendProgress = root.findViewById(
                R.id.meal_chat_send_progress);
        proposalCard = root.findViewById(R.id.meal_chat_proposal);
        proposalTitle = root.findViewById(R.id.meal_chat_proposal_title);
        proposalMeal = root.findViewById(R.id.meal_chat_proposal_meal);
        proposalCarbs = root.findViewById(R.id.meal_chat_proposal_carbs);
        proposalAbsorption = root.findViewById(
                R.id.meal_chat_proposal_absorption);
        proposalConfidence = root.findViewById(
                R.id.meal_chat_proposal_confidence);
        proposalWarnings = root.findViewById(
                R.id.meal_chat_proposal_warnings);
        proposalTime = root.findViewById(R.id.meal_chat_proposal_time);
        proposalHint = root.findViewById(R.id.meal_chat_proposal_hint);
        mealConfirm = root.findViewById(R.id.meal_chat_confirm);
        intakeReconcile = root.findViewById(R.id.intake_chat_reconcile);
        mealTime.setOnClickListener(view -> mealTimeAction());
        // The result-card timestamp describes the saved action. The time chip
        // above the conversation controls the next turn.
        proposalTime.setOnClickListener(null);
        ViewCompat.setAccessibilityHeading(proposalTitle, true);
        mealAttach.setOnClickListener(view -> choosePhotoAction());
        mealVoice.setOnClickListener(view -> voiceAction());
        mealSend.setOnClickListener(view -> sendMealMessage());
        mealConfirm.setOnClickListener(view -> confirmMeal());
        intakeReconcile.setOnClickListener(view -> reconcilePendingTurn());
        mealInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMealMessage();
                return true;
            }
            return false;
        });
        bindBackendStatus();
        renderChat();
        updateMealTime();
        updateAttachments();
        updateProposal();
        updateCorrectionUi();
        updateInlineError();
        applyDeferredTranscript();
        scrollChatToBottom();
        if (!retryClientTurnId.isEmpty()) {
            mealInput.setText(retryTurnControlKind != CONTROL_NONE
                    ? "" : retryTurnText);
            // Never submit a restored medical fact merely because a screen
            // reopened. Send performs an exact same-ID retry; Resolve lets the
            // user deliberately discard it after checking the timeline.
            setMealBusy(false);
        } else {
            ensureIntakeSession(null);
        }
        scheduleInitialVoiceRecording();
    }

    private void sendMealMessage() {
        if (busy || mode != Mode.MEAL) return;
        if (pendingPhotoImports > 0) {
            toast(R.string.meal_chat_media_processing);
            return;
        }
        if (recording) {
            stopRecording(true);
            return;
        }
        if (transcribing) {
            toast(R.string.meal_chat_voice_transcribing);
            return;
        }
        String currentText = IntakeEvent.clean(
                mealInput.getText().toString());
        if (!retryClientTurnId.isEmpty()
                && (currentText.equals(retryTurnText)
                        || retryTurnControlKind != CONTROL_NONE
                        && currentText.isEmpty())
                && pendingPhotos.equals(retryTurnPhotos)) {
            retryPendingIntakeTurn();
            return;
        }
        if (!retryClientTurnId.isEmpty()) clearPendingTurnRetry(true);
        if (currentText.isEmpty() && pendingPhotos.isEmpty()) {
            toast(R.string.meal_chat_empty_message);
            return;
        }
        sendIntakeTurn(null);
    }

    private void ensureIntakeSession(Runnable ready) {
        if (closed) return;
        if (!intakeSessionId.isEmpty()) {
            if (ready != null) ready.run();
            return;
        }
        if (ready != null) afterIntakeSessionReady = ready;
        if (intakeSessionStarting) return;
        intakeSessionStarting = true;
        final int generation = intakeSessionGeneration;
        repository.startIntakeChat(intakeClientSessionId,
                new IntakeRepository.Callback<IntakeChatSession>() {
            @Override public void onSuccess(IntakeChatSession session) {
                if (closed || generation != intakeSessionGeneration) return;
                intakeSessionStarting = false;
                if (session == null || session.id.isEmpty()) {
                    failIntakeSession(activity.getString(
                            R.string.intake_chat_session_error));
                    return;
                }
                intakeSessionId = session.id;
                sessionFailureShown = false;
                clearInlineErrorIf(INLINE_ERROR_CONNECTION);
                persistChatState();
                Runnable pending = afterIntakeSessionReady;
                afterIntakeSessionReady = null;
                if (pending != null) pending.run();
            }

            @Override public void onError(String message) {
                if (closed || generation != intakeSessionGeneration) return;
                intakeSessionStarting = false;
                failIntakeSession(activity.getString(
                        R.string.meal_chat_start_error, message));
            }
        });
    }

    private void failIntakeSession(String message) {
        boolean wasWaiting = afterIntakeSessionReady != null;
        afterIntakeSessionReady = null;
        if (wasWaiting) {
            releaseIntakeTurnFile(intakeTurnAudio, false);
            transcribing = false;
            mealSending = false;
            setMealBusy(false);
        }
        if (!sessionFailureShown) {
            sessionFailureShown = true;
            showInlineError(R.string.intake_chat_connection_inline_error,
                    INLINE_ERROR_CONNECTION);
        }
    }

    private void sendIntakeTurn(File audio) {
        sendIntakeTurn(audio, null, CONTROL_NONE);
    }

    private void sendIntakeTurn(File audio, String controlText,
            int controlKind) {
        boolean controlTurn = controlKind != CONTROL_NONE;
        if (closed || mode != Mode.MEAL || busy) {
            deleteTemporary(audio);
            return;
        }
        if (pendingPhotoImports > 0) {
            if (audio != null) {
                deleteTemporary(deferredIntakeAudio);
                deferredIntakeAudio = audio;
                transcribing = true;
                updateAttachments();
            }
            toast(R.string.meal_chat_media_processing);
            return;
        }
        String text = controlTurn ? IntakeEvent.clean(controlText)
                : IntakeEvent.clean(mealInput == null ? ""
                        : mealInput.getText().toString());
        ArrayList<File> photos = controlTurn
                ? new ArrayList<>() : new ArrayList<>(pendingPhotos);
        if (text.isEmpty() && photos.isEmpty() && audio == null) {
            toast(R.string.meal_chat_empty_message);
            return;
        }
        final String clientTurnId = UUID.randomUUID().toString();
        if (!retryClientTurnId.isEmpty()) clearPendingTurnRetry(true);
        if (!controlTurn && !mealTimeExplicitForNextTurn) {
            mealOccurredAtMs = System.currentTimeMillis();
            pendingMealOccurredAtMs = mealOccurredAtMs;
            updateMealTime();
        }
        if (!controlTurn) mealTimeExplicitForNextTurn = false;
        final long occurredAtMs = controlTurn
                ? System.currentTimeMillis() : mealOccurredAtMs;
        if (!rememberPendingTurn(clientTurnId, occurredAtMs, text, audio,
                photos, controlKind)) {
            clearPendingTurnRetry(false);
            deleteTemporary(audio);
            toast(R.string.intake_chat_pending_save_error);
            return;
        }
        intakeTurnAudio = audio;
        mealConfirming = controlKind == CONTROL_DELETE;
        mealSending = true;
        transcribing = audio != null;
        clearInlineError();
        setMealBusy(true);
        updateAttachments();
        ensureIntakeSession(() -> startIntakeTurnRequest(clientTurnId,
                occurredAtMs, text, audio, photos, controlKind));
    }

    private void retryPendingIntakeTurn() {
        if (retryClientTurnId.isEmpty() || busy || closed) return;
        if (!pendingTurnMatchesBackend()) {
            showInlineError(R.string.intake_backend_change_pending,
                    INLINE_ERROR_RESOLVE_TURN);
            if (mealSend != null) mealSend.setEnabled(false);
            updateReconcileAction();
            return;
        }
        clearInlineError();
        intakeTurnAudio = retryTurnAudio;
        mealConfirming = retryTurnControlKind == CONTROL_DELETE;
        mealSending = true;
        transcribing = retryTurnAudio != null;
        setMealBusy(true);
        updateAttachments();
        ArrayList<File> photos = new ArrayList<>(retryTurnPhotos);
        ensureIntakeSession(() -> startIntakeTurnRequest(retryClientTurnId,
                retryTurnOccurredAtMs, retryTurnText, retryTurnAudio,
                photos, retryTurnControlKind));
    }

    private boolean rememberPendingTurn(String clientTurnId, long occurredAtMs,
            String text, File audio, List<File> photos,
            int controlKind) {
        retryClientTurnId = clientTurnId;
        retryBackendFingerprint = backendFingerprint();
        retryTurnOccurredAtMs = occurredAtMs;
        retryTurnText = text;
        retryTurnAudio = audio;
        retryTurnPhotos.clear();
        retryTurnPhotos.addAll(photos);
        retryTurnDefinitiveFailure = false;
        retryTurnCommitMayHaveOccurred = false;
        retryTurnControlKind = controlKind;
        updateBackendSettingsAvailability();
        return persistChatState();
    }

    private void clearPendingTurnRetry(boolean deleteAudio) {
        File audio = retryTurnAudio;
        retryClientTurnId = "";
        retryBackendFingerprint = "";
        retryTurnOccurredAtMs = 0L;
        retryTurnText = "";
        retryTurnAudio = null;
        retryTurnPhotos.clear();
        retryTurnDefinitiveFailure = false;
        retryTurnCommitMayHaveOccurred = false;
        retryTurnControlKind = CONTROL_NONE;
        if (deleteAudio) deleteTemporary(audio);
        updateBackendSettingsAvailability();
        updateReconcileAction();
    }

    private void startIntakeTurnRequest(String clientTurnId,
            long occurredAtMs, String text, File audio,
            ArrayList<File> photos, int controlKind) {
        boolean controlTurn = controlKind != CONTROL_NONE;
        if (closed) {
            releaseIntakeTurnFile(audio, false);
            return;
        }
        activeIntakePhotos.clear();
        activeIntakePhotos.addAll(photos);
        intakeTurnCall = repository.sendIntakeChat(intakeSessionId,
                clientTurnId, occurredAtMs, text, audio, photos,
                new IntakeRepository.Callback<IntakeChatTurn>() {
            @Override public void onSuccess(IntakeChatTurn turn) {
                releaseIntakeTurnFile(audio, true);
                clearPendingTurnRetry(false);
                invalidateStaleControlTarget(turn, controlKind);
                if (closed) {
                    rememberActionReceipt(turn, false);
                    for (File photo : photos) deleteTemporary(photo);
                    return;
                }
                intakeTurnCall = null;
                activeIntakePhotos.clear();
                transcribing = false;
                mealSending = false;
                clearInlineError();
                String userText = IntakeEvent.clean(turn.transcript);
                if (userText.isEmpty()) userText = text;
                if (userText.isEmpty() && audio != null) {
                    userText = activity.getString(R.string.meal_chat_user_voice);
                }
                if (!controlTurn) {
                    chatLines.add(new ChatLine(true, userText, photos.size()));
                }
                if (!turn.assistantMessage.isEmpty()) {
                    chatLines.add(new ChatLine(false,
                            turn.assistantMessage, 0));
                }
                if (!controlTurn) {
                    clearSentMedia(photos);
                    if (mealInput != null) mealInput.setText("");
                }
                if (controlKind == CONTROL_DELETE) mealConfirming = false;
                acceptIntakeTurn(turn);
                setMealBusy(false);
                // Applied/undone receipts leave correction mode immediately.
                // Refresh the input hint as well as the card so it cannot keep
                // naming the pre-correction value after a successful replace.
                updateCorrectionUi();
                renderChat();
                updateAttachments();
                updateProposal();
                scrollChatToBottom();
                if (controlKind == CONTROL_REVISION
                        && IntakeChatTurn.OUTCOME_CLARIFICATION.equalsIgnoreCase(
                                turn.outcome)) {
                    prepareCorrectionInput();
                }
            }

            @Override public void onError(String message) {
                handleIntakeTurnFailure(audio, message, false, false);
            }

            @Override public void onDefinitiveError(String message,
                    boolean commitMayHaveOccurred) {
                handleIntakeTurnFailure(audio, message, true,
                        commitMayHaveOccurred);
            }
        });
    }

    private void handleIntakeTurnFailure(File audio, String message,
            boolean definitive, boolean commitMayHaveOccurred) {
        releaseIntakeTurnFile(audio, false);
        if (definitive) {
            retryTurnDefinitiveFailure = true;
            retryTurnCommitMayHaveOccurred = commitMayHaveOccurred;
            persistChatState();
        }
        if (closed) {
            // Keep the durable payload for exact same-ID reconciliation when
            // the user reopens the conversation.
            return;
        }
        intakeTurnCall = null;
        activeIntakePhotos.clear();
        transcribing = false;
        mealSending = false;
        mealConfirming = false;
        setMealBusy(false);
        if (definitive) {
            showInlineError(R.string.intake_chat_rejected_inline_error,
                    INLINE_ERROR_RESOLVE_TURN);
        } else {
            showInlineError(R.string.intake_chat_retry_inline_error,
                    INLINE_ERROR_RETRY_TURN);
        }
        persistChatState();
        renderChat();
        updateAttachments();
        updateReconcileAction();
        scrollChatToBottom();
    }

    private void releaseIntakeTurnFile(File audio, boolean delete) {
        if (intakeTurnAudio == audio) intakeTurnAudio = null;
        if (delete) deleteTemporary(audio);
    }

    private void acceptIntakeTurn(IntakeChatTurn turn) {
        rememberActionReceipt(turn, true);
    }

    /**
     * A deterministic control returning no_change means the backend no longer
     * has the action represented by our cached card. Keep the receipt visible
     * for this screen, but never let the user repeatedly mutate a stale target
     * or enter a correction flow that could create a new record by mistake.
     */
    private boolean invalidateStaleControlTarget(IntakeChatTurn turn,
            int controlKind) {
        if (controlKind == CONTROL_NONE || turn == null
                || !IntakeChatTurn.OUTCOME_NO_CHANGE.equalsIgnoreCase(
                        turn.outcome)) {
            return false;
        }
        lastActionId = "";
        lastActionDeleted = false;
        correctionMode = false;
        correctionSummary = "";
        return true;
    }

    private void rememberActionReceipt(IntakeChatTurn turn,
            boolean renderGraph) {
        if (turn == null) return;
        lastIntakeTurn = turn;
        actionCardHistory.accept(turn);
        if (IntakeChatTurn.OUTCOME_APPLIED.equalsIgnoreCase(turn.outcome)
                && !turn.actionId.isEmpty()) {
            lastActionId = turn.actionId;
            lastActionTurn = turn;
            lastActionDeleted = false;
            correctionMode = false;
            correctionSummary = "";
        }
        if (IntakeChatTurn.OUTCOME_UNDONE.equalsIgnoreCase(turn.outcome)
                || IntakeChatTurn.OUTCOME_ALREADY_UNDONE.equalsIgnoreCase(
                        turn.outcome)) {
            lastActionId = "";
            if (turn.events.isEmpty()) {
                // A create was removed. Keep its previous applied receipt so
                // the card can remain as an informative tombstone.
                lastActionDeleted = true;
                if (lastActionTurn == null) lastActionTurn = turn;
            } else {
                // Undoing a replacement restored the previous record. Show
                // the restored facts as Changed, never as a deletion.
                lastActionTurn = turn;
                lastActionDeleted = false;
            }
            correctionMode = false;
            correctionSummary = "";
        }
        persistChatState();
        if (renderGraph && (!turn.events.isEmpty()
                || !turn.deletedEventIds.isEmpty())) {
            activity.requestRender();
        }
    }

    /**
     * A proposal belongs to the assistant's latest completed turn. As soon as
     * the user sends a correction it must no longer look actionable while the
     * replacement estimate is being calculated.
     */
    private void clearProposalForRevision() {
        mealReadyToConfirm = false;
        mealProposal = null;
        if (proposalCard != null) proposalCard.setVisibility(GONE);
    }

    private void clearSentMedia(List<File> photos) {
        for (File photo : photos) {
            pendingPhotos.remove(photo);
            deleteTemporary(photo);
        }
    }

    private void prepareCorrectionInput() {
        if (lastActionDeleted || lastActionTurn == null) return;
        correctionMode = true;
        correctionSummary = primaryActionSummary(lastActionTurn);
        updateCorrectionUi();
        scrollChatToBottom();
        if (mealVoice != null) mealVoice.requestFocus();
    }

    private void updateCorrectionUi() {
        if (mealInput != null) {
            mealInput.setHint(correctionMode
                    ? activity.getString(R.string.intake_chat_correction_input_hint,
                            correctionSummary)
                    : activity.getString(R.string.meal_chat_message_hint));
        }
        updateVoiceButton();
    }

    /** Delete is an explicit chat control, distinct from inverse Undo. */
    private void confirmMeal() {
        if (busy || lastActionDeleted || lastActionId.isEmpty()) return;
        correctionMode = false;
        correctionSummary = "";
        updateCorrectionUi();
        clearInlineError();
        sendIntakeTurn(null, activity.getString(
                R.string.intake_chat_delete_control), CONTROL_DELETE);
    }

    private void reconcilePendingTurn() {
        if (closed || busy || retryClientTurnId.isEmpty()) return;
        int message = !retryTurnDefinitiveFailure
                || retryTurnCommitMayHaveOccurred
                ? R.string.intake_chat_reconcile_uncertain
                : R.string.intake_chat_reconcile_safe;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.intake_chat_reconcile_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.intake_chat_reconcile_confirm,
                        (dialog, which) -> discardPendingTurnAndStartFresh())
                .show();
    }

    private void discardPendingTurnAndStartFresh() {
        if (closed || busy || retryClientTurnId.isEmpty()) return;
        ArrayList<File> failedPhotos = new ArrayList<>(retryTurnPhotos);
        clearPendingTurnRetry(true);
        for (File photo : failedPhotos) {
            pendingPhotos.remove(photo);
            deleteTemporary(photo);
        }
        intakeSessionGeneration++;
        intakeClientSessionId = UUID.randomUUID().toString();
        intakeSessionId = "";
        intakeSessionStarting = false;
        afterIntakeSessionReady = null;
        sessionFailureShown = false;
        lastActionId = "";
        lastIntakeTurn = null;
        lastActionTurn = null;
        lastActionDeleted = false;
        actionCardHistory.clear();
        correctionMode = false;
        correctionSummary = "";
        if (mealInput != null) mealInput.setText("");
        clearInlineError();
        chatLines.add(new ChatLine(false, activity.getString(
                R.string.intake_chat_reconcile_complete), 0));
        persistChatState();
        setMealBusy(false);
        renderChat();
        updateAttachments();
        updateProposal();
        scrollChatToBottom();
        ensureIntakeSession(null);
    }

    private void renderChat() {
        if (mealMessages == null) return;
        if (renderedChatLineCount > chatLines.size()
                || mealMessages.getChildCount() != renderedChatLineCount) {
            mealMessages.removeAllViews();
            renderedChatLineCount = 0;
        }
        while (renderedChatLineCount < chatLines.size()) {
            addChatBubble(chatLines.get(renderedChatLineCount));
            renderedChatLineCount++;
        }
    }

    private void addChatBubble(ChatLine line) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(line.user ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        rowParams.bottomMargin = dp(9);
        row.setLayoutParams(rowParams);

        TextView bubble = new TextView(activity);
        StringBuilder text = new StringBuilder(line.text);
        if (line.photoCount > 0) {
            if (text.length() > 0) text.append("\n\n");
            text.append(activity.getResources().getQuantityString(
                    R.plurals.meal_chat_user_photos_count,
                    line.photoCount,
                    line.photoCount));
        }
        bubble.setText(text.toString());
        bubble.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_primary));
        bubble.setTextSize(14.0f);
        bubble.setLineSpacing(0.0f, 1.08f);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        int windowWidth = root != null && root.getWidth() > 0
                ? root.getWidth() : activity.getResources()
                .getDisplayMetrics().widthPixels;
        bubble.setMaxWidth(Math.min((int) (windowWidth * 0.84f), dp(704)));
        bubble.setBackgroundResource(line.user
                ? R.drawable.intake_chat_user
                : R.drawable.intake_chat_assistant);
        row.addView(bubble, new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT));
        mealMessages.addView(row);
    }

    private void updateProposal() {
        if (proposalCard == null) return;
        IntakeChatTurn turn = lastActionTurn;
        IntakeChatCardHistory.Card current =
                actionCardHistory.primaryForTurn(turn);
        renderActionCardHistory(current);
        if (turn == null || current == null) {
            proposalCard.setVisibility(GONE);
            return;
        }

        IntakeEvent event = current.event();
        boolean inactive = !current.isActive();
        proposalCard.setVisibility(VISIBLE);
        proposalCard.setEnabled(!inactive);
        proposalCard.setClickable(false);
        proposalCard.setFocusable(false);
        proposalCard.setBackgroundResource(inactive
                ? R.drawable.intake_chat_proposal_inactive
                : R.drawable.intake_chat_proposal);

        boolean corrected = current.isActive()
                && (!turn.deletedEventIds.isEmpty()
                || IntakeChatTurn.OUTCOME_UNDONE.equalsIgnoreCase(turn.outcome)
                || IntakeChatTurn.OUTCOME_ALREADY_UNDONE.equalsIgnoreCase(
                        turn.outcome));
        proposalTitle.setText(cardStatusText(current, corrected));
        proposalTitle.setTextColor(ContextCompat.getColor(activity,
                inactive ? R.color.modern_secondary_text_secondary
                        : R.color.modern_secondary_accent));
        proposalMeal.setText(eventSummary(event));
        proposalMeal.setTextColor(ContextCompat.getColor(activity,
                inactive ? R.color.modern_secondary_text_secondary
                        : R.color.modern_secondary_text_primary));
        proposalCarbs.setText("");
        int summaryFlags = proposalMeal.getPaintFlags();
        proposalMeal.setPaintFlags(inactive
                ? summaryFlags | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                : summaryFlags & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);

        boolean estimatedMeal = event.hasMeal()
                && (event.carbsSource.toLowerCase(Locale.ROOT).contains("ai")
                || !event.analysisId.isEmpty());
        if (!inactive && event.hasAbsorptionSpeed()) {
            proposalAbsorption.setText(CarbAbsorptionUi.details(activity,
                    event.absorptionSpeed,
                    event.absorptionPeakMinutes,
                    event.absorptionDurationMinutes,
                    event.absorptionConfidence));
            proposalAbsorption.setVisibility(VISIBLE);
        } else {
            proposalAbsorption.setVisibility(GONE);
        }
        if (!inactive && estimatedMeal) {
            proposalConfidence.setText(event.aiConfidence > 0.0f
                    ? activity.getString(R.string.intake_chat_ai_estimate_confidence,
                            Math.round(event.aiConfidence * 100.0f))
                    : activity.getString(R.string.intake_chat_ai_estimate));
            proposalConfidence.setVisibility(VISIBLE);
            proposalWarnings.setText(R.string.intake_chat_ai_warning);
            proposalWarnings.setVisibility(VISIBLE);
        } else {
            proposalConfidence.setVisibility(GONE);
            proposalWarnings.setVisibility(GONE);
        }
        proposalTime.setText(activity.getString(
                R.string.intake_chat_action_time,
                formatProposalTime(event.occurredAtMs)));
        proposalTime.setTextColor(ContextCompat.getColor(activity,
                inactive ? R.color.modern_secondary_text_secondary
                        : R.color.modern_secondary_text_primary));
        proposalTime.setVisibility(VISIBLE);

        boolean currentAction = current.isActive() && !lastActionDeleted
                && !lastActionId.isEmpty()
                && lastActionId.equals(current.actionId);
        boolean cardDeleteAvailable = currentAction
                && IntakeChatCardHistory.supportsSingleCardDelete(turn);
        boolean compoundAction = turn != null && turn.events.size() > 1;
        boolean controlsEnabled = cardDeleteAvailable && !busy
                && retryClientTurnId.isEmpty();
        proposalHint.setText(current.status()
                == IntakeChatCardHistory.Status.DELETED
                ? R.string.intake_chat_deleted_hint
                : current.status() == IntakeChatCardHistory.Status.REPLACED
                        ? R.string.intake_chat_card_replaced_hint
                        : currentAction
                                ? cardDeleteAvailable
                                        ? R.string.intake_chat_correction_hint
                                        : compoundAction
                                                ? R.string.intake_chat_compound_action_hint
                                                : R.string.intake_chat_active_replacement_hint
                                : lastActionId.isEmpty()
                                        ? R.string.intake_chat_card_active_hint
                                        : R.string.intake_chat_unavailable_hint);
        proposalHint.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_secondary));
        // Historical and tombstoned cards are information, not controls.
        mealConfirm.setVisibility(cardDeleteAvailable ? VISIBLE : GONE);
        mealConfirm.setEnabled(controlsEnabled);
        mealConfirm.setAlpha(controlsEnabled ? 1.0f : 0.52f);
        mealConfirm.setText(mealConfirming
                ? R.string.intake_chat_action_deleting
                : R.string.intake_chat_action_delete);
        mealConfirm.setContentDescription(activity.getString(
                R.string.intake_chat_action_delete_description,
                primaryActionSummary(turn)));
    }

    private void renderActionCardHistory(
            IntakeChatCardHistory.Card current) {
        if (mealActionHistory == null) return;
        mealActionHistory.removeAllViews();
        for (IntakeChatCardHistory.Card card : actionCardHistory.cards()) {
            if (card != current) addHistoricalActionCard(card);
        }
    }

    private void addHistoricalActionCard(IntakeChatCardHistory.Card card) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(14), dp(16), dp(14));
        container.setBackgroundResource(card.isActive()
                ? R.drawable.intake_chat_proposal
                : R.drawable.intake_chat_proposal_inactive);
        container.setClickable(false);
        container.setFocusable(false);
        container.setEnabled(card.isActive());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        params.topMargin = dp(5);
        params.bottomMargin = dp(5);
        container.setLayoutParams(params);

        TextView status = actionCardText(13.0f, true);
        status.setText(cardStatusText(card, false));
        status.setTextColor(ContextCompat.getColor(activity,
                card.isActive() ? R.color.modern_secondary_accent
                        : R.color.modern_secondary_text_secondary));
        container.addView(status);

        TextView summary = actionCardText(18.0f, true);
        summary.setText(eventSummary(card.event()));
        summary.setTextColor(ContextCompat.getColor(activity,
                card.isActive() ? R.color.modern_secondary_text_primary
                        : R.color.modern_secondary_text_secondary));
        if (!card.isActive()) {
            summary.setPaintFlags(summary.getPaintFlags()
                    | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        summaryParams.topMargin = dp(8);
        container.addView(summary, summaryParams);

        TextView time = actionCardText(12.0f, false);
        time.setText(activity.getString(R.string.intake_chat_action_time,
                formatProposalTime(card.event().occurredAtMs)));
        time.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_secondary));
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        timeParams.topMargin = dp(8);
        container.addView(time, timeParams);

        TextView hint = actionCardText(12.0f, false);
        hint.setText(cardHistoryHint(card));
        hint.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_secondary));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                MATCH_PARENT, WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        container.addView(hint, hintParams);

        container.setContentDescription(activity.getString(
                R.string.intake_chat_card_description,
                status.getText(), summary.getText(), time.getText(),
                hint.getText()));
        container.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ViewCompat.setAccessibilityHeading(container, true);
        status.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        summary.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        time.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mealActionHistory.addView(container);
    }

    private TextView actionCardText(float sizeSp, boolean bold) {
        TextView view = new TextView(activity);
        view.setTextSize(sizeSp);
        view.setLineSpacing(0.0f, 1.08f);
        view.setTextIsSelectable(false);
        if (bold) view.setTypeface(view.getTypeface(),
                android.graphics.Typeface.BOLD);
        return view;
    }

    private int cardStatusText(IntakeChatCardHistory.Card card,
            boolean corrected) {
        if (card.status() == IntakeChatCardHistory.Status.DELETED) {
            return R.string.intake_chat_action_undone;
        }
        if (card.status() == IntakeChatCardHistory.Status.REPLACED) {
            return R.string.intake_chat_card_replaced;
        }
        return corrected ? R.string.intake_chat_action_corrected
                : R.string.intake_chat_card_active;
    }

    private int cardHistoryHint(IntakeChatCardHistory.Card card) {
        if (card.status() == IntakeChatCardHistory.Status.DELETED) {
            return R.string.intake_chat_deleted_hint;
        }
        if (card.status() == IntakeChatCardHistory.Status.REPLACED) {
            return R.string.intake_chat_card_replaced_hint;
        }
        return R.string.intake_chat_card_active_hint;
    }

    private String eventSummary(IntakeEvent event) {
        if (event == null) return activity.getString(
                R.string.intake_chat_no_action);
        if (event.hasInsulin()) {
            return activity.getString(R.string.intake_chat_action_insulin,
                    event.insulinDisplayName(),
                    formatNumber(event.insulinUnits));
        }
        if (event.hasMeal()) {
            String meal = event.mealText.isEmpty()
                    ? activity.getString(R.string.intake_details_meal_title)
                    : event.mealText;
            return event.hasCarbs()
                    ? activity.getString(R.string.intake_chat_action_meal,
                            meal, formatNumber(event.carbsGrams))
                    : meal;
        }
        return activity.getString(R.string.intake_chat_no_action);
    }

    private ArrayList<String> actionSummaries(IntakeChatTurn turn) {
        ArrayList<String> summaries = new ArrayList<>();
        if (turn == null) return summaries;
        for (IntakeEvent event : turn.events) {
            if (event.hasInsulin()) {
                summaries.add(activity.getString(
                        R.string.intake_chat_action_insulin,
                        event.insulinDisplayName(),
                        formatNumber(event.insulinUnits)));
            } else if (event.hasMeal()) {
                String meal = event.mealText.isEmpty()
                        ? activity.getString(R.string.intake_details_meal_title)
                        : event.mealText;
                summaries.add(event.hasCarbs()
                        ? activity.getString(R.string.intake_chat_action_meal,
                                meal, formatNumber(event.carbsGrams))
                        : meal);
            }
        }
        return summaries;
    }

    private String primaryActionSummary(IntakeChatTurn turn) {
        ArrayList<String> summaries = actionSummaries(turn);
        return summaries.isEmpty()
                ? activity.getString(R.string.intake_chat_no_action)
                : summaries.get(0);
    }

    private void setMealBusy(boolean value) {
        busy = value;
        if (mealSend == null) return;
        boolean pendingUnknown = !retryClientTurnId.isEmpty();
        mealSend.setEnabled(!value && pendingTurnMatchesBackend());
        mealInput.setEnabled(!value && !pendingUnknown);
        mealAttach.setEnabled(!value && !pendingUnknown);
        mealTime.setEnabled(!value && !pendingUnknown);
        if (proposalTime != null) {
            proposalTime.setEnabled(!value);
            proposalTime.setAlpha(value ? 0.68f : 1.0f);
        }
        root.findViewById(R.id.intake_back_button).setEnabled(!value);
        updateBackendSettingsAvailability();
        updateMealTime();
        updateSendButton();
        updateVoiceButton();
        if (mealConfirm != null) updateProposal();
        updateReconcileAction();
        updateInlineError();
    }

    private void showInlineError(int message, int action) {
        intakeInlineErrorMessage = message;
        intakeInlineErrorAction = action;
        updateInlineError();
    }

    private void clearInlineError() {
        intakeInlineErrorMessage = 0;
        intakeInlineErrorAction = INLINE_ERROR_NONE;
        updateInlineError();
    }

    private void clearInlineErrorIf(int action) {
        if (intakeInlineErrorAction == action) clearInlineError();
    }

    private void updateInlineError() {
        if (intakeInlineError == null) return;
        boolean visible = intakeInlineErrorMessage != 0;
        intakeInlineError.setVisibility(visible ? VISIBLE : GONE);
        if (!visible) {
            intakeInlineError.setOnClickListener(null);
            return;
        }
        intakeInlineError.setText(intakeInlineErrorMessage);
        intakeInlineError.setEnabled(!busy);
        intakeInlineError.setAlpha(busy ? 0.68f : 1.0f);
        intakeInlineError.setOnClickListener(view -> performInlineErrorAction());
    }

    private void performInlineErrorAction() {
        if (closed || busy) return;
        switch (intakeInlineErrorAction) {
            case INLINE_ERROR_CONNECTION:
                sessionFailureShown = false;
                clearInlineError();
                checkBackend();
                ensureIntakeSession(null);
                break;
            case INLINE_ERROR_RETRY_TURN:
                clearInlineError();
                retryPendingIntakeTurn();
                break;
            case INLINE_ERROR_RESOLVE_TURN:
                reconcilePendingTurn();
                break;
            case INLINE_ERROR_UNDO:
                clearInlineError();
                confirmMeal();
                break;
            case INLINE_ERROR_VOICE:
                clearInlineError();
                voiceAction();
                break;
            default:
                break;
        }
    }

    private void updateReconcileAction() {
        if (intakeReconcile == null) return;
        boolean visible = !retryClientTurnId.isEmpty();
        intakeReconcile.setVisibility(visible ? VISIBLE : GONE);
        intakeReconcile.setEnabled(visible && !busy);
    }

    private void choosePhotoAction() {
        if (busy || recording || mode != Mode.MEAL) return;
        if (pendingPhotos.size() + pendingPhotoImports
                >= MAX_PHOTOS_PER_MESSAGE) {
            toast(activity.getString(R.string.meal_chat_photo_limit,
                    MAX_PHOTOS_PER_MESSAGE));
            return;
        }
        String[] choices = {
                activity.getString(R.string.intake_photo_take),
                activity.getString(R.string.intake_photo_choose)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.meal_chat_attach)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) launchCamera();
                    else launchGallery();
                }).show();
    }

    private void launchCamera() {
        try {
            pendingCameraFile = File.createTempFile("meal-camera-", ".jpg",
                    mediaDirectory());
            temporaryFiles.add(pendingCameraFile);
            Uri output = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".intake.files",
                    pendingCameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (intent.resolveActivity(activity.getPackageManager()) == null) {
                deleteTemporary(pendingCameraFile);
                pendingCameraFile = null;
                toast(R.string.intake_camera_unavailable);
                return;
            }
            activity.startActivityForResult(intent, REQUEST_MEAL_CAMERA);
        } catch (Exception error) {
            deleteTemporary(pendingCameraFile);
            pendingCameraFile = null;
            toast(R.string.intake_media_error);
        }
    }

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(intent, REQUEST_MEAL_GALLERY);
    }

    boolean handleActivityResult(int requestCode, int resultCode,
            Intent data) {
        if (requestCode == REQUEST_MEAL_CAMERA) {
            File captured = pendingCameraFile;
            pendingCameraFile = null;
            if (resultCode == RESULT_OK && captured != null
                    && captured.isFile() && captured.length() > 0L) {
                importPhoto(Uri.fromFile(captured), captured);
            } else {
                deleteTemporary(captured);
            }
            return true;
        }
        if (requestCode == REQUEST_MEAL_GALLERY) {
            if (resultCode == RESULT_OK && data != null) {
                importGalleryResult(data);
            }
            return true;
        }
        return false;
    }

    private void importGalleryResult(Intent data) {
        ArrayList<Uri> sources = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) sources.add(uri);
            }
        } else if (data.getData() != null) {
            sources.add(data.getData());
        }
        int available = Math.max(0, MAX_PHOTOS_PER_MESSAGE
                - pendingPhotos.size() - pendingPhotoImports);
        if (sources.size() > available) {
            toast(activity.getString(R.string.meal_chat_photo_limit,
                    MAX_PHOTOS_PER_MESSAGE));
        }
        for (int index = 0; index < Math.min(available, sources.size());
                index++) {
            importPhoto(sources.get(index), null);
        }
    }

    private void importPhoto(Uri source, File sourceToDelete) {
        pendingPhotoImports++;
        updateAttachments();
        mediaExecutor.execute(() -> {
            File result = null;
            try {
                result = normalizePhoto(source);
            } catch (Exception | OutOfMemoryError ignored) {
                // A concise local media error is shown below.
            } finally {
                deleteTemporary(sourceToDelete);
            }
            File normalized = result;
            main.post(() -> {
                pendingPhotoImports = Math.max(0, pendingPhotoImports - 1);
                if (closed) {
                    deleteTemporary(normalized);
                    return;
                }
                if (normalized == null) {
                    toast(R.string.intake_media_error);
                } else if (pendingPhotos.size() < MAX_PHOTOS_PER_MESSAGE) {
                    pendingPhotos.add(normalized);
                } else {
                    deleteTemporary(normalized);
                }
                if (mode == Mode.MEAL) updateAttachments();
                if (pendingPhotoImports == 0 && deferredIntakeAudio != null) {
                    File queuedAudio = deferredIntakeAudio;
                    deferredIntakeAudio = null;
                    transcribing = false;
                    sendIntakeTurn(queuedAudio);
                }
            });
        });
    }

    private File normalizePhoto(Uri source) throws IOException {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        if (Build.VERSION.SDK_INT >= 24) {
            try (InputStream input = openSource(source)) {
                orientation = new ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } catch (RuntimeException ignored) {
                // A bitmap without EXIF can still be used.
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = openSource(source)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unsupported image");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                MAX_IMAGE_EDGE);
        Bitmap decoded;
        try (InputStream input = openSource(source)) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new IOException("Unsupported image");
        Bitmap transformed = orientBitmap(decoded, orientation);
        Bitmap scaled = scaleDown(transformed, MAX_IMAGE_EDGE);
        File result = File.createTempFile("meal-photo-", ".jpg",
                mediaDirectory());
        temporaryFiles.add(result);
        boolean written;
        try (BufferedOutputStream output = new BufferedOutputStream(
                new FileOutputStream(result))) {
            written = scaled.compress(Bitmap.CompressFormat.JPEG, 90, output);
        } finally {
            if (scaled != transformed) scaled.recycle();
            if (transformed != decoded) transformed.recycle();
            decoded.recycle();
        }
        if (!written || result.length() <= 0L) {
            deleteTemporary(result);
            throw new IOException("Could not normalize image");
        }
        return result;
    }

    private InputStream openSource(Uri source) throws IOException {
        InputStream raw;
        if ("file".equalsIgnoreCase(source.getScheme())) {
            raw = new FileInputStream(new File(source.getPath()));
        } else {
            raw = activity.getContentResolver().openInputStream(source);
        }
        if (raw == null) throw new IOException("Could not open image");
        return new BufferedInputStream(raw);
    }

    private void updateAttachments() {
        if (attachmentList == null) return;
        attachmentList.removeAllViews();
        for (File photo : pendingPhotos) {
            addAttachmentPreview(photo);
        }
        if (transcribing) addTranscriptionStatus();
        if (pendingPhotoImports > 0) {
            TextView loading = attachmentLabel(
                    activity.getString(R.string.meal_chat_media_processing));
            attachmentList.addView(loading);
        }
        boolean visible = !pendingPhotos.isEmpty() || transcribing
                || pendingPhotoImports > 0;
        attachmentScroll.setVisibility(visible ? VISIBLE : GONE);
        updateVoiceButton();
    }

    private void addAttachmentPreview(File photo) {
        FrameLayout card = new FrameLayout(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(72), dp(72));
        params.rightMargin = dp(8);
        card.setLayoutParams(params);
        card.setBackgroundResource(R.drawable.intake_photo);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(decodePreview(photo));
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                MATCH_PARENT, MATCH_PARENT);
        imageParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        card.addView(image, imageParams);
        TextView remove = new TextView(activity);
        remove.setText("×");
        remove.setGravity(Gravity.CENTER);
        remove.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_primary));
        remove.setTextSize(18.0f);
        remove.setBackgroundResource(R.drawable.intake_close);
        remove.setContentDescription(activity.getString(
                R.string.meal_chat_remove_photo));
        remove.setOnClickListener(view -> {
            if (busy) return;
            pendingPhotos.remove(photo);
            deleteTemporary(photo);
            updateAttachments();
        });
        FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        removeParams.topMargin = dp(2);
        removeParams.rightMargin = dp(2);
        card.addView(remove, removeParams);
        attachmentList.addView(card);
    }

    private void addTranscriptionStatus() {
        TextView status = attachmentLabel(
                activity.getString(R.string.meal_chat_voice_transcribing));
        status.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_warning));
        attachmentList.addView(status);
    }

    private TextView attachmentLabel(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_primary));
        view.setTextSize(12.0f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackgroundResource(R.drawable.intake_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                WRAP_CONTENT, dp(52));
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private static Bitmap decodePreview(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                PREVIEW_EDGE);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void scheduleInitialVoiceRecording() {
        if (!initialVoiceAutoStartPending || root == null) return;
        if (!retryClientTurnId.isEmpty()) {
            // A restored turn may already have reached the backend. Never
            // start a new capture after reopening until the user explicitly
            // retries or resolves that exact pending medical fact.
            initialVoiceAutoStartPending = false;
            return;
        }
        final View surface = root;
        surface.post(() -> {
            if (!initialVoiceAutoStartPending || closed || surface != root
                    || !surface.isAttachedToWindow()) return;
            // showMeal() also runs after a Fold/configuration change. Consume
            // this one-shot only on the currently attached surface so an old
            // posted callback cannot start a second recorder.
            initialVoiceAutoStartPending = false;
            if (canStartVoiceRecording()) requestOrStartRecording();
        });
    }

    private void voiceAction() {
        if (busy || mode != Mode.MEAL) return;
        if (!retryClientTurnId.isEmpty()) {
            toast(R.string.intake_chat_retry_hint);
            return;
        }
        if (pendingPhotoImports > 0) {
            toast(R.string.meal_chat_media_processing);
            return;
        }
        if (transcribing) {
            toast(R.string.meal_chat_voice_transcribing);
            return;
        }
        if (recording) {
            stopRecording(true);
            return;
        }
        voiceStatusMessage = 0;
        requestOrStartRecording();
    }

    private boolean canStartVoiceRecording() {
        return !closed && mode == Mode.MEAL && !busy && !recording
                && recorder == null && !transcribing
                && pendingPhotoImports == 0 && retryClientTurnId.isEmpty()
                && !recordPermissionRequestInFlight;
    }

    private void requestOrStartRecording() {
        if (!canStartVoiceRecording()) return;
        voiceStatusMessage = 0;
        if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            recordPermissionRequestInFlight = true;
            updateVoiceButton();
            try {
                activity.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO);
            } catch (RuntimeException error) {
                recordPermissionRequestInFlight = false;
                showVoiceError(R.string.intake_voice_permission);
            }
        } else {
            startRecording();
        }
    }

    boolean handlePermissionResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO) return false;
        recordPermissionRequestInFlight = false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && canStartVoiceRecording()) startRecording();
        else if (!granted && !closed) {
            showVoiceError(R.string.intake_voice_permission);
        } else {
            updateVoiceButton();
        }
        return true;
    }

    private void startRecording() {
        if (!canStartVoiceRecording()) return;
        voiceStatusMessage = 0;
        try {
            recordingFile = File.createTempFile("meal-voice-", ".m4a",
                    mediaDirectory());
            temporaryFiles.add(recordingFile);
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            // Speech-optimized mono audio halves upload/base64 work compared
            // with the old 44.1 kHz capture without discarding voice detail.
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16_000);
            recorder.setAudioEncodingBitRate(32_000);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.setMaxDuration(VOICE_MAX_DURATION_MS);
            recorder.setOnInfoListener((active, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    recorderReachedLimit = true;
                    main.post(() -> stopRecording(true));
                }
            });
            recorder.prepare();
            recorder.start();
            recorderReachedLimit = false;
            recording = true;
            updateVoiceButton();
        } catch (Exception error) {
            stopRecording(false);
            showVoiceError(R.string.intake_voice_failed);
        }
    }

    private void showVoiceError(int message) {
        voiceStatusMessage = message;
        updateVoiceButton();
        toast(message);
    }

    private void stopRecording(boolean keep) {
        MediaRecorder active = recorder;
        recorder = null;
        boolean wasRecording = recording;
        recording = false;
        if (active != null) {
            try {
                if (wasRecording) active.stop();
            } catch (RuntimeException error) {
                // Some devices report the max-duration callback after the
                // recorder has already finalized the file. Preserve that
                // non-empty recording and let backend validation decide.
                if (!recorderReachedLimit || recordingFile == null
                        || recordingFile.length() <= 0L) {
                    keep = false;
                }
            }
            try { active.reset(); } catch (RuntimeException ignored) {}
            active.release();
        }
        File completed = recordingFile;
        recordingFile = null;
        recorderReachedLimit = false;
        if (keep && completed != null && completed.length() > 0L) {
            sendIntakeTurn(completed);
        } else {
            deleteTemporary(completed);
        }
        if (mode == Mode.MEAL) updateAttachments();
    }

    private void transcribeRecording(File audio) {
        if (audio == null) return;
        if (closed) {
            deleteTemporary(audio);
            return;
        }
        transcribing = true;
        transcriptionFile = audio;
        final int generation = ++transcriptionGeneration;
        if (mode == Mode.MEAL) updateAttachments();
        transcriptionCall = repository.transcribeAudio(audio,
                new IntakeRepository.Callback<String>() {
            @Override public void onSuccess(String text) {
                finishTranscriptionFile(audio);
                if (generation != transcriptionGeneration || closed) return;
                transcribing = false;
                String transcript = IntakeEvent.clean(text);
                if (transcript.isEmpty()) {
                    toast(R.string.meal_chat_voice_empty);
                } else if (mode == Mode.MEAL && mealInput != null) {
                    insertEditableTranscript(transcript);
                    toast(R.string.meal_chat_voice_transcribed);
                } else {
                    deferredTranscript = transcript;
                }
                if (mode == Mode.MEAL) updateAttachments();
            }

            @Override public void onError(String message) {
                finishTranscriptionFile(audio);
                if (generation != transcriptionGeneration || closed) return;
                transcribing = false;
                if (mode == Mode.MEAL) updateAttachments();
                showInlineError(R.string.intake_chat_voice_inline_error,
                        INLINE_ERROR_VOICE);
            }
        });
    }

    private void finishTranscriptionFile(File audio) {
        if (transcriptionFile == audio) {
            transcriptionFile = null;
            transcriptionCall = null;
        }
        deleteTemporary(audio);
    }

    private void applyDeferredTranscript() {
        if (deferredTranscript.isEmpty() || mealInput == null) return;
        String transcript = deferredTranscript;
        deferredTranscript = "";
        insertEditableTranscript(transcript);
        toast(R.string.meal_chat_voice_transcribed);
    }

    private void insertEditableTranscript(String transcript) {
        if (mealInput == null) return;
        String clean = IntakeEvent.clean(transcript);
        if (clean.isEmpty()) return;
        Editable editable = mealInput.getText();
        int length = editable.length();
        int selectionStart = mealInput.getSelectionStart();
        int selectionEnd = mealInput.getSelectionEnd();
        if (selectionStart < 0 || selectionEnd < 0) {
            selectionStart = selectionEnd = length;
        }
        int start = Math.max(0, Math.min(length,
                Math.min(selectionStart, selectionEnd)));
        int end = Math.max(start, Math.min(length,
                Math.max(selectionStart, selectionEnd)));
        boolean leadingSpace = start > 0
                && !Character.isWhitespace(editable.charAt(start - 1));
        boolean trailingSpace = end < length
                && !Character.isWhitespace(editable.charAt(end));
        String insertion = (leadingSpace ? " " : "") + clean
                + (trailingSpace ? " " : "");
        editable.replace(start, end, insertion);
        int cursor = start + insertion.length()
                - (trailingSpace ? 1 : 0);
        mealInput.setSelection(Math.max(0,
                Math.min(editable.length(), cursor)));
        mealInput.requestFocus();
        mealInput.post(() -> {
            if (closed || mode != Mode.MEAL || mealInput == null) return;
            InputMethodManager keyboard = (InputMethodManager)
                    activity.getSystemService(
                            MainActivity.INPUT_METHOD_SERVICE);
            if (keyboard != null) {
                keyboard.showSoftInput(mealInput,
                        InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void updateVoiceButton() {
        if (mealVoice == null || mealVoiceIcon == null
                || mealVoiceProgress == null) return;
        // Recording is interactive, so keep the stop glyph clean and easy to
        // hit. The spinner belongs to the upload/transcription phase after the
        // recording has stopped.
        boolean showProgress = transcribing;
        mealVoice.setSelected(recording);
        mealVoice.setActivated(transcribing || recordPermissionRequestInFlight);
        mealVoice.setEnabled(!busy && !transcribing
                && !recordPermissionRequestInFlight
                && pendingPhotoImports == 0 && retryClientTurnId.isEmpty());
        mealVoice.setContentDescription(activity.getString(recording
                ? R.string.meal_chat_voice_stop
                : transcribing ? R.string.meal_chat_voice_transcribing
                : recordPermissionRequestInFlight
                ? R.string.intake_chat_microphone_permission
                : R.string.meal_chat_voice));
        mealVoiceProgress.setVisibility(showProgress ? VISIBLE : GONE);
        mealVoiceIcon.setVisibility(transcribing ? View.INVISIBLE : VISIBLE);
        mealVoiceIcon.setImageResource(recording
                ? R.drawable.intake_stop : R.drawable.intake_mic);
        int tint = ContextCompat.getColor(activity,
                recording ? R.color.modern_secondary_text_primary
                        : transcribing ? R.color.modern_secondary_warning
                        : R.color.modern_secondary_on_accent);
        mealVoiceIcon.setColorFilter(tint);
        Drawable spinner = mealVoiceProgress.getIndeterminateDrawable();
        if (spinner != null) {
            DrawableCompat.setTint(spinner.mutate(), tint);
        }
        if (intakeVoiceHint != null) {
            intakeVoiceHint.setText(recording
                    ? R.string.intake_chat_recording
                    : transcribing ? R.string.intake_chat_processing
                    : recordPermissionRequestInFlight
                    ? R.string.intake_chat_microphone_permission
                    : busy && mealSending
                    && retryTurnControlKind == CONTROL_REVISION
                    ? R.string.intake_chat_correction_preparing
                    : !retryClientTurnId.isEmpty()
                    ? R.string.intake_chat_retry_hint
                    : voiceStatusMessage != 0
                    ? voiceStatusMessage
                    : correctionMode
                    ? R.string.intake_chat_correction_voice_hint
                    : R.string.intake_chat_voice_hint);
            intakeVoiceHint.setTextColor(ContextCompat.getColor(activity,
                    recording ? R.color.modern_secondary_danger
                            : transcribing || recordPermissionRequestInFlight
                            ? R.color.modern_secondary_warning
                            : voiceStatusMessage != 0
                            ? R.color.modern_secondary_danger
                            : R.color.modern_secondary_text_secondary));
        }
    }

    private void updateSendButton() {
        if (mealSend == null || mealSendIcon == null
                || mealSendProgress == null) return;
        boolean sending = busy && mealSending;
        mealSendIcon.setVisibility(sending ? View.INVISIBLE : VISIBLE);
        mealSendProgress.setVisibility(sending ? VISIBLE : GONE);
        mealSend.setContentDescription(activity.getString(sending
                ? R.string.meal_chat_sending : R.string.meal_chat_send));
    }

    private void pickTime(boolean meal) {
        if (busy) return;
        hideKeyboard();
        if (root != null) root.clearFocus();
        final long current = mode == Mode.MANUAL_MEAL
                ? manualMealOccurredAtMs : meal ? mealOccurredAtMs
                : insulinDraft.occurredAtMs;
        View content = LayoutInflater.from(activity).inflate(
                R.layout.modern_intake_time_chooser, null, false);
        TextView selection = content.findViewById(
                R.id.intake_time_chooser_selection);
        selection.setText(formatOccurredAt(current));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(content)
                .create();
        bindQuickTime(content.findViewById(R.id.intake_time_now), dialog,
                meal, 0);
        bindQuickTime(content.findViewById(R.id.intake_time_5m), dialog,
                meal, 5);
        bindQuickTime(content.findViewById(R.id.intake_time_10m), dialog,
                meal, 10);
        bindQuickTime(content.findViewById(R.id.intake_time_20m), dialog,
                meal, 20);
        bindQuickTime(content.findViewById(R.id.intake_time_30m), dialog,
                meal, 30);
        bindQuickTime(content.findViewById(R.id.intake_time_40m), dialog,
                meal, 40);
        bindQuickTime(content.findViewById(R.id.intake_time_50m), dialog,
                meal, 50);
        bindQuickTime(content.findViewById(R.id.intake_time_60m), dialog,
                meal, 60);
        content.findViewById(R.id.intake_time_exact).setOnClickListener(view -> {
            dialog.dismiss();
            pickExactDateAndTime(meal, current);
        });
        content.findViewById(R.id.intake_time_cancel)
                .setOnClickListener(view -> dialog.dismiss());
        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(
                        Color.TRANSPARENT));
                window.setLayout(MATCH_PARENT, WRAP_CONTENT);
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        } catch (RuntimeException error) {
            toast(R.string.intake_time_picker_error);
        }
    }

    private void bindQuickTime(View button, AlertDialog dialog, boolean meal,
            int minutesAgo) {
        button.setOnClickListener(view -> {
            long value = System.currentTimeMillis()
                    - minutesAgo * 60_000L;
            applySelectedTime(meal, value);
            dialog.dismiss();
        });
    }

    private void pickExactDateAndTime(boolean meal, long current) {
        Calendar initial = Calendar.getInstance();
        initial.setTimeInMillis(Math.min(current, System.currentTimeMillis()));
        DatePickerDialog dateDialog = new DatePickerDialog(activity,
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.setTimeInMillis(initial.getTimeInMillis());
                    selected.set(Calendar.YEAR, year);
                    selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, day);
                    showExactTimePicker(meal, selected);
                }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH));
        dateDialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        try {
            dateDialog.show();
        } catch (RuntimeException error) {
            toast(R.string.intake_time_picker_error);
        }
    }

    private void showExactTimePicker(boolean meal, Calendar selected) {
        TimePickerDialog timeDialog = new TimePickerDialog(activity,
                (picker, hour, minute) -> {
                    selected.set(Calendar.HOUR_OF_DAY, hour);
                    selected.set(Calendar.MINUTE, minute);
                    selected.set(Calendar.SECOND, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    applySelectedTime(meal, selected.getTimeInMillis());
                }, selected.get(Calendar.HOUR_OF_DAY),
                selected.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(activity));
        try {
            timeDialog.show();
        } catch (RuntimeException error) {
            toast(R.string.intake_time_picker_error);
        }
    }

    private void applySelectedTime(boolean meal, long selectedAtMs) {
        long value = Math.min(selectedAtMs, System.currentTimeMillis());
        if (mode == Mode.MANUAL_MEAL) {
            manualMealOccurredAtMs = value;
            updateManualMealTime();
        } else if (meal) {
            applySelectedMealTime(value);
        } else {
            insulinDraft.occurredAtMs = value;
            updateInsulinTime();
        }
    }

    private void applySelectedMealTime(long value) {
        mealOccurredAtMs = value;
        pendingMealOccurredAtMs = value;
        mealTimeExplicitForNextTurn = true;
        mealTimeSyncUnknown = false;
        updateMealTime();
        updateProposal();
    }

    private void mealTimeAction() {
        if (busy) return;
        if (mealTimeSyncUnknown) {
            beginMealTimeResolution(pendingMealOccurredAtMs);
        } else {
            pickTime(true);
        }
    }

    private void beginMealTimeResolution(long value) {
        mealOccurredAtMs = value;
        pendingMealOccurredAtMs = value;
        mealTimeUpdating = true;
        mealTimeSyncUnknown = false;
        setMealBusy(true);
        updateMealTime();
        updateProposal();
        repository.resolveMealChatTime(mealSessionId, value,
                new IntakeRepository.Callback<MealChatSession>() {
            @Override public void onSuccess(MealChatSession session) {
                if (closed) return;
                acceptResolvedMealTime(session, value);
            }

            @Override public void onError(String message) {
                if (closed) return;
                mealTimeUpdating = false;
                mealTimeSyncUnknown = true;
                confirmAfterTimeUpdate = false;
                setMealBusy(false);
                updateMealTime();
                updateProposal();
                toast(R.string.meal_chat_time_sync_error);
            }
        });
    }

    private void acceptResolvedMealTime(MealChatSession session,
            long requestedAtMs) {
        if (session == null || session.occurredAtMs <= 0L) {
            mealTimeUpdating = false;
            mealTimeSyncUnknown = true;
            confirmAfterTimeUpdate = false;
            setMealBusy(false);
            updateMealTime();
            updateProposal();
            toast(R.string.meal_chat_time_sync_error);
            return;
        }

        mealOccurredAtMs = session.occurredAtMs;
        pendingMealOccurredAtMs = mealOccurredAtMs;
        mealTimeUpdating = false;
        mealTimeSyncUnknown = false;
        boolean active = "active".equalsIgnoreCase(session.status);
        if (!active) {
            mealReadyToConfirm = false;
            confirmAfterTimeUpdate = false;
        }
        setMealBusy(false);
        updateMealTime();
        updateProposal();
        if (!active) {
            activity.requestRender();
            toast(R.string.meal_chat_session_already_confirmed);
            return;
        }

        boolean requestedAccepted = mealOccurredAtMs == requestedAtMs;
        if (!requestedAccepted) {
            toast(R.string.meal_chat_time_reconciled);
        }
        boolean shouldConfirm = confirmAfterTimeUpdate && requestedAccepted;
        confirmAfterTimeUpdate = false;
        if (shouldConfirm) confirmMeal();
    }

    private String formatOccurredAt(long occurredAtMs) {
        long ageMs = Math.max(0L,
                System.currentTimeMillis() - occurredAtMs);
        long ageMinutes = ageMs / 60_000L;
        String relative = ageMinutes < 1L
                ? activity.getString(R.string.intake_time_now)
                : ageMinutes <= 90L
                ? activity.getString(R.string.intake_time_minutes_ago,
                        ageMinutes)
                : "";
        String absolute = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT).format(new Date(occurredAtMs));
        return relative.isEmpty() ? absolute
                : activity.getString(R.string.intake_time_relative_absolute,
                        relative, absolute);
    }

    private void updateInsulinTime() {
        if (insulinTime == null) return;
        insulinTime.setText(activity.getString(R.string.intake_time_selected,
                formatOccurredAt(insulinDraft.occurredAtMs)));
    }

    private void updateManualMealTime() {
        if (manualMealTime == null) return;
        manualMealTime.setText(activity.getString(R.string.intake_time_selected,
                formatOccurredAt(manualMealOccurredAtMs)));
    }

    private void updateMealTime() {
        if (mealTime == null) return;
        mealTime.setText(activity.getString(mealTimeSyncUnknown
                        ? R.string.intake_time_selected_retry
                        : mealTimeUpdating
                        ? R.string.intake_time_selected_updating
                        : R.string.intake_time_selected,
                formatOccurredAt(mealOccurredAtMs)));
        boolean enabled = !busy && retryClientTurnId.isEmpty();
        mealTime.setEnabled(enabled);
        mealTime.setAlpha(enabled ? 1.0f : 0.68f);
    }

    private String formatProposalTime(long occurredAtMs) {
        long ageMinutes = Math.max(0L,
                System.currentTimeMillis() - occurredAtMs) / 60_000L;
        String clock = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(occurredAtMs));
        if (ageMinutes <= 90L) {
            String relative = ageMinutes < 1L
                    ? activity.getString(R.string.intake_time_now)
                    : activity.getString(R.string.intake_time_minutes_ago,
                            ageMinutes);
            return activity.getString(R.string.intake_time_relative_absolute,
                    relative, clock);
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT).format(new Date(occurredAtMs));
    }

    private void bindBackendStatus() {
        backendStatus = root.findViewById(R.id.intake_backend_status);
        backendStatus.setOnClickListener(view -> {
            if (!retryClientTurnId.isEmpty()) {
                toast(R.string.intake_backend_change_pending);
                return;
            }
            IntakeBackendSettings.show(activity, this::checkBackend);
        });
        updateBackendSettingsAvailability();
        checkBackend();
    }

    private void updateBackendSettingsAvailability() {
        if (backendStatus == null) return;
        boolean enabled = !busy && retryClientTurnId.isEmpty();
        backendStatus.setEnabled(enabled);
        backendStatus.setAlpha(enabled ? 1.0f : 0.68f);
    }

    private boolean pendingTurnMatchesBackend() {
        return retryClientTurnId.isEmpty()
                || !retryBackendFingerprint.isEmpty()
                && retryBackendFingerprint.equals(backendFingerprint());
    }

    private void onBackendConfigurationChanged() {
        if (closed) return;
        // IntakeRepository.configure() vetoes this transition globally, even
        // when settings were opened outside this composer. Keep this defensive
        // guard so a future configuration source can never erase the exact
        // pending identity or its media through the listener path.
        if (!retryClientTurnId.isEmpty()) {
            showInlineError(R.string.intake_backend_change_pending,
                    INLINE_ERROR_RESOLVE_TURN);
            if (mealSend != null) mealSend.setEnabled(false);
            updateBackendSettingsAvailability();
            updateReconcileAction();
            return;
        }
        intakeSessionGeneration++;
        intakeClientSessionId = UUID.randomUUID().toString();
        intakeSessionId = "";
        intakeSessionStarting = false;
        afterIntakeSessionReady = null;
        sessionFailureShown = false;
        lastActionId = "";
        lastIntakeTurn = null;
        lastActionTurn = null;
        lastActionDeleted = false;
        actionCardHistory.clear();
        correctionMode = false;
        correctionSummary = "";
        ArrayList<File> previousRetryPhotos = new ArrayList<>(
                retryTurnPhotos);
        clearPendingTurnRetry(true);
        for (File photo : previousRetryPhotos) {
            pendingPhotos.remove(photo);
            deleteTemporary(photo);
        }
        deleteTemporary(deferredIntakeAudio);
        deferredIntakeAudio = null;
        clearInlineError();
        chatLines.clear();
        chatLines.add(new ChatLine(false,
                activity.getString(R.string.meal_chat_intro), 0, false));
        IntakeChatStateStore.clear(activity);
        renderedChatLineCount = 0;
        renderChat();
        updateProposal();
        updateAttachments();
        checkBackend();
        ensureIntakeSession(null);
    }

    private void checkBackend() {
        if (closed || backendStatus == null) return;
        TextView target = backendStatus;
        target.setText(R.string.intake_backend_checking);
        target.setTextColor(ContextCompat.getColor(activity,
                R.color.modern_secondary_text_secondary));
        repository.health(new IntakeRepository.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject value) {
                if (closed || target != backendStatus) return;
                boolean ready = "ok".equalsIgnoreCase(
                        value.optString("database", ""))
                        && value.optBoolean("auth_configured", false);
                boolean aiReady = value.optBoolean("ai_configured", false);
                target.setText(!ready ? R.string.intake_backend_offline
                        : aiReady ? R.string.intake_backend_online
                        : R.string.intake_backend_ai_missing);
                target.setTextColor(ContextCompat.getColor(activity,
                        ready && (mode != Mode.MEAL || aiReady)
                                ? R.color.modern_secondary_accent
                                : R.color.modern_secondary_warning));
            }

            @Override public void onError(String message) {
                if (closed || target != backendStatus) return;
                target.setText(R.string.intake_backend_offline);
                target.setTextColor(ContextCompat.getColor(activity,
                        R.color.modern_secondary_warning));
            }
        });
    }

    private void replaceRoot(int layoutId) {
        View previous = root;
        if (previous != null) {
            ViewCompat.setOnApplyWindowInsetsListener(previous, null);
            ViewParent parent = previous.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(previous);
            }
        }
        root = LayoutInflater.from(activity).inflate(layoutId, null, false);
        renderedChatLineCount = 0;
        View page = root.findViewById(R.id.intake_page);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean keyboardVisible = insets.isVisible(
                    WindowInsetsCompat.Type.ime());
            int readableGutter = ClinicalUi.readableHorizontalGutter(activity,
                    Math.max(0, view.getWidth() - bars.left - bars.right), 0);
            page.setPadding(bars.left + readableGutter, bars.top,
                    bars.right + readableGutter,
                    Math.max(bars.bottom, ime.bottom));
            View safety = view.findViewById(R.id.meal_chat_safety);
            if (safety != null) {
                safety.setVisibility(keyboardVisible ? GONE : VISIBLE);
            }
            return insets;
        });
        ClinicalUi.reapplyInsetsOnWidthChanges(root);
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
    }

    private void handleSystemBack() {
        if (closed) return;
        if (busy) {
            if (mode == Mode.MEAL) {
                requestClose(false);
                return;
            }
            MainActivity.setonback(this::handleSystemBack);
            toast(R.string.intake_wait_for_save);
            return;
        }
        if (recording) stopRecording(false);
        hideKeyboard();
        requestClose(false);
    }

    private void childBack() {
        if (busy) {
            if (mode == Mode.MEAL) {
                requestClose(true);
                return;
            }
            toast(R.string.intake_wait_for_save);
            return;
        }
        if (recording) stopRecording(false);
        hideKeyboard();
        requestClose(true);
    }

    void onActivityPause() {
        if (!closed && recording) stopRecording(false);
    }

    void onConfigurationChanged() {
        if (closed || root == null) return;
        if (mode == Mode.MEAL && !busy) {
            String draft = mealInput == null ? ""
                    : mealInput.getText().toString();
            showMeal();
            if (mealInput != null) {
                mealInput.setText(draft);
                mealInput.setSelection(mealInput.length());
            }
        } else {
            ViewCompat.requestApplyInsets(root);
        }
    }

    void destroy() {
        destroying = true;
        close(false);
    }

    private void requestClose(boolean popBack) {
        if (busy && intakeTurnCall != null) {
            // Detach the surface but let the authoritative request finish. If
            // it returns a receipt, the repository still merges it into the
            // graph; cancelling the socket here could hide a server commit.
            intakeTurnCall = null;
            busy = false;
            mealSending = false;
            transcribing = false;
        } else if (busy && afterIntakeSessionReady != null) {
            afterIntakeSessionReady = null;
            File unsentAudio = intakeTurnAudio;
            intakeTurnAudio = null;
            clearPendingTurnRetry(false);
            deleteTemporary(unsentAudio);
            busy = false;
            mealSending = false;
            transcribing = false;
        } else if (busy && mode == Mode.MEAL) {
            // Undo and other repository-owned operations are safe to finish in
            // the background; their repository merge precedes the UI callback.
            busy = false;
            mealSending = false;
            mealConfirming = false;
            transcribing = false;
        }
        close(popBack);
    }

    private void close(boolean popBack) {
        if (closed) return;
        if (busy && !destroying) {
            toast(R.string.intake_wait_for_save);
            return;
        }
        persistChatState();
        closed = true;
        repository.removeConfigurationListener(backendConfigurationListener);
        transcriptionGeneration++;
        transcribing = false;
        if (transcriptionCall != null) {
            transcriptionCall.cancel();
            transcriptionCall = null;
        }
        if (intakeTurnCall != null) {
            // Keep the backend-authoritative turn alive across Activity/UI
            // teardown so a successful receipt can still update the graph.
            intakeTurnCall = null;
        }
        File activeIntakeAudio = intakeTurnAudio;
        intakeTurnAudio = null;
        // The cancelled transport may still be unwinding its FileInputStream.
        // Leave this fresh file for the stale-media reaper instead of unlinking
        // it under the worker.
        if (activeIntakeAudio != null) {
            temporaryFiles.remove(activeIntakeAudio);
        }
        for (File activePhoto : activeIntakePhotos) {
            temporaryFiles.remove(activePhoto);
        }
        activeIntakePhotos.clear();
        File activeTranscription = transcriptionFile;
        transcriptionFile = null;
        // The repository worker owns an in-flight recording until its finally
        // block. Do not unlink it underneath FileInputStream on close.
        if (activeTranscription != null) {
            temporaryFiles.remove(activeTranscription);
        }
        if (recording || recorder != null) stopRecording(false);
        deleteTemporary(deferredIntakeAudio);
        deferredIntakeAudio = null;
        hideKeyboard();
        if (popBack) MainActivity.poponback();
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, null);
            ViewParent parent = root.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(root);
            }
        }
        mediaExecutor.shutdownNow();
        if (retryTurnAudio != null) temporaryFiles.remove(retryTurnAudio);
        for (File retryPhoto : retryTurnPhotos) {
            temporaryFiles.remove(retryPhoto);
        }
        ArrayList<File> files;
        synchronized (temporaryFiles) {
            files = new ArrayList<>(temporaryFiles);
        }
        for (File file : files) deleteTemporary(file);
        pendingPhotos.clear();
        deferredTranscript = "";
        activity.getWindow().setSoftInputMode(previousSoftInputMode);
        activity.onIntakeComposerClosed(this);
        activity.lightBars(false);
    }

    private void scrollChatToBottom() {
        if (mealScroll != null) {
            mealScroll.post(() -> mealScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager)
                activity.getSystemService(MainActivity.INPUT_METHOD_SERVICE);
        if (keyboard != null && root != null) {
            keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    /**
     * A newly opened composer is always a new conversation. The only state
     * allowed to cross that boundary is an exact, unresolved transport turn:
     * dropping its id or payload could duplicate a medical record. Ordinary
     * chat rows, action cards and completed backend sessions are deliberately
     * not restored.
     */
    private void restorePendingTurnState() {
        IntakeChatStateStore.State state = IntakeChatStateStore.load(
                activity, backendFingerprint());
        if (state == null) return;
        IntakeChatStateStore.Pending pending = state.pending;
        if (pending == null) {
            IntakeChatStateStore.clear(activity);
            return;
        }
        intakeClientSessionId = state.clientSessionId;
        intakeSessionId = state.sessionId;
        if (pending.controlKind != CONTROL_NONE) {
            // A pending delete/correction needs its frozen target only so the
            // exact same control turn can be reconciled safely.
            lastActionId = state.lastActionId;
            lastActionTurn = state.lastActionTurn;
            lastActionDeleted = state.lastActionDeleted;
            actionCardHistory.accept(lastActionTurn);
        }
        retryClientTurnId = pending.clientTurnId;
        retryBackendFingerprint = backendFingerprint();
        retryTurnOccurredAtMs = pending.occurredAtMs;
        retryTurnText = pending.text;
        retryTurnAudio = pending.audio;
        retryTurnPhotos.addAll(pending.photos);
        retryTurnDefinitiveFailure = pending.definitiveFailure;
        retryTurnCommitMayHaveOccurred = pending.commitMayHaveOccurred;
        retryTurnControlKind = pending.controlKind;
        if (pending.controlKind == CONTROL_REVISION) {
            correctionMode = true;
            correctionSummary = primaryActionSummary(lastActionTurn);
        }
        pendingPhotos.addAll(pending.photos);
        if (pending.controlKind == CONTROL_NONE) {
            mealOccurredAtMs = pending.occurredAtMs;
            pendingMealOccurredAtMs = pending.occurredAtMs;
        }
        if (pending.audio != null) temporaryFiles.add(pending.audio);
        temporaryFiles.addAll(pending.photos);
    }

    private boolean persistChatState() {
        if (retryClientTurnId.isEmpty()) {
            // Completed conversations are intentionally ephemeral. Saved
            // events live in the repository and are not touched here.
            IntakeChatStateStore.clear(activity);
            return true;
        }
        IntakeChatStateStore.Pending pending = new IntakeChatStateStore.Pending(
                retryClientTurnId, retryTurnOccurredAtMs, retryTurnText,
                retryTurnAudio, retryTurnPhotos, retryTurnDefinitiveFailure,
                retryTurnCommitMayHaveOccurred, retryTurnControlKind);
        String fingerprint = retryBackendFingerprint.isEmpty()
                ? backendFingerprint() : retryBackendFingerprint;
        return IntakeChatStateStore.save(activity, fingerprint,
                new IntakeChatStateStore.State(intakeClientSessionId,
                        intakeSessionId, lastActionId,
                        lastActionTurn, lastActionDeleted,
                        Collections.emptyList(), pending));
    }

    private String backendFingerprint() {
        String identity = repository.backendUrl() + '\u0000'
                + repository.backendToken();
        return UUID.nameUUIDFromBytes(identity.getBytes(
                StandardCharsets.UTF_8)).toString();
    }

    private File mediaDirectory() throws IOException {
        File directory = new File(activity.getCacheDir(), "intake-media");
        if ((!directory.isDirectory() && !directory.mkdirs())
                || !directory.isDirectory()) {
            throw new IOException("Could not create intake media cache");
        }
        return directory;
    }

    private void cleanStaleMedia() {
        File directory = new File(activity.getCacheDir(), "intake-media");
        File[] stale = directory.listFiles();
        if (stale == null) return;
        long cutoff = System.currentTimeMillis() - STALE_MEDIA_AGE_MS;
        for (File file : stale) {
            try {
                // A transcription cancelled by a previous composer can still
                // be unwinding on its dedicated worker. Only reap genuinely
                // old leftovers; that worker owns all fresh audio cleanup.
                if (file.isFile() && !temporaryFiles.contains(file)
                        && file.lastModified() > 0L
                        && file.lastModified() <= cutoff) {
                    file.delete();
                }
            } catch (SecurityException ignored) {}
        }
    }

    private void deleteTemporary(File file) {
        if (file == null) return;
        temporaryFiles.remove(file);
        try {
            if (file.isFile() && !file.delete()) file.deleteOnExit();
        } catch (SecurityException ignored) {}
    }

    private static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (width / sample > target || height / sample > target) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleDown(Bitmap source, int maxEdge) {
        int edge = Math.max(source.getWidth(), source.getHeight());
        if (edge <= maxEdge) return source;
        float factor = maxEdge / (float) edge;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * factor)),
                Math.max(1, Math.round(source.getHeight() * factor)), true);
    }

    private static Bitmap orientBitmap(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1.0f, 1.0f); break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180.0f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180.0f); matrix.postScale(-1.0f, 1.0f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90.0f); matrix.postScale(-1.0f, 1.0f); break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90.0f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90.0f); matrix.postScale(-1.0f, 1.0f); break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90.0f); break;
            default:
                return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                source.getHeight(), matrix, true);
    }

    private static float parseNumber(String raw) {
        String value = IntakeEvent.clean(raw).replace(',', '.');
        if (value.isEmpty()) throw new NumberFormatException();
        float parsed = Float.parseFloat(value);
        if (!Float.isFinite(parsed)) throw new NumberFormatException();
        return parsed;
    }

    private static String formatNumber(float value) {
        if (Math.abs(value - Math.round(value)) < 0.01f) {
            return String.format(Locale.getDefault(), "%d", Math.round(value));
        }
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private static String join(List<String> values, String separator,
            String prefix) {
        StringBuilder result = new StringBuilder(prefix);
        for (String value : values) {
            if (result.length() > prefix.length()) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources()
                .getDisplayMetrics().density);
    }

    private void toast(int stringId) {
        toast(activity.getString(stringId));
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
