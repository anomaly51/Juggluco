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
 * Entry point for the new backend-owned intake experience.
 *
 * <p>Insulin and meals deliberately use different screens and different
 * writes. Insulin is a fast structured form. A meal is a conversation whose
 * proposal is persisted only after an explicit confirmation.</p>
 */
final class IntakeComposer {
    private enum Mode { CHOOSER, INSULIN, MEAL }

    private static final int REQUEST_MEAL_CAMERA = 0x6F41;
    private static final int REQUEST_MEAL_GALLERY = 0x6F42;
    private static final int REQUEST_RECORD_AUDIO = 0x6F43;
    private static final int MAX_PHOTOS_PER_MESSAGE = 24;
    private static final int MAX_IMAGE_EDGE = 2400;
    private static final int PREVIEW_EDGE = 320;
    private static final int VOICE_MAX_DURATION_MS = 60_000;
    private static final long STALE_MEDIA_AGE_MS = 6L * 60L * 60L * 1000L;

    private static final class ChatLine {
        final boolean user;
        final String text;
        final int photoCount;

        ChatLine(boolean user, String text, int photoCount) {
            this.user = user;
            this.text = IntakeEvent.clean(text);
            this.photoCount = photoCount;
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
    private final IntakeDraft insulinDraft = new IntakeDraft();

    private View root;
    private TextView backendStatus;
    private Mode mode = Mode.CHOOSER;
    private boolean closed;
    private boolean busy;
    private boolean destroying;

    // Insulin screen state.
    private EditText insulinDose;
    private Spinner insulinProduct;
    private Button insulinSave;
    private TextView insulinTime;
    private int insulinProductIndex;

    // Meal conversation state.
    private final String mealClientEventId = UUID.randomUUID().toString();
    private long mealOccurredAtMs = System.currentTimeMillis();
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
    private HorizontalScrollView attachmentScroll;
    private LinearLayout attachmentList;
    private TextView mealTime;
    private EditText mealInput;
    private View mealAttach;
    private View mealVoice;
    private ImageView mealVoiceIcon;
    private ProgressBar mealVoiceProgress;
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
    private Button mealConfirm;
    private int pendingPhotoImports;

    // Camera and voice state.
    private File pendingCameraFile;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;
    private boolean recorderReachedLimit;
    private boolean transcribing;
    private int transcriptionGeneration;
    private String deferredTranscript = "";
    private File transcriptionFile;
    private IntakeRepository.Cancellable transcriptionCall;

    IntakeComposer(MainActivity activity) {
        this.activity = activity;
        repository = IntakeRepository.get(activity);
        previousSoftInputMode = activity.getWindow().getAttributes()
                .softInputMode;
        chatLines.add(new ChatLine(false,
                activity.getString(R.string.meal_chat_intro), 0));
        cleanStaleMedia();
    }

    boolean isShowing() {
        return !closed && root != null && root.getParent() != null;
    }

    void show() {
        activity.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showChooserInternal();
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
        if (backendStatus != null) backendStatus.setEnabled(!value);
    }

    private void showMeal() {
        if (busy || closed) return;
        mode = Mode.MEAL;
        replaceRoot(R.layout.modern_meal_chat);
        root.findViewById(R.id.intake_back_button)
                .setOnClickListener(view -> childBack());
        mealScroll = root.findViewById(R.id.meal_chat_scroll);
        mealMessages = root.findViewById(R.id.meal_chat_messages);
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
        mealConfirm = root.findViewById(R.id.meal_chat_confirm);
        mealTime.setOnClickListener(view -> mealTimeAction());
        proposalTime.setOnClickListener(view -> mealTimeAction());
        mealAttach.setOnClickListener(view -> choosePhotoAction());
        mealVoice.setOnClickListener(view -> voiceAction());
        mealSend.setOnClickListener(view -> sendMealMessage());
        mealConfirm.setOnClickListener(view -> confirmMeal());
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
        applyDeferredTranscript();
        scrollChatToBottom();
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
        String text = IntakeEvent.clean(mealInput.getText().toString());
        if (text.isEmpty() && pendingPhotos.isEmpty()) {
            toast(R.string.meal_chat_empty_message);
            return;
        }
        ArrayList<File> photos = new ArrayList<>(pendingPhotos);
        mealConfirming = false;
        mealSending = true;
        clearProposalForRevision();
        setMealBusy(true);
        if (mealSessionId.isEmpty()) {
            repository.startMealChat(mealClientEventId, mealOccurredAtMs,
                    new IntakeRepository.Callback<MealChatSession>() {
                @Override public void onSuccess(MealChatSession session) {
                    if (closed) return;
                    if (session.id.isEmpty()) {
                        mealSending = false;
                        setMealBusy(false);
                        toast(R.string.meal_chat_invalid_session);
                        return;
                    }
                    mealSessionId = session.id;
                    if (session.occurredAtMs > 0L) {
                        mealOccurredAtMs = session.occurredAtMs;
                        pendingMealOccurredAtMs = mealOccurredAtMs;
                    }
                    updateMealTime();
                    sendMealTurn(text, photos);
                }

                @Override public void onError(String message) {
                    if (closed) return;
                    mealSending = false;
                    setMealBusy(false);
                    toast(activity.getString(
                            R.string.meal_chat_start_error, message));
                }
            });
        } else {
            sendMealTurn(text, photos);
        }
    }

    private void sendMealTurn(String text, ArrayList<File> photos) {
        repository.sendMealChat(mealSessionId, text, photos,
                new IntakeRepository.Callback<MealChatSession.Turn>() {
            @Override public void onSuccess(MealChatSession.Turn turn) {
                if (closed) return;
                chatLines.add(new ChatLine(true, text, photos.size()));
                chatLines.add(new ChatLine(false,
                        turn.assistantMessage.text, 0));
                mealProposal = turn.proposal;
                mealReadyToConfirm = turn.readyToConfirm;
                clearSentMedia(photos);
                if (mealInput != null) mealInput.setText("");
                mealSending = false;
                setMealBusy(false);
                if (mode == Mode.MEAL) {
                    renderChat();
                    updateAttachments();
                    updateProposal();
                    scrollChatToBottom();
                }
            }

            @Override public void onError(String message) {
                if (closed) return;
                mealSending = false;
                setMealBusy(false);
                String error = activity.getString(R.string.meal_chat_error,
                        message);
                toast(error);
                if (mode == Mode.MEAL) {
                    chatLines.add(new ChatLine(false, error, 0));
                    renderChat();
                    scrollChatToBottom();
                }
            }
        });
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

    private void confirmMeal() {
        if (mealTimeUpdating) {
            confirmAfterTimeUpdate = true;
            return;
        }
        if (mealTimeSyncUnknown) {
            toast(R.string.meal_chat_time_sync_required);
            return;
        }
        if (busy || mealSessionId.isEmpty() || !mealReadyToConfirm
                || mealProposal == null) return;
        mealSending = false;
        mealConfirming = true;
        setMealBusy(true);
        repository.confirmMealChat(mealSessionId,
                new IntakeRepository.Callback<IntakeEvent>() {
            @Override public void onSuccess(IntakeEvent value) {
                if (closed) return;
                toast(R.string.meal_chat_confirmed);
                activity.requestRender();
                mealConfirming = false;
                setMealBusy(false);
                close(true);
            }

            @Override public void onError(String message) {
                if (closed) return;
                mealConfirming = false;
                setMealBusy(false);
                toast(activity.getString(R.string.meal_chat_error, message));
            }
        });
    }

    private void renderChat() {
        if (mealMessages == null) return;
        mealMessages.removeAllViews();
        for (ChatLine line : chatLines) addChatBubble(line);
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
        bubble.setMaxWidth((int) (activity.getResources()
                .getDisplayMetrics().widthPixels * 0.84f));
        bubble.setBackgroundResource(line.user
                ? R.drawable.intake_chat_user
                : R.drawable.intake_chat_assistant);
        row.addView(bubble, new LinearLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT));
        mealMessages.addView(row);
    }

    private void updateProposal() {
        if (proposalCard == null) return;
        if (mealProposal == null) {
            proposalCard.setVisibility(GONE);
            return;
        }
        proposalCard.setVisibility(VISIBLE);
        proposalTitle.setText(mealReadyToConfirm
                ? R.string.meal_chat_proposal_ready_title
                : R.string.meal_chat_proposal_title);
        String name = mealProposal.mealName.isEmpty()
                ? mealProposal.mealDescription : mealProposal.mealName;
        proposalMeal.setText(mealProposal.totalPortionGrams > 0.0f
                ? activity.getString(R.string.meal_chat_proposal_meal, name,
                        formatNumber(mealProposal.totalPortionGrams))
                : activity.getString(
                        R.string.meal_chat_proposal_meal_no_portion, name));
        proposalCarbs.setText(activity.getString(
                R.string.meal_chat_proposal_carbs,
                formatNumber(mealProposal.estimatedCarbsGrams),
                formatNumber(mealProposal.carbsLowGrams),
                formatNumber(mealProposal.carbsHighGrams)));
        proposalAbsorption.setText(CarbAbsorptionUi.details(activity,
                mealProposal.absorptionSpeed,
                mealProposal.absorptionPeakMinutes,
                mealProposal.absorptionDurationMinutes,
                mealProposal.absorptionConfidence));
        proposalConfidence.setText(activity.getString(
                R.string.meal_chat_proposal_confidence,
                Math.round(mealProposal.confidence * 100.0f)));
        proposalTime.setText(activity.getString(mealTimeSyncUnknown
                        ? R.string.meal_chat_proposal_time_retry
                        : mealTimeUpdating
                        ? R.string.meal_chat_proposal_time_updating
                        : R.string.meal_chat_proposal_time_action,
                formatProposalTime(mealOccurredAtMs)));
        proposalTime.setEnabled(!busy);
        proposalTime.setAlpha(busy ? 0.68f : 1.0f);
        if (mealProposal.warnings.isEmpty()) {
            proposalWarnings.setVisibility(GONE);
        } else {
            proposalWarnings.setVisibility(VISIBLE);
            proposalWarnings.setText(join(mealProposal.warnings, "\n• ",
                    "• "));
        }
        mealConfirm.setVisibility(mealReadyToConfirm ? VISIBLE : GONE);
        mealConfirm.setEnabled(mealReadyToConfirm && !busy
                && !mealTimeSyncUnknown);
        if (mealTimeSyncUnknown) {
            mealConfirm.setText(R.string.meal_chat_confirm_time_sync_required);
        } else {
            mealConfirm.setText(activity.getString(
                    R.string.meal_chat_confirm_at_button,
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(mealOccurredAtMs))));
        }
    }

