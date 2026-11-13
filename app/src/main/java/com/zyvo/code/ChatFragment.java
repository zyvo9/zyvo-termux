package com.zyvo.code;

import com.termux.R;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.sse.EventSource;

/** The product: a chat where every tool call really executes on the phone. */
public final class ChatFragment extends Fragment implements EngineManager.Listener {

    private Prefs prefs;
    private EngineManager engine;
    private ApiClient api;

    private RecyclerView list;
    private ChatAdapter adapter;
    private final List<ChatItem> items = new ArrayList<>();
    private final Map<String, ChatItem> byKey = new HashMap<>();
    private final Map<String, String> messageRoles = new HashMap<>();

    private EditText input;
    private ImageButton send;
    private View emptyView;
    private ProgressBar loadingView;
    private View banner;
    private TextView bannerText;
    private Button bannerAction;
    private TextView titleView;
    private View topDot;
    private TextView modelChip;
    private TextView composerModel;
    private TextView agentChip;
    private ImageButton scrollBottom;

    private EventSource sse;
    private EventSource sseStartedFrom;
    private String sessionId;
    private boolean busy;

    // only auto-scroll while the user is pinned to the bottom of the transcript
    private boolean pinnedToBottom = true;
    private long workingStartMs;
    private View workingBar;
    private View workingSpinner;
    private TextView workingBarText;
    private final Runnable workingTick = new Runnable() {
        @Override
        public void run() {
            if (workingBarText != null && workingStartMs > 0) {
                workingBarText.setText(getString(R.string.working_bar) + " "
                        + liveDuration(System.currentTimeMillis() - workingStartMs));
            }
            if (busy) ui.postDelayed(this, 1000);
        }
    };

    /** 7.6s while running, 1m 23s when it gets long. */
    private static String liveDuration(long ms) {
        if (ms < 60_000) return String.format(Locale.US, "%.1fs", Math.max(0.1, ms / 1000.0));
        long m = ms / 60_000;
        long s = (ms % 60_000) / 1000;
        return m + "m " + s + "s";
    }

    /** 8s / 1m 23s — rounded, for the finished line. */
    private static String shortDuration(long ms) {
        long s = Math.max(1, Math.round(ms / 1000.0));
        if (s < 60) return s + "s";
        long m = s / 60;
        long r = s % 60;
        return r == 0 ? m + "m" : m + "m " + r + "s";
    }

    /** Reveals streamed assistant text a few characters at a time (no jumps). */
    private final Runnable revealTick = new Runnable() {
        @Override
        public void run() {
            boolean active = false;
            for (int i = 0; i < items.size(); i++) {
                ChatItem it = items.get(i);
                if (it.kind != ChatItem.ASSISTANT) continue;
                if (it.fullText == null || it.shown >= it.fullText.length()) continue;
                int backlog = it.fullText.length() - it.shown;
                int step = Math.max(3, backlog / 8);
                int next = Math.min(it.fullText.length(), it.shown + step);
                while (next < it.fullText.length()
                        && !Character.isWhitespace(it.fullText.charAt(next))) {
                    next++;
                }
                it.shown = next;
                it.text = it.fullText.substring(0, next);
                adapter.notifyItemChanged(i);
                active = true;
            }
            if (active) {
                scrollIfPinned();
                ui.postDelayed(this, 32);
            }
        }
    };
    private boolean autoScrolling;
    private final Runnable autoScrollDone = new Runnable() {
        @Override
        public void run() {
            autoScrolling = false;
            updatePinned();
        }
    };
    // the engine streams the user's own prompt back - skip those echoes
    private final ArrayDeque<String> sentTexts = new ArrayDeque<>();

    private SessionAdapter sessionAdapter;


    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable busyWatchdog = () -> setBusy(false);

    // A turn that produces no event at all is almost always a provider quota
    // wall (the engine retries the lane silently). Say so instead of spinning.
    private long lastSignalMs;
    private boolean stallWarned;
    private String lastReportedError = "";
    private long lastReportedAt;
    private final Runnable stallTick = new Runnable() {
        @Override
        public void run() {
            if (!busy) return;
            if (hasRunningTool()) {
                // a long command (pkg install, build) is legitimately silent
                lastSignalMs = System.currentTimeMillis();
                ui.postDelayed(this, 5000L);
                return;
            }
            long idle = System.currentTimeMillis() - lastSignalMs;
            if (idle > 150_000L) {
                failTurn("No response from the model for " + (idle / 1000) + "s.");
                return;
            }
            if (idle > 40_000L && !stallWarned) {
                stallWarned = true;
                showBanner("Waiting for the model — no reply yet (" + (idle / 1000)
                        + "s). The free lane may be rate-limited. Tap Stop to cancel.", false);
            }
            ui.postDelayed(this, 5000L);
        }
    };
    private final Runnable reconnect = () -> {
        if (isAdded() && engine != null && engine.isReady()) connectStream();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        prefs = new Prefs(requireContext());
        engine = EngineManager.get(requireContext());
        api = new ApiClient(engine.workspaceDir.getAbsolutePath(), prefs);

        list = v.findViewById(R.id.list);
        input = v.findViewById(R.id.input);
        send = v.findViewById(R.id.btn_send);
        emptyView = v.findViewById(R.id.empty);
        loadingView = v.findViewById(R.id.loading);
        banner = v.findViewById(R.id.engine_banner);
        bannerText = v.findViewById(R.id.engine_text);
        bannerAction = v.findViewById(R.id.engine_action);
        titleView = v.findViewById(R.id.title);
        topDot = v.findViewById(R.id.top_engine_dot);
        modelChip = v.findViewById(R.id.model_chip);
        composerModel = v.findViewById(R.id.composer_model);
        workingBar = v.findViewById(R.id.working_bar);
        workingBarText = v.findViewById(R.id.working_text);
        workingSpinner = v.findViewById(R.id.working_spinner);

        View.OnClickListener pickModel = this::showModelPicker;
        if (modelChip != null) modelChip.setOnClickListener(pickModel);
        if (composerModel != null) composerModel.setOnClickListener(pickModel);
        refreshModelChips();
        loadVariants();

        View plus = v.findViewById(R.id.btn_plus);
        if (plus != null) plus.setOnClickListener(this::showPlusMenu);
        agentChip = v.findViewById(R.id.agent_chip);
        if (agentChip != null) agentChip.setOnClickListener(this::showAgentPicker);
        refreshAgentChip();

        scrollBottom = v.findViewById(R.id.btn_scroll_bottom);
        if (scrollBottom != null) {
            scrollBottom.setOnClickListener(view -> {
                pinnedToBottom = true;
                scrollToBottom();
            });
        }


        adapter = new ChatAdapter(requireContext(), items);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updatePinned();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    autoScrolling = false;
                    ui.removeCallbacks(autoScrollDone);
                }
            }
        });

        RecyclerView sessionList = getActivity() == null
                ? null : getActivity().findViewById(R.id.session_list);
        if (sessionList != null) {
            sessionAdapter = new SessionAdapter(requireContext(), new SessionAdapter.Listener() {
                @Override
                public void onSessionClick(SessionAdapter.Session session) {
                    openSession(session.id, session.title);
                    closeDrawer();
                }

                @Override
                public void onSessionLongClick(SessionAdapter.Session session) {
                    sessionActions(session);
                }
            });
            sessionList.setLayoutManager(new LinearLayoutManager(requireContext()));
            sessionList.setAdapter(sessionAdapter);
        }


        View.OnClickListener chipClick = view -> {
            String text = ((TextView) view).getText().toString();
            input.setText(text);
            input.setSelection(text.length());
            sendMessage();
        };
        v.findViewById(R.id.chip1).setOnClickListener(chipClick);
        v.findViewById(R.id.chip2).setOnClickListener(chipClick);
        v.findViewById(R.id.chip3).setOnClickListener(chipClick);

        send.setOnClickListener(view -> {
            if (busy) {
                setBusy(false);
                removeWorking();
                flushReveal();
                if (sessionId != null) {
                    final String sid = sessionId;
                    io.execute(() -> api.abort(sid));
                }
            } else {
                sendMessage();
            }
        });

        input.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });

        v.findViewById(R.id.btn_new).setOnClickListener(view -> newSession());
        v.findViewById(R.id.btn_sessions).setOnClickListener(view -> openSessionsDrawer());
        bannerAction.setOnClickListener(view -> {
            showBanner("Starting engine…", false);
            engine.restart();
        });

        String last = prefs.lastSessionId();
        if (!TextUtils.isEmpty(last)) sessionId = last;

        engine.addListener(this);
        updateEmpty();
    }

    @Override
    public void onDestroyView() {
        engine.removeListener(this);
        closeStream();
        ui.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshModelChips();
    }

    /** Dropdown model changer anchored to the chip (scrollable, check on current). */
    private void showModelPicker(View anchor) {
        final Context ctx = requireContext();
        final PopupWindow[] holder = new PopupWindow[1];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_popup_card);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int rowBg = tv.resourceId;

        final String current = prefs.modelId();
        for (final String[] m : Models.FREE) {
            boolean selected = m[0].equals(current);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(8), 0);
            row.setBackgroundResource(rowBg);

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(R.drawable.ic_spark);
            icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(ctx,
                    selected ? R.color.text_primary : R.color.text_disabled)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));

            TextView label = new TextView(ctx);
            label.setText(m[1]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(12));
            row.addView(label, lp);

            if (selected) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.text_primary)));
                row.addView(check, new LinearLayout.LayoutParams(dp(16), dp(16)));
            }

            row.setOnClickListener(v -> {
                prefs.setModel(Prefs.LANE_PROVIDER, m[0]);
                refreshModelChips();
                engine.restart();
                if (holder[0] != null) holder[0].dismiss();
                anchor.post(() -> showVariantPicker(anchor, m[0]));
            });
            rows.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(250), screenW - dp(32));
        int listH = Math.min(Models.FREE.length * dp(44) + dp(12), (int) (screenH * 0.6f));

        PopupWindow popup = new PopupWindow(card, width, listH, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        holder[0] = popup;

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0];
        if (x + width > screenW - dp(8)) x = screenW - width - dp(8);
        if (x < dp(8)) x = dp(8);
        int y = loc[1] - listH - dp(8);
        if (y < dp(8)) y = loc[1] + anchor.getHeight() + dp(8);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------- effort variants

    /** Per-model reasoning efforts from GET /config/providers (CLI variants). */
    private final Map<String, String[]> variantCache = new HashMap<>();
    private boolean variantsLoaded;

    private void loadVariants() {
        if (variantsLoaded) return;
        io.execute(() -> {
            try {
                JsonObject all = api.providers();
                JsonArray list = all.has("providers") && all.get("providers").isJsonArray()
                        ? all.getAsJsonArray("providers") : new JsonArray();
                Map<String, String[]> found = new HashMap<>();
                for (JsonElement pe : list) {
                    if (!pe.isJsonObject()) continue;
                    JsonObject p = pe.getAsJsonObject();
                    if (!"opencode".equals(str(p, "id"))) continue;
                    JsonObject models = p.has("models") && p.get("models").isJsonObject()
                            ? p.getAsJsonObject("models") : new JsonObject();
                    for (String mid : models.keySet()) {
                        JsonElement me = models.get(mid);
                        if (me == null || !me.isJsonObject()) continue;
                        JsonObject mo = me.getAsJsonObject();
                        if (!mo.has("variants") || !mo.get("variants").isJsonObject()) continue;
                        JsonObject vars = mo.getAsJsonObject("variants");
                        String[] names = vars.keySet().toArray(new String[0]);
                        if (names.length > 0) found.put(mid, names);
                    }
                }
                ui.post(() -> {
                    variantCache.clear();
                    variantCache.putAll(found);
                    variantsLoaded = true;
                });
            } catch (Exception ignored) {
                // keep the generic low/medium/high fallback
            }
        });
    }

    /** CLI dialog-variant: Default (provider decides) + each effort. */
    private void showVariantPicker(View anchor, String modelId) {
        final Context ctx = requireContext();
        final PopupWindow[] holder = new PopupWindow[1];

        String[] efforts = variantCache.get(modelId);
        if (efforts == null || efforts.length == 0) {
            if (variantsLoaded) return; // model exposes no variants
            efforts = new String[]{"low", "medium", "high"};
        }

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_popup_card);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int rowBg = tv.resourceId;

        final String current = prefs.variantOf(modelId);
        String[] labels = new String[efforts.length + 1];
        labels[0] = getString(R.string.effort_default);
        for (int i = 0; i < efforts.length; i++) {
            String e = efforts[i];
            labels[i + 1] = Character.toUpperCase(e.charAt(0)) + e.substring(1);
        }

        for (int i = 0; i < labels.length; i++) {
            final String value = i == 0 ? "" : efforts[i - 1];
            boolean selected = value.equals(current);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(8), 0);
            row.setBackgroundResource(rowBg);

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, lp);

            if (selected) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.text_primary)));
                row.addView(check, new LinearLayout.LayoutParams(dp(16), dp(16)));
            }

            row.setOnClickListener(v -> {
                prefs.setVariant(modelId, value);
                refreshModelChips();
                if (holder[0] != null) holder[0].dismiss();
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        }

        int width = dp(200);
        int height = dp(44) * labels.length + dp(12);
        PopupWindow popup = new PopupWindow(card, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        holder[0] = popup;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0];
        if (x + width > screenW - dp(8)) x = screenW - width - dp(8);
        if (x < dp(8)) x = dp(8);
        int y = loc[1] - height - dp(8);
        if (y < dp(8)) y = loc[1] + anchor.getHeight() + dp(8);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    /** Composer "+" menu: new chat, terminal, settings. */
    private void showPlusMenu(View anchor) {
        final Context ctx = requireContext();
        final PopupWindow[] holder = new PopupWindow[1];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_popup_card);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int rowBg = tv.resourceId;

        String[] labels = {getString(R.string.new_chat), getString(R.string.tab_terminal),
                getString(R.string.tab_settings)};
        int[] icons = {R.drawable.ic_add, R.drawable.ic_terminal, R.drawable.ic_settings};
        Runnable[] actions = {
                this::newSession,
                this::openTerminal,
                this::openSettings
        };

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setBackgroundResource(rowBg);

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.text_secondary)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(12));
            row.addView(label, lp);

            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                actions[index].run();
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        }

        int width = dp(200);
        int height = dp(44) * labels.length + dp(12);
        PopupWindow popup = new PopupWindow(card, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        holder[0] = popup;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0];
        if (x + width > screenW - dp(8)) x = screenW - width - dp(8);
        if (x < dp(8)) x = dp(8);
        int y = loc[1] - height - dp(8);
        if (y < dp(8)) y = loc[1] + anchor.getHeight() + dp(8);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private void refreshAgentChip() {
        if (agentChip == null) return;
        boolean plan = "plan".equals(prefs.agentMode());
        agentChip.setText(getString(plan ? R.string.agent_plan : R.string.agent_build));
    }

    /** Build = full tools; Plan = read-only planning agent (zyvo agent modes). */
    private void showAgentPicker(View anchor) {
        final Context ctx = requireContext();
        final PopupWindow[] holder = new PopupWindow[1];

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_popup_card);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));

        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int rowBg = tv.resourceId;

        String[] modes = {"build", "plan"};
        String[] labels = {getString(R.string.agent_build), getString(R.string.agent_plan)};
        String[] hints = {getString(R.string.agent_build_hint), getString(R.string.agent_plan_hint)};

        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setBackgroundResource(rowBg);

            TextView label = new TextView(ctx);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(ContextCompat.getColor(ctx,
                    mode.equals(prefs.agentMode()) ? R.color.text_primary : R.color.text_secondary));
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(label);

            TextView hint = new TextView(ctx);
            hint.setText(hints[i]);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            hint.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            row.addView(hint);

            row.setOnClickListener(v -> {
                prefs.setAgentMode(mode);
                refreshAgentChip();
                if (holder[0] != null) holder[0].dismiss();
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        }

        int width = dp(240);
        int height = dp(52) * modes.length + dp(12);
        PopupWindow popup = new PopupWindow(card, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        holder[0] = popup;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0];
        if (x + width > screenW - dp(8)) x = screenW - width - dp(8);
        if (x < dp(8)) x = dp(8);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, loc[1] - dp(132));
    }

    private void refreshModelChips() {
        String name = Models.nameOf(prefs.modelId());
        String variant = prefs.variantOf(prefs.modelId());
        if (!TextUtils.isEmpty(variant)) name = name + " · " + variant;
        if (modelChip != null) modelChip.setText(name);
        if (composerModel != null) composerModel.setText(name);
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ engine

    @Override
    public void onEngineState(EngineManager.State state, String detail) {
        ui.post(() -> {
            if (!isAdded() || getView() == null) return;
            tintDot(state);
            switch (state) {
                case READY:
                    banner.setVisibility(View.GONE);
                    connectStream();
                    if (!sseLoaded && sessionId != null) loadMessages(sessionId);
                    break;
                case STARTING:
                    showBanner("Starting engine — " + detail, false);
                    break;
                case STOPPED:
                    if (busy) reportError("Engine stopped while the turn was running.");
                    showBanner("Engine stopped", true);
                    closeStream();
                    break;
                case ERROR:
                    if (busy) reportError("Engine error: " + detail);
                    showBanner("Engine error: " + detail, true);
                    closeStream();
                    break;
            }
        });
    }

    private void tintDot(EngineManager.State state) {
        if (topDot == null) return;
        int color;
        switch (state) {
            case READY:
                color = R.color.ok;
                break;
            case STARTING:
                color = R.color.warn;
                break;
            case ERROR:
                color = R.color.danger;
                break;
            default:
                color = R.color.text_disabled;
                break;
        }
        topDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), color)));
    }

    private void showBanner(String text, boolean action) {
        banner.setVisibility(View.VISIBLE);
        bannerText.setText(text);
        bannerAction.setVisibility(action ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------------------- stream

    private boolean sseLoaded;

    private void connectStream() {
        if (sse != null) return;
        sse = api.subscribe(new ApiClient.EventListener() {
            @Override
            public void onJson(JsonObject event) {
                ui.post(() -> handleEvent(event));
            }

            @Override
            public void onClosed(String reason) {
                sse = null;
                ui.post(() -> {
                    if (isAdded() && engine.isReady()) ui.postDelayed(reconnect, 2500);
                });
            }
        });
    }

    private void closeStream() {
        if (sse != null) {
            try {
                sse.cancel();
            } catch (Throwable ignored) {
            }
            sse = null;
        }
    }

    private void handleEvent(JsonObject event) {
        try {
            String type = str(event, "type");
            JsonObject props = event.has("properties") && event.get("properties").isJsonObject()
                    ? event.getAsJsonObject("properties") : new JsonObject();

            if ("message.part.updated".equals(type)) {
                if (props.has("part") && props.get("part").isJsonObject()) {
                    handlePart(props.getAsJsonObject("part"));
                }
            } else if ("message.updated".equals(type)) {
                JsonObject info = props.has("info") && props.get("info").isJsonObject()
                        ? props.getAsJsonObject("info") : props;
                String mid = str(info, "id");
                String mrole = str(info, "role");
                if (!mid.isEmpty() && !mrole.isEmpty()) messageRoles.put(mid, mrole);
                if ("assistant".equals(mrole)) {
                    JsonObject time = info.has("time") && info.get("time").isJsonObject()
                            ? info.getAsJsonObject("time") : null;
                    if (time != null && time.has("completed")) {
                        // per-message completion fires after every agent step; the
                        // turn is only over on session.idle (keeps Working visible).
                        finishThoughts();
                    }
                }
                String evSid = str(info, "sessionID");
                if (sessionId == null || evSid.isEmpty() || sessionId.equals(evSid)) {
                    lastSignalMs = System.currentTimeMillis();
                    if (info.has("error") && !info.get("error").isJsonNull()) {
                        String err = errorText(info.get("error"));
                        if (!err.isEmpty()) reportError(err);
                    }
                }
            } else if ("session.error".equals(type)) {
                String sid = str(props, "sessionID");
                if (sessionId == null || sid.isEmpty() || sessionId.equals(sid)) {
                    String err = props.has("error") ? errorText(props.get("error")) : "";
                    if (err.isEmpty()) err = errorText(props);
                    reportError(err);
                }
            } else if (type.startsWith("permission")) {
                String pid = str(props, "id");
                String sid = str(props, "sessionID");
                if (!TextUtils.isEmpty(pid)) {
                    final String useSession = TextUtils.isEmpty(sid) ? sessionId : sid;
                    if (useSession != null) {
                        final String usePid = pid;
                        io.execute(() -> api.replyPermission(useSession, usePid, "always"));
                    }
                }
            } else if ("session.idle".equals(type)) {
                lastSignalMs = System.currentTimeMillis();
                finishThoughts();
                removeWorking();
                setBusy(false);
            }
        } catch (Throwable t) {
            // never let a malformed event kill the stream
        }
    }

    private void handlePart(JsonObject part) {
        String sid = str(part, "sessionID");
        if (sessionId != null && !sid.isEmpty() && !sessionId.equals(sid)) return;
        lastSignalMs = System.currentTimeMillis();

        String pid = str(part, "id");
        if (TextUtils.isEmpty(pid)) return;
        // The engine also streams the user's own message back - never render it twice.
        if ("user".equals(messageRoles.get(str(part, "messageID")))) return;
        String ptype = str(part, "type");

        if ("text".equals(ptype)) {
            String text = str(part, "text");
            if (TextUtils.isEmpty(text)) return;
            String mid = str(part, "messageID");
            if (messageRoles.get(mid) == null && sentTexts.contains(normalize(text))) {
                messageRoles.put(mid, "user");
                return;
            }
            ChatItem item = byKey.get(pid);
            if (item == null) {
                finishThoughts();
                item = ChatItem.assistant(pid);
                addItem(item);
            }
            item.fullText = text;
            if (item.shown > text.length()) item.shown = 0;
            if (item.shown == 0) item.text = "";
            startReveal();
            rebind(item);

        } else if ("tool".equals(ptype)) {
            ChatItem existing = byKey.get(pid);
            JsonObject state = part.has("state") && part.get("state").isJsonObject()
                    ? part.getAsJsonObject("state") : new JsonObject();
            JsonObject inp = state.has("input") && state.get("input").isJsonObject()
                    ? state.getAsJsonObject("input") : new JsonObject();

            String toolName = str(part, "tool");
            String cmd = firstNonEmpty(
                    str(inp, "command"),
                    str(inp, "cmd"),
                    str(inp, "filePath"),
                    str(inp, "path"),
                    str(inp, "pattern"),
                    str(inp, "description"));
            if (cmd.isEmpty()) cmd = str(state, "title");

            // CLI hides bookkeeping writes (AGENTS.md / SKILL.md / vault notes).
            if (existing == null && isBookkeeping(toolName, cmd)) return;

            ChatItem item = existing;
            if (item == null) {
                finishThoughts();
                item = ChatItem.tool(pid);
                addItem(item);
            }

            item.toolName = toolName;
            item.status = str(state, "status");
            item.command = cmd;
            item.diff = str(state, "diff");

            String out = str(state, "output");
            if (out.isEmpty()) out = str(state, "error");
            item.output = out;

            if (!TextUtils.isEmpty(item.status)) {
                setBusy(!"error".equals(item.status) && "running".equals(item.status));
            }
            rebind(item);
        } else if ("reasoning".equals(ptype)) {
            String text = str(part, "text");
            if (TextUtils.isEmpty(text)) return;
            ChatItem item = byKey.get(pid);
            if (item == null) {
                item = ChatItem.thought(pid);
                item.expanded = false;
                addItem(item);
            }
            item.text = text.length() > 6000 ? text.substring(0, 6000) : text;
            String title = thoughtTitle(item.text);
            if (!title.isEmpty()) item.title = title;

            JsonObject ptime = part.has("time") && part.get("time").isJsonObject()
                    ? part.getAsJsonObject("time") : null;
            long start = ptime != null ? num(ptime, "start") : 0;
            long end = ptime != null ? num(ptime, "end") : 0;
            if (start > 0) item.startMs = start;
            if (end > 0) {
                item.streaming = false;
                item.durationMs = Math.max(0, end - item.startMs);
            }
            rebind(item);
        }
    }

    private static boolean isBookkeeping(String toolName, String path) {
        String n = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        boolean writer = n.contains("write") || n.contains("edit") || n.contains("patch");
        if (!writer || path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("agents.md") || p.contains("skill.md") || p.contains("zyvovault")
                || p.contains("/vault/");
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // --------------------------------------------------------------- send

    private void sendMessage() {
        final String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (!engine.isReady()) {
            UiUtil.toast(requireContext(), "Engine is still starting — one moment");
            return;
        }

        input.setText("");
        addItem(ChatItem.user(text));
        rememberSent(text);
        banner.setVisibility(View.GONE);
        workingStartMs = System.currentTimeMillis();
        pinnedToBottom = true;

        setBusy(true);
        scrollToBottom();
        ui.postDelayed(this::scrollToBottom, 90);

        io.execute(() -> {
            try {
                if (sessionId == null || sessionId.isEmpty()) {
                    JsonObject created = api.createSession(firstLine(text));
                    sessionId = str(created, "id");
                    prefs.setLastSessionId(sessionId);
                }
                api.promptAsync(sessionId, text, prefs.variantOf(prefs.modelId()),
                        prefs.agentMode());
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> {
                    removeWorking();
                    addItem(ChatItem.error(msg));
                    setBusy(false);
                });
            }
        });
    }

    /** The engine streams the user's own prompt back; remember it to skip it. */
    private void rememberSent(String text) {
        sentTexts.addLast(normalize(text));
        while (sentTexts.size() > 20) sentTexts.removeFirst();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }


    private void setBusy(boolean value) {
        boolean wasBusy = busy;
        busy = value;
        ui.removeCallbacks(busyWatchdog);
        ui.removeCallbacks(stallTick);
        if (send != null) {
            send.setImageResource(value ? R.drawable.ic_stop : R.drawable.ic_arrow_up);
            send.setContentDescription(getString(value ? R.string.stop : R.string.send));
        }
        if (workingBar != null) workingBar.setVisibility(value ? View.VISIBLE : View.GONE);
        if (workingSpinner != null) {
            workingSpinner.setVisibility(value ? View.VISIBLE : View.GONE);
        }
        ui.removeCallbacks(workingTick);
        if (value) {
            lastSignalMs = System.currentTimeMillis();
            stallWarned = false;
            if (workingBarText != null) workingBarText.setText(getString(R.string.working_bar));
            ui.postDelayed(workingTick, 1000);
            ui.postDelayed(busyWatchdog, 180_000L);
            ui.postDelayed(stallTick, 5000L);
        } else if (wasBusy && workingStartMs > 0 && workingBarText != null && workingBar != null) {
            // turn finished: same spot keeps the total time instead of the spinner
            workingBarText.setText(getString(R.string.worked_for_fmt,
                    shortDuration(System.currentTimeMillis() - workingStartMs)));
            workingBar.setVisibility(View.VISIBLE);
        }
    }

    /** A tool is mid-run, so silence is expected — do not treat it as a stall. */
    private boolean hasRunningTool() {
        for (int i = 0; i < items.size(); i++) {
            ChatItem it = items.get(i);
            if (it.kind == ChatItem.TOOL && "running".equals(it.status)) return true;
        }
        return false;
    }

    /**
     * Ends a turn that produced nothing and explains it in plain words: the
     * engine keeps retrying a rate-limited lane silently, which used to look
     * like the app simply hanging.
     */
    private void failTurn(String reason) {
        finishThoughts();
        removeWorking();
        setBusy(false);
        addItem(ChatItem.error(reason + "\n\n"
                + "Most likely the free lane hit its quota (rate limit).\n"
                + "Fix: switch mobile data / Wi-Fi for a fresh IP, then send again.\n"
                + "Drawer → Engine logs shows the raw engine output."));
        showBanner("Model did not answer — see Engine logs", false);
        if (sessionId != null) {
            final String sid = sessionId;
            io.execute(() -> api.abort(sid));
        }
    }

    /** Shows a real engine/provider error as an error card (never silently). */
    private void reportError(String raw) {
        String text = friendlyError(raw);
        long now = System.currentTimeMillis();
        if (text.equals(lastReportedError) && now - lastReportedAt < 10_000L) return;
        lastReportedError = text;
        lastReportedAt = now;
        finishThoughts();
        removeWorking();
        setBusy(false);
        addItem(ChatItem.error(text));
        showBanner("Engine reported an error — see chat", false);
    }

    /** Turns provider JSON into a plain-language hint plus the raw text. */
    private static String friendlyError(String raw) {
        String s = raw == null ? "" : raw.trim();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("freeusagelimit") || low.contains("usage limit")
                || low.contains("rate limit") || low.contains("rate-limit")
                || low.contains("429")) {
            return "Free lane limit reached (rate limit).\n"
                    + "Switch mobile data / Wi-Fi for a fresh IP, then send again.\n\n" + s;
        }
        if (low.contains("unauthorized") || low.contains("401") || low.contains("invalid api key")) {
            return "The lane key was rejected (auth).\n"
                    + "Open Settings and check the API key.\n\n" + s;
        }
        return s.isEmpty() ? "The engine reported an error." : s;
    }

    /** Digs the human message out of an engine error object. */
    private static String errorText(JsonElement el) {
        return errorText(el, 0);
    }

    private static String errorText(JsonElement el, int depth) {
        if (el == null || el.isJsonNull() || depth > 5) return "";
        if (el.isJsonPrimitive()) {
            try {
                return el.getAsString();
            } catch (Exception e) {
                return "";
            }
        }
        if (!el.isJsonObject()) return el.toString();
        JsonObject o = el.getAsJsonObject();
        String m = str(o, "message");
        if (!m.isEmpty()) return m;
        for (String key : new String[]{"data", "error", "cause"}) {
            if (o.has(key)) {
                String nested = errorText(o.get(key), depth + 1);
                if (!nested.isEmpty()) return nested;
            }
        }
        return o.toString();
    }

    /** Stops every still-streaming thought row (reasoning done for this turn). */
    private void finishThoughts() {
        for (int i = 0; i < items.size(); i++) {
            ChatItem item = items.get(i);
            if (item.kind == ChatItem.THOUGHT && item.streaming) {
                item.streaming = false;
                item.expanded = false;
                if (item.durationMs <= 0) {
                    item.durationMs = System.currentTimeMillis() - item.startMs;
                }
                adapter.notifyItemChanged(i);
            }
        }
    }

    /** CLI reasoningSummary: the bold first line of the reasoning text. */
    private static String thoughtTitle(String text) {
        if (text == null) return "";
        String s = text.trim();
        int nl = s.indexOf('\n');
        String first = (nl >= 0 ? s.substring(0, nl) : s).trim();
        if (first.length() > 4 && first.startsWith("**") && first.endsWith("**")) {
            return first.substring(2, first.length() - 2).trim();
        }
        return "";
    }

    // ----------------------------------------------------------- sessions

    /** Clears the conversation (top bar + and the drawer's New chat call this). */
    public void newSession() {
        workingStartMs = 0;
        sessionId = null;
        prefs.setLastSessionId("");
        sseLoaded = false;
        items.clear();
        byKey.clear();
        messageRoles.clear();
        adapter.notifyDataSetChanged();
        setBusy(false);
        updateEmpty();
        if (titleView != null) titleView.setText(getString(R.string.app_name));
    }

    /** Opens a session picked in the drawer (called by MainActivity). */
    public void openSession(String id, String title) {
        if (TextUtils.isEmpty(id)) return;
        workingStartMs = 0;
        sessionId = id;
        prefs.setLastSessionId(id);
        sseLoaded = false;
        items.clear();
        byKey.clear();
        messageRoles.clear();
        adapter.notifyDataSetChanged();
        setBusy(false);
        updateEmpty();
        if (titleView != null) {
            titleView.setText(TextUtils.isEmpty(title) ? getString(R.string.app_name) : title);
        }
        loadMessages(id);
    }

    private void openSessionsDrawer() {
        DrawerLayout drawer = activityDrawer();
        if (drawer == null) {
            UiUtil.toast(requireContext(), "Session drawer unavailable");
            return;
        }
        loadDrawerSessions();
        drawer.openDrawer(GravityCompat.START);
    }

    private DrawerLayout activityDrawer() {
        if (getActivity() == null) return null;
        return getActivity().findViewById(R.id.drawer_layout);
    }

    private void closeDrawer() {
        DrawerLayout drawer = activityDrawer();
        if (drawer != null) drawer.closeDrawer(GravityCompat.START);
    }

    private void loadDrawerSessions() {
        if (sessionAdapter == null || engine == null || !engine.isReady()) return;
        io.execute(() -> {
            try {
                JsonArray arr = api.listSessions();
                final List<SessionAdapter.Session> list = new ArrayList<>();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String id = str(o, "id");
                    if (TextUtils.isEmpty(id)) continue;
                    String t = str(o, "title");
                    long updated = 0;
                    if (o.has("time") && o.get("time").isJsonObject()) {
                        updated = num(o.getAsJsonObject("time"), "updated");
                    }
                    list.add(new SessionAdapter.Session(id,
                            TextUtils.isEmpty(t) ? "Untitled" : t, updated));
                }
                list.sort((a, b) -> Long.compare(b.updated, a.updated));
                ui.post(() -> {
                    if (!isAdded() || sessionAdapter == null) return;
                    sessionAdapter.setItems(list);
                    sessionAdapter.setCurrent(sessionId);
                });
            } catch (Exception ignored) {
                // drawer simply stays empty
            }
        });
    }

    private void sessionActions(SessionAdapter.Session session) {
        new AlertDialog.Builder(requireContext())
                .setTitle(session.title)
                .setItems(new String[]{getString(R.string.session_rename),
                        getString(R.string.session_delete)}, (d, which) -> {
                    if (which == 0) renameSession(session);
                    else deleteSession(session);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renameSession(SessionAdapter.Session session) {
        EditText field = new EditText(requireContext());
        field.setText(session.title);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.session_rename)
                .setView(field)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String title = field.getText().toString().trim();
                    if (title.isEmpty()) return;
                    io.execute(() -> {
                        try {
                            api.renameSession(session.id, title);
                        } catch (Exception ignored) {
                        }
                        ui.post(() -> {
                            loadDrawerSessions();
                            if (session.id.equals(sessionId) && titleView != null) {
                                titleView.setText(title);
                            }
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSession(SessionAdapter.Session session) {
        new AlertDialog.Builder(requireContext())
                .setMessage(session.title)
                .setPositiveButton(R.string.session_delete, (d, w) -> io.execute(() -> {
                    try {
                        api.deleteSession(session.id);
                    } catch (Exception ignored) {
                    }
                    ui.post(() -> {
                        if (session.id.equals(sessionId)) newSession();
                        loadDrawerSessions();
                    });
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openTerminal() {
        try {
            startActivity(new Intent(requireContext(),
                    Class.forName("com.termux.app.TermuxActivity")));
        } catch (Throwable t) {
            UiUtil.toast(requireContext(), "Terminal is not available");
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(requireContext(),
                    Class.forName("com.termux.app.activities.SettingsActivity")));
        } catch (Throwable t) {
            UiUtil.toast(requireContext(), "Settings are not available");
        }
    }

    private void loadMessages(String id) {
        if (loadingView != null) loadingView.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JsonArray arr = api.messages(id);
                final List<ChatItem> rebuilt = parseMessages(arr);
                ui.post(() -> {
                    if (!isAdded()) return;
                    if (loadingView != null) loadingView.setVisibility(View.GONE);
                    items.clear();
                    byKey.clear();
                    for (ChatItem ci : rebuilt) {
                        items.add(ci);
                        byKey.put(ci.key, ci);
                    }
                    sseLoaded = true;
                    pinnedToBottom = true;
                    adapter.notifyDataSetChanged();
                    updateEmpty();
                    scrollToBottom();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (loadingView != null) loadingView.setVisibility(View.GONE);
                });
            }
        });
    }

    private List<ChatItem> parseMessages(JsonArray arr) {
        List<ChatItem> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            JsonObject info = msg.has("info") && msg.get("info").isJsonObject()
                    ? msg.getAsJsonObject("info") : new JsonObject();
            String role = str(info, "role");
            boolean isUser = "user".equals(role);

            JsonArray parts = msg.has("parts") && msg.get("parts").isJsonArray()
                    ? msg.getAsJsonArray("parts") : new JsonArray();

            if (parts.size() == 0) continue;

            for (JsonElement pe : parts) {
                if (!pe.isJsonObject()) continue;
                JsonObject part = pe.getAsJsonObject();
                String ptype = str(part, "type");
                String pid = str(part, "id");

                if ("text".equals(ptype)) {
                    String text = str(part, "text");
                    if (TextUtils.isEmpty(text)) continue;
                    if (isUser) {
                        out.add(ChatItem.user(text));
                    } else {
                        ChatItem item = ChatItem.assistant(pid);
                        item.text = text;
                        item.fullText = text;
                        item.shown = text.length();
                        out.add(item);
                    }
                } else if ("tool".equals(ptype)) {
                    JsonObject state = part.has("state") && part.get("state").isJsonObject()
                            ? part.getAsJsonObject("state") : new JsonObject();
                    JsonObject inp = state.has("input") && state.get("input").isJsonObject()
                            ? state.getAsJsonObject("input") : new JsonObject();
                    String toolName = str(part, "tool");
                    String cmd = firstNonEmpty(str(inp, "command"), str(inp, "filePath"),
                            str(inp, "path"), str(inp, "pattern"));
                    if (cmd.isEmpty()) cmd = str(state, "title");
                    if (isBookkeeping(toolName, cmd)) continue;
                    ChatItem item = ChatItem.tool(pid);
                    item.toolName = toolName;
                    item.status = str(state, "status");
                    item.command = cmd;
                    item.diff = str(state, "diff");
                    String outText = str(state, "output");
                    if (outText.isEmpty()) outText = str(state, "error");
                    item.output = outText;
                    out.add(item);
                } else if ("reasoning".equals(ptype)) {
                    String text = str(part, "text");
                    if (TextUtils.isEmpty(text)) continue;
                    ChatItem item = ChatItem.thought(pid);
                    item.streaming = false;
                    item.expanded = false;
                    item.text = text.length() > 6000 ? text.substring(0, 6000) : text;
                    item.title = thoughtTitle(item.text);
                    JsonObject ptime = part.has("time") && part.get("time").isJsonObject()
                            ? part.getAsJsonObject("time") : null;
                    long start = ptime != null ? num(ptime, "start") : 0;
                    long end = ptime != null ? num(ptime, "end") : 0;
                    item.startMs = start;
                    item.durationMs = end > 0 && start > 0 ? end - start : 0;
                    out.add(item);
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------- utils

    private static String fmtSpan(long ms) {
        long s = Math.max(1, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        long r = s % 60;
        return r == 0 ? m + "m" : m + "m " + r + "s";
    }

    private void addItem(ChatItem item) {
        items.add(item);
        byKey.put(item.key, item);
        adapter.notifyItemInserted(items.size() - 1);
        updateEmpty();
        if (pinnedToBottom) scrollToBottomAnimated();
    }

    private void rebind(ChatItem item) {
        int index = items.indexOf(item);
        if (index >= 0) {
            adapter.notifyItemChanged(index);
            scrollIfPinned();
        }
    }

    private void updateEmpty() {
        if (emptyView != null) {
            emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void scrollIfPinned() {
        if (pinnedToBottom) scrollToBottom();
    }

    private void scrollToBottom() {
        if (list == null || adapter == null || adapter.getItemCount() == 0) return;
        final RecyclerView rv = list;
        rv.postOnAnimation(() -> {
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            if (lm == null) return;
            int lastPos = adapter.getItemCount() - 1;
            View last = lm.findViewByPosition(lastPos);
            if (last == null) return; // not laid out yet; next tick catches it
            int dy = last.getBottom() - (rv.getHeight() - rv.getPaddingBottom());
            if (dy > 0) rv.scrollBy(0, dy);
            updatePinned();
        });
    }

    /** New rows animate in instead of snapping the whole transcript. */
    private void scrollToBottomAnimated() {
        if (list == null || adapter == null || adapter.getItemCount() == 0) return;
        list.post(() -> {
            if (adapter.getItemCount() > 0) {
                list.smoothScrollToPosition(adapter.getItemCount() - 1);
            }
        });
    }

    private void startReveal() {
        ui.removeCallbacks(revealTick);
        ui.postDelayed(revealTick, 32);
    }

    /** Shows the remaining text immediately (turn finished). */
    private void flushReveal() {
        ui.removeCallbacks(revealTick);
        for (int i = 0; i < items.size(); i++) {
            ChatItem it = items.get(i);
            if (it.kind != ChatItem.ASSISTANT || it.fullText == null) continue;
            if (it.shown < it.fullText.length()) {
                it.shown = it.fullText.length();
                it.text = it.fullText;
                adapter.notifyItemChanged(i);
            }
        }
        scrollIfPinned();
    }

    /** Drops the "Working." row once a real part (thinking, tool, text) arrives. */
    private void removeWorking() {
        ui.removeCallbacks(workingTick);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind == ChatItem.WORKING) {
                ChatItem removed = items.remove(i);
                byKey.remove(removed.key);
                adapter.notifyItemRemoved(i);
                return;
            }
        }
    }

    /** Track whether the user is reading history or pinned to the newest row. */
    private void updatePinned() {
        if (list == null || adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        if (lm == null) return;
        if (autoScrolling) {
            pinnedToBottom = true;
            if (scrollBottom != null) scrollBottom.setVisibility(View.GONE);
            return;
        }
        pinnedToBottom = lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
        if (scrollBottom != null) {
            scrollBottom.setVisibility(pinnedToBottom ? View.GONE : View.VISIBLE);
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            try {
                return e.getAsString();
            } catch (Exception ex) {
                return "";
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl > 0 ? s.substring(0, nl) : s;
        return line.length() > 60 ? line.substring(0, 60) : line;
    }
}