    private void setMealBusy(boolean value) {
        busy = value;
        if (mealSend == null) return;
        mealSend.setEnabled(!value);
        mealInput.setEnabled(!value);
        mealAttach.setEnabled(!value);
        mealTime.setEnabled(!value);
        if (proposalTime != null) {
            proposalTime.setEnabled(!value);
            proposalTime.setAlpha(value ? 0.68f : 1.0f);
        }
        root.findViewById(R.id.intake_back_button).setEnabled(!value);
        if (backendStatus != null) backendStatus.setEnabled(!value);
        updateMealTime();
        updateSendButton();
        updateVoiceButton();
        if (mealConfirm != null) {
            mealConfirm.setEnabled(!value && mealReadyToConfirm
                    && !mealTimeSyncUnknown);
            if (value && mealConfirming) {
                mealConfirm.setText(R.string.meal_chat_confirming);
            } else if (mealProposal != null) {
                updateProposal();
            }
        }
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
                dp(28), dp(28), Gravity.TOP | Gravity.END);
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

    private void voiceAction() {
        if (busy || mode != Mode.MEAL) return;
        if (transcribing) {
            toast(R.string.meal_chat_voice_transcribing);
            return;
        }
        if (recording) {
            stopRecording(true);
            return;
        }
        requestOrStartRecording();
    }

    private void requestOrStartRecording() {
        if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
        } else {
            startRecording();
        }
    }

    boolean handlePermissionResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO) return false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && !closed && mode == Mode.MEAL) startRecording();
        else if (!granted) toast(R.string.intake_voice_permission);
        return true;
    }

    private void startRecording() {
        stopRecording(false);
        try {
            recordingFile = File.createTempFile("meal-voice-", ".m4a",
                    mediaDirectory());
            temporaryFiles.add(recordingFile);
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44_100);
            recorder.setAudioEncodingBitRate(64_000);
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
            toast(R.string.intake_voice_failed);
        }
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
            transcribeRecording(completed);
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
                toast(activity.getString(
                        R.string.meal_chat_voice_transcription_error,
                        message));
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
        mealVoice.setActivated(transcribing);
        mealVoice.setEnabled(!busy && !transcribing);
        mealVoice.setContentDescription(activity.getString(recording
                ? R.string.meal_chat_voice_stop
                : transcribing ? R.string.meal_chat_voice_transcribing
                : R.string.meal_chat_voice));
        mealVoiceProgress.setVisibility(showProgress ? VISIBLE : GONE);
        mealVoiceIcon.setVisibility(transcribing ? View.INVISIBLE : VISIBLE);
        mealVoiceIcon.setImageResource(recording
                ? R.drawable.intake_stop : R.drawable.intake_mic);
        int tint = ContextCompat.getColor(activity,
                recording ? R.color.modern_secondary_danger
                        : transcribing ? R.color.modern_secondary_accent
                        : R.color.modern_secondary_text_secondary);
        mealVoiceIcon.setColorFilter(tint);
        Drawable spinner = mealVoiceProgress.getIndeterminateDrawable();
        if (spinner != null) {
            DrawableCompat.setTint(spinner.mutate(), tint);
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
        final long current = meal ? mealOccurredAtMs
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
        if (meal) {
            applySelectedMealTime(value);
        } else {
            insulinDraft.occurredAtMs = value;
            updateInsulinTime();
        }
    }

    private void applySelectedMealTime(long value) {
        if (value == mealOccurredAtMs && !mealTimeSyncUnknown) {
            updateMealTime();
            updateProposal();
            return;
        }
        if (mealSessionId.isEmpty()) {
            mealOccurredAtMs = value;
            pendingMealOccurredAtMs = value;
            mealTimeSyncUnknown = false;
            updateMealTime();
            updateProposal();
            return;
        }

        beginMealTimeResolution(value);
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

    private void updateMealTime() {
        if (mealTime == null) return;
        mealTime.setText(activity.getString(mealTimeSyncUnknown
                        ? R.string.intake_time_selected_retry
                        : mealTimeUpdating
                        ? R.string.intake_time_selected_updating
                        : R.string.intake_time_selected,
                formatOccurredAt(mealOccurredAtMs)));
        mealTime.setEnabled(!busy);
        mealTime.setAlpha(busy ? 0.68f : 1.0f);
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
        backendStatus.setOnClickListener(view ->
                IntakeBackendSettings.show(activity, this::checkBackend));
        checkBackend();
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
        View page = root.findViewById(R.id.intake_page);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean keyboardVisible = insets.isVisible(
                    WindowInsetsCompat.Type.ime());
            page.setPadding(bars.left, bars.top, bars.right,
                    Math.max(bars.bottom, ime.bottom));
            View safety = view.findViewById(R.id.meal_chat_safety);
            if (safety != null) {
                safety.setVisibility(keyboardVisible ? GONE : VISIBLE);
            }
            return insets;
        });
        activity.addMyContentView(root,
                new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT), false);
        ViewCompat.requestApplyInsets(root);
        activity.lightBars(false);
    }

    private void handleSystemBack() {
        if (closed) return;
        if (busy) {
            MainActivity.setonback(this::handleSystemBack);
            toast(R.string.intake_wait_for_save);
            return;
        }
        if (mode == Mode.CHOOSER) {
            close(false);
        } else {
            if (recording) stopRecording(false);
            hideKeyboard();
            showChooserInternal();
            MainActivity.setonback(this::handleSystemBack);
        }
    }

    private void childBack() {
        if (busy) {
            toast(R.string.intake_wait_for_save);
            return;
        }
        if (recording) stopRecording(false);
        hideKeyboard();
        showChooserInternal();
    }

    void onActivityPause() {
        if (!closed && recording) stopRecording(false);
    }

    void onConfigurationChanged() {
        if (!closed && root != null) ViewCompat.requestApplyInsets(root);
    }

    void destroy() {
        destroying = true;
        close(false);
    }

    private void close(boolean popBack) {
        if (closed) return;
        if (busy && !destroying) {
            toast(R.string.intake_wait_for_save);
            return;
        }
        closed = true;
        transcriptionGeneration++;
        transcribing = false;
        if (transcriptionCall != null) {
            transcriptionCall.cancel();
            transcriptionCall = null;
        }
        File activeTranscription = transcriptionFile;
        transcriptionFile = null;
        // The repository worker owns an in-flight recording until its finally
        // block. Do not unlink it underneath FileInputStream on close.
        if (activeTranscription != null) {
            temporaryFiles.remove(activeTranscription);
        }
        if (recording || recorder != null) stopRecording(false);
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
                if (file.isFile() && file.lastModified() > 0L
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
