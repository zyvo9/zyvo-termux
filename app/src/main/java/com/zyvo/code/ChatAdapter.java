package com.zyvo.code;

import com.termux.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * ZCode-style transcript with zyvo CLI behaviours: collapsing "Thought" rows,
 * inline tool rows with running spinner, output preview on tap, effort-aware
 * model chips and "Worked for ..." turn summaries.
 */
public final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int V_USER = 0;
    private static final int V_ASSISTANT = 1;
    private static final int V_TOOL = 2;
    private static final int V_SUMMARY = 3;
    private static final int V_THOUGHT = 4;
    private static final int V_WORKING = 5;
    private static final int V_TODO = 6;
    private static final int V_NOTICE = 7;
    private static final int V_ERROR = 8;

    static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * Payload for a stream tick: only the text of this row changed. RecyclerView
     * hands it to the 3-arg onBindViewHolder so we can skip markdown parsing,
     * the entrance animation, action-bar visibility and a full re-measure on
     * every frame.
     */
    public static final Object PAYLOAD_STREAM = new Object();

    private final Context ctx;
    private final List<ChatItem> items;
    private final Markwon markwon;
    /** Animator scale 0 (developer setting / battery saver) disables our motion. */
    private final boolean reduceMotion;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());

    public ChatAdapter(Context ctx, List<ChatItem> items) {
        this.ctx = ctx;
        this.items = items;
        this.markwon = Markwon.builder(ctx)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(ctx))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        builder.codeTextColor(ContextCompat.getColor(ctx, R.color.code_text));
                        builder.codeBackgroundColor(ContextCompat.getColor(ctx, R.color.code_bg));
                        builder.codeTypeface(Typeface.MONOSPACE);
                    }
                })
                .build();
        float scale = 1f;
        try {
            scale = android.provider.Settings.Global.getFloat(ctx.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
        }
        this.reduceMotion = scale == 0f;
    }

    /** Small entrance for a row the first time it appears; never on re-bind. */
    private void animateIn(View v, ChatItem item) {
        if (item == null || item.animated) return;
        item.animated = true;
        if (reduceMotion) return;
        v.setAlpha(0f);
        v.setTranslationY(6f * ctx.getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f).setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    @Override
    public int getItemViewType(int position) {
        switch (items.get(position).kind) {
            case ChatItem.USER:
                return V_USER;
            case ChatItem.SUMMARY:
                return V_SUMMARY;
            case ChatItem.TOOL:
                return V_TOOL;
            case ChatItem.THOUGHT:
                return V_THOUGHT;
            case ChatItem.WORKING:
                return V_WORKING;
            case ChatItem.TODO:
                return V_TODO;
            case ChatItem.NOTICE:
                return V_NOTICE;
            case ChatItem.ERROR:
                return V_ERROR;
            default:
                return V_ASSISTANT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(ctx);
        if (viewType == V_USER) {
            return new MessageVH(inf.inflate(R.layout.item_message_user, parent, false));
        }
        if (viewType == V_SUMMARY) {
            return new SummaryVH(inf.inflate(R.layout.item_summary, parent, false));
        }
        if (viewType == V_TOOL) {
            return new ToolVH(inf.inflate(R.layout.item_tool, parent, false));
        }
        if (viewType == V_THOUGHT) {
            return new ThoughtVH(inf.inflate(R.layout.item_thought, parent, false));
        }
        if (viewType == V_WORKING) {
            return new WorkingVH(inf.inflate(R.layout.item_working, parent, false));
        }
        if (viewType == V_TODO) {
            return new TodoVH(inf.inflate(R.layout.item_todo, parent, false));
        }
        if (viewType == V_NOTICE) {
            return new NoticeVH(inf.inflate(R.layout.item_notice, parent, false));
        }
        if (viewType == V_ERROR) {
            return new ErrorVH(inf.inflate(R.layout.item_error, parent, false));
        }
        return new MessageVH(inf.inflate(R.layout.item_message_assistant, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_STREAM)) {
            ChatItem item = items.get(position);
            if (holder instanceof MessageVH) {
                bindStreamText((MessageVH) holder, item);
                return;
            }
            if (holder instanceof ThoughtVH) {
                bindStreamThought((ThoughtVH) holder, item, position);
                return;
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    /** Cheap path: only the growing text (and the hidden footer) changes. */
    private void bindStreamText(MessageVH h, ChatItem item) {
        if (item.kind != ChatItem.ASSISTANT) {
            bindMessage(h, item);
            return;
        }
        boolean revealing = item.fullText != null && item.fullText.length() > item.shown;
        if (!revealing) {
            // stream finished - one full bind so markdown, copy and duration land
            bindMessage(h, item);
            return;
        }
        h.text.setText(plainMarkdown(item.text));
        if (h.time.getText().length() > 0) h.time.setText("");
        if (h.actions.getVisibility() == View.VISIBLE) h.actions.setVisibility(View.GONE);
    }

    /** Cheap path for a thinking row: only the live 2-line tail moves. */
    private void bindStreamThought(ThoughtVH h, ChatItem item, int position) {
        if (item.kind != ChatItem.THOUGHT || !item.streaming) {
            bindThought(h, item, position);
            return;
        }
        if (TextUtils.isEmpty(item.text)) return;
        if (h.body.getVisibility() != View.VISIBLE) {
            h.body.setVisibility(View.VISIBLE);
            h.body.setMaxLines(2);
            h.body.setEllipsize(TextUtils.TruncateAt.START);
            h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_disabled));
            h.body.setTextSize(12f);
        }
        String live = tail(item.text, 2);
        if (!live.contentEquals(h.body.getText())) h.body.setText(live);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatItem item = items.get(position);
        if (holder instanceof MessageVH) {
            bindMessage((MessageVH) holder, item);
        } else if (holder instanceof SummaryVH) {
            bindSummary((SummaryVH) holder, item, position);
        } else if (holder instanceof WorkingVH) {
            bindWorking((WorkingVH) holder, item);
        } else if (holder instanceof ToolVH) {
            bindTool((ToolVH) holder, item, position);
        } else if (holder instanceof ThoughtVH) {
            bindThought((ThoughtVH) holder, item, position);
        } else if (holder instanceof TodoVH) {
            bindTodo((TodoVH) holder, item);
        } else if (holder instanceof NoticeVH) {
            bindNotice((NoticeVH) holder, item);
        } else if (holder instanceof ErrorVH) {
            bindError((ErrorVH) holder, item);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ThoughtVH) {
            ((ThoughtVH) holder).stopTicker();
            ((ThoughtVH) holder).stopShimmer();
        }
        if (holder instanceof ToolVH) {
            ((ToolVH) holder).stopTicker();
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ------------------------------------------------------------ messages

    private void bindMessage(MessageVH h, ChatItem item) {
        animateIn(h.itemView, item);
        if (item.kind == ChatItem.USER) {
            markwon.setMarkdown(h.text, item.text == null ? "" : item.text);
            h.time.setText(stamp(item.timeMs));
            h.time.setVisibility(TextUtils.isEmpty(h.time.getText()) ? View.GONE : View.VISIBLE);
            return;
        }

        boolean revealing = item.fullText != null && item.fullText.length() > item.shown;
        if (revealing) {
            // word-by-word reveal: cheap plain text, no markdown re-parse per frame.
            // Markers are stripped so the live view reads clean; the finished text
            // is re-rendered as full markdown (headings, tables, bullets).
            h.text.setText(plainMarkdown(item.text));
        } else {
            markwon.setMarkdown(h.text, item.text == null ? "" : item.text);
        }
        h.text.setTextColor(ContextCompat.getColor(ctx,
                item.error ? R.color.danger : R.color.text_primary));

        boolean hasText = !TextUtils.isEmpty(item.text) && !revealing;
        h.actions.setVisibility(hasText ? View.VISIBLE : View.GONE);
        h.time.setText(revealing ? "" : footer(item));

        if (hasText) {
            h.btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("zyvo", item.text));
                    UiUtil.toast(ctx, ctx.getString(R.string.copied));
                }
            });
            h.btnRegen.setOnClickListener(v -> {
                if (messageActions != null) messageActions.onRegenerate(item);
            });
            h.btnShare.setOnClickListener(v -> shareText(item.text));
        }
    }

    /** Grok-style share action under a reply. */
    private void shareText(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        try {
            Intent chooser = Intent.createChooser(send, ctx.getString(R.string.share));
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Throwable t) {
            UiUtil.toast(ctx, ctx.getString(R.string.share_failed));
        }
    }

    /**
     * Cheap single-pass markdown stripper for the streaming view: drops emphasis
     * markers, heading hashes and code fences, turns list dashes into bullets.
     * No regex, no allocation per line beyond the result builder.
     */
    private static String plainMarkdown(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        boolean lineStart = true;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (lineStart) {
                int j = i;
                while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                int h = j;
                while (h < s.length() && s.charAt(h) == '#') h++;
                if (h > j && h < s.length() && s.charAt(h) == ' ') {
                    i = h + 1;
                    continue;
                }
                if (j < s.length() && (s.charAt(j) == '-' || s.charAt(j) == '*' || s.charAt(j) == '+')
                        && j + 1 < s.length() && s.charAt(j + 1) == ' ') {
                    out.append("• ");
                    i = j + 2;
                    lineStart = false;
                    continue;
                }
                if (j < s.length() && s.charAt(j) == '>' && j + 1 < s.length()
                        && s.charAt(j + 1) == ' ') {
                    i = j + 2;
                    continue;
                }
                lineStart = false;
            }
            if (c == '*' || c == '`') {
                i++;
                continue;
            }
            out.append(c);
            lineStart = c == '\n';
            i++;
        }
        return out.toString();
    }

    /**
     * Strips the shell-env preamble the engine prepends to every bash call
     * (`export HOME=...; export PATH=...;`) so the row shows the real command.
     */
    private static String cleanCommand(String cmd) {
        if (cmd == null) return "";
        String s = cmd.trim();
        int guard = 0;
        while (s.startsWith("export ") && guard++ < 4) {
            int sep = s.indexOf(';');
            if (sep < 0) break;
            s = s.substring(sep + 1).trim();
        }
        return s;
    }

    private String stamp(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance();
        int doy = now.get(Calendar.DAY_OF_YEAR);
        int year = now.get(Calendar.YEAR);
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        boolean today = then.get(Calendar.DAY_OF_YEAR) == doy && then.get(Calendar.YEAR) == year;
        return (today ? timeFmt : dayFmt).format(new Date(ms));
    }

    private static String fmtDuration(long ms) {
        if (ms <= 0) return "";
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format(Locale.US, "%.1fs", ms / 1000.0);
        long m = ms / 60000;
        long s = (ms % 60000) / 1000;
        return m + "m " + s + "s";
    }

    /** Footer under a reply: "12:24 PM · 5s · interrupted". */
    private String footer(ChatItem item) {
        String t = stamp(item.timeMs);
        String d = item.durationMs > 0 ? fmtDuration(item.durationMs) : "";
        String base = TextUtils.isEmpty(t) ? d : (d.isEmpty() ? t : t + " · " + d);
        if (item.interrupted) {
            return base.isEmpty() ? ctx.getString(R.string.interrupted)
                    : base + " · " + ctx.getString(R.string.interrupted);
        }
        return base;
    }

    // ------------------------------------------------------------- thought

    private void bindWorking(WorkingVH h, ChatItem item) {
        h.text.setText(TextUtils.isEmpty(item.text)
                ? ctx.getString(R.string.working) : item.text);
    }

    private void bindThought(ThoughtVH h, ChatItem item, int position) {
        animateIn(h.itemView, item);
        boolean hasTitle = !TextUtils.isEmpty(item.title);
        if (item.streaming) {
            h.title.setText(hasTitle
                    ? ctx.getString(R.string.thinking_of, item.title)
                    : ctx.getString(R.string.thinking));
            h.spinner.setVisibility(View.VISIBLE);
            h.chevron.setVisibility(View.GONE);
            h.startTicker();
            h.startShimmer();
        } else {
            h.stopTicker();
            h.stopShimmer();
            h.spinner.setVisibility(View.VISIBLE);
            h.spinner.setAlpha(0.75f);
            String d = fmtDuration(item.durationMs);
            h.title.setText(d.isEmpty()
                    ? ctx.getString(R.string.thought)
                    : ctx.getString(R.string.thought_for, d));
            h.chevron.setVisibility(View.VISIBLE);
            h.chevron.setRotation(item.expanded ? 180f : 0f);
        }

        boolean hasBody = !TextUtils.isEmpty(item.text);
        boolean showBody = hasBody && (item.expanded || item.streaming);
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);
        if (showBody) {
            if (item.streaming) {
                // live tail of the reasoning tokens — plain text, no markdown parse
                h.body.setText(tail(item.text, 2));
                h.body.setMaxLines(2);
                h.body.setEllipsize(TextUtils.TruncateAt.START);
                h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_disabled));
                h.body.setTextSize(12f);
            } else {
                h.body.setMaxLines(Integer.MAX_VALUE);
                h.body.setEllipsize(null);
                h.body.setTextSize(13f);
                h.body.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                markwon.setMarkdown(h.body, item.text);
            }
        }

        boolean toggleable = !item.streaming && hasBody;
        h.header.setOnClickListener(toggleable ? v -> {
            item.expanded = !item.expanded;
            notifyItemChanged(position);
        } : null);
        h.header.setClickable(toggleable);
    }

    // ------------------------------------------------------------------ todo

    private void bindTodo(TodoVH h, ChatItem item) {
        animateIn(h.itemView, item);
        java.util.List<ChatItem.Todo> todos = item.todos;
        if (todos == null || todos.isEmpty()) {
            h.itemView.setVisibility(View.GONE);
            return;
        }
        h.itemView.setVisibility(View.VISIBLE);
        int done = 0;
        for (ChatItem.Todo t : todos) {
            if ("completed".equals(t.status) || "cancelled".equals(t.status)) done++;
        }
        h.count.setText(done + "/" + todos.size());
        h.rows.removeAllViews();
        for (ChatItem.Todo t : todos) {
            boolean completed = "completed".equals(t.status);
            boolean cancelled = "cancelled".equals(t.status);
            boolean active = "in_progress".equals(t.status);
            String glyph = completed ? "✓ " : cancelled ? "✕ " : active ? "◐ " : "○ ";
            TextView row = new TextView(ctx);
            row.setText(glyph + t.content);
            row.setTextSize(12);
            row.setLineSpacing(dp(2), 1f);
            row.setPadding(0, dp(3), 0, dp(3));
            row.setTextColor(ContextCompat.getColor(ctx,
                    completed || cancelled ? R.color.text_disabled
                            : active ? R.color.text_primary : R.color.text_secondary));
            if (completed || cancelled) {
                row.setPaintFlags(row.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            h.rows.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- summary
    private void bindSummary(SummaryVH h, ChatItem item, int position) {
        h.text.setText(TextUtils.isEmpty(item.text)
                ? ctx.getString(R.string.working) : item.text);
        h.chevron.setRotation(item.expanded ? 180f : 0f);
        h.itemView.setOnClickListener(v -> {
            item.expanded = !item.expanded;
            notifyItemChanged(position);
            int p = position + 1;
            while (p < items.size()) {
                ChatItem c = items.get(p);
                if (c.kind != ChatItem.TOOL || c.group != item.group) break;
                notifyItemChanged(p);
                p++;
            }
        });
    }

    // ---------------------------------------------------------------- tool

    private void bindTool(ToolVH h, ChatItem item, int position) {
        boolean collapsed = false;
        for (int p = position - 1; p >= 0; p--) {
            ChatItem c = items.get(p);
            if (c.kind == ChatItem.SUMMARY) {
                if (c.group == item.group) collapsed = !c.expanded;
                break;
            }
            if (c.kind != ChatItem.TOOL) break;
        }
        h.itemView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapsed) {
            h.stopTicker();
            return;
        }
        boolean running = "running".equals(item.status) || "pending".equals(item.status);
        boolean failed = item.error || "error".equals(item.status);

        animateIn(h.itemView, item);
        if (item.hidden) {
            h.itemView.setVisibility(View.GONE);
            h.stopTicker();
            return;
        }

        h.spinner.setVisibility(running ? View.VISIBLE : View.GONE);
        h.icon.setVisibility(running ? View.GONE : View.VISIBLE);
        if (running) h.startTicker();
        else h.stopTicker();

        h.icon.setImageResource(iconFor(item.toolName, item.command));
        h.icon.setColorFilter(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_secondary));

        String clean = cleanCommand(item.command);
        String tool = item.toolName == null ? "" : item.toolName.toLowerCase(Locale.ROOT);

        // Reference layout (user screenshot): [icon] Ran command  +1 -1  Title
        h.label.setText(labelFor(item.toolName, item.status));
        h.label.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_secondary));

        String desc = !TextUtils.isEmpty(item.label) ? item.label : clean;
        if (TextUtils.isEmpty(desc)) desc = firstLine(item.output);
        desc = desc == null ? "" : desc.trim();
        String title = desc.isEmpty() ? titleFor(item.toolName, clean) : firstLine(desc);
        if (title.length() > 90) title = title.substring(0, 89) + "…";
        h.name.setText(title);
        h.name.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_primary));

        // +N −N sits IN FRONT of the title for writes/edits.
        int add = 0;
        int del = 0;
        if (!TextUtils.isEmpty(item.diff)) {
            int[] c = diffCounts(item.diff);
            add = c[0];
            del = c[1];
        }
        boolean hasDiff = add + del > 0;
        if (hasDiff) {
            h.diff.setText(diffLabel(add, del));
            h.diff.setVisibility(View.VISIBLE);
            h.diff.setClickable(true);
        } else if (item.liveLines > 0) {
            h.diff.setText("+" + item.liveLines);
            h.diff.setTextColor(ContextCompat.getColor(ctx, R.color.ok));
            h.diff.setVisibility(View.VISIBLE);
            h.diff.setClickable(false);
        } else {
            h.diff.setVisibility(View.GONE);
        }

        // Body: command + output + diff. Tap stages 10 lines -> everything -> closed.
        boolean hasOutput = !TextUtils.isEmpty(item.output);
        boolean hasCmd = !TextUtils.isEmpty(clean);
        int total = hasOutput ? countLines(item.output) : 0;
        boolean open = item.expanded || item.full || failed || running;
        int maxLines = item.full ? 400 : (item.expanded ? 10 : 3);
        if (failed && !item.expanded) maxLines = 8;

        boolean showBody = open && (hasCmd || hasOutput || hasDiff);
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);

        h.cmdLine.setVisibility(open && hasCmd ? View.VISIBLE : View.GONE);
        if (open && hasCmd) h.cmdLine.setText(clean);

        h.output.setVisibility(open && hasOutput ? View.VISIBLE : View.GONE);
        if (open && hasOutput) {
            boolean tailMode = running && !item.expanded && !item.full && total > maxLines;
            h.output.setText(tailMode ? tail(item.output, maxLines)
                    : (item.full ? cap(item.output, 400) : preview(item.output, maxLines)));
            h.output.setMaxLines(maxLines);
            h.output.setEllipsize(item.full ? null : TextUtils.TruncateAt.END);
        }

        boolean overflow = total > maxLines;
        boolean showMeta = open && overflow;
        h.expand.setVisibility(showMeta ? View.VISIBLE : View.GONE);
        if (showMeta) {
            h.expand.setText(item.full
                    ? ctx.getString(R.string.tap_collapse)
                    : ctx.getString(R.string.lines_tap_expand, total));
        }

        boolean canOpen = hasCmd || hasOutput || hasDiff;
        h.chevron.setVisibility(canOpen ? View.VISIBLE : View.GONE);
        h.chevron.setRotation(open ? 180f : 0f);

        // --- diff body (real +/− lines)
        boolean showDiff = item.showDiff && hasDiff;
        h.diffBody.setVisibility(showDiff ? View.VISIBLE : View.GONE);
        if (showDiff) {
            h.diffBody.setText(diffSpans(item.diff));
            h.diffBody.setMaxLines(item.full ? 400 : 10);
            h.diffBody.setEllipsize(TextUtils.TruncateAt.END);
        }

        h.diff.setOnClickListener(hasDiff ? v -> {
            item.showDiff = !item.showDiff;
            notifyItemChanged(position);
        } : null);

        h.expand.setOnClickListener(v -> {
            nextStage(item);
            notifyItemChanged(position);
        });

        h.card.setOnClickListener(v -> {
            nextStage(item);
            notifyItemChanged(position);
        });
    }

    /** Codex-style verb for the row: "Ran command", "Writing file", ... */
    private static String labelFor(String tool, String status) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        boolean done = "completed".equals(status);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) {
            return done ? "Ran command" : "Running command";
        }
        if (n.contains("write")) return done ? "Wrote file" : "Writing file";
        if (n.contains("patch")) return done ? "Applied patch" : "Applying patch";
        if (n.contains("edit")) return done ? "Edited file" : "Editing file";
        if (n.equals("read") || n.startsWith("read_") || n.endsWith("read")) {
            return done ? "Read file" : "Reading file";
        }
        if (n.contains("grep")) return done ? "Searched code" : "Searching code";
        if (n.contains("glob") || n.contains("find")) return done ? "Found files" : "Finding files";
        if (n.contains("list")) return done ? "Listed files" : "Listing files";
        if (n.contains("webfetch") || n.contains("web") || n.contains("http")) {
            return done ? "Fetched page" : "Fetching page";
        }
        if (n.contains("task") || n.contains("agent")) return "Subagent";
        if (n.contains("todo")) return "Tasks";
        return done ? "Used tool" : "Using tool";
    }

    /** 10 lines → everything → closed. */
    private static void nextStage(ChatItem item) {
        if (!item.expanded) {
            item.expanded = true;
            item.full = false;
        } else if (!item.full) {
            item.full = true;
        } else {
            item.expanded = false;
            item.full = false;
        }
    }

    private static int[] diffCounts(String diff) {
        int add = 0;
        int del = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            if (line.startsWith("+")) add++;
            else if (line.startsWith("-")) del++;
        }
        return new int[]{add, del};
    }

    private SpannableString diffLabel(int add, int del) {
        SpannableString span = new SpannableString(
                (add > 0 ? " +" + add : "") + (del > 0 ? " −" + del : ""));
        if (add > 0) {
            span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.ok)),
                    0, (" +" + add).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (del > 0) {
            int from = (add > 0 ? (" +" + add).length() : 0);
            span.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.danger)),
                    from, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /** The changed lines themselves: added green, removed red, hunk headers dim. */
    private CharSequence diffSpans(String diff) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int colorAdd = ContextCompat.getColor(ctx, R.color.ok);
        int colorDel = ContextCompat.getColor(ctx, R.color.danger);
        int colorCtx = ContextCompat.getColor(ctx, R.color.text_disabled);
        int lines = 0;
        for (String line : diff.split("\n")) {
            if (lines >= 300) {
                sb.append("…");
                break;
            }
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            int start = sb.length();
            sb.append(line);
            int color;
            if (line.startsWith("@@")) color = ContextCompat.getColor(ctx, R.color.text_secondary);
            else if (line.startsWith("+")) color = colorAdd;
            else if (line.startsWith("-")) color = colorDel;
            else color = colorCtx;
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            lines++;
        }
        return sb;
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static String preview(String s, int maxLines) {
        String[] lines = s.split("\n", -1);
        if (lines.length <= maxLines) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** Last {@code n} lines — what a running command is printing right now. */
    private static String tail(String s, int n) {
        if (s == null || s.isEmpty()) return "";
        int idx = s.length();
        for (int count = 0; count < n; count++) {
            int nl = s.lastIndexOf('\n', idx - 1);
            if (nl < 0) return s;
            idx = nl;
        }
        return s.substring(idx + 1);
    }

    private static String cap(String s, int maxLines) {
        return preview(s, maxLines);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static boolean isShell(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")) return true;
        return n.isEmpty() && command != null && !command.isEmpty();
    }

    private static String titleFor(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("todo")) return "Tasks";
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) return "Terminal";
        if (n.equals("read") || n.startsWith("read_")) return "Read";
        if (n.contains("write")) return "Write";
        if (n.contains("edit") || n.contains("patch")) return "Edit";
        if (n.contains("grep") || n.contains("search") || n.contains("find")) return "Search";
        if (n.contains("glob")) return "Glob";
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) return "Web";
        if (n.contains("task") || n.contains("agent") || n.contains("subagent")) return "Agent";
        if (!n.isEmpty()) return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        if (command != null && !command.isEmpty()) return "Terminal";
        return "Tool";
    }

    /** Lucide glyph per tool class (Codex/Grok-style outlined glyphs). */
    private static int iconFor(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) return R.drawable.ic_lc_square_terminal;
        if (n.contains("todo")) return R.drawable.ic_lc_list_checks;
        if (n.contains("glob") || n.contains("find")) return R.drawable.ic_lc_folder_search;
        if (n.equals("read") || n.startsWith("read_") || n.endsWith("read")) {
            return R.drawable.ic_lc_file_text;
        }
        if (n.contains("write")) return R.drawable.ic_lc_file_pen;
        if (n.contains("edit") || n.contains("patch")) return R.drawable.ic_lc_pen_line;
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) {
            return R.drawable.ic_lc_globe;
        }
        if (n.contains("grep") || n.contains("search")) return R.drawable.ic_lc_search;
        if (n.contains("diff")) return R.drawable.ic_lc_file_diff;
        if (n.contains("task") || n.contains("agent") || n.contains("subagent")) {
            return R.drawable.ic_lc_bot;
        }
        if (n.contains("skill")) return R.drawable.ic_lc_sparkles;
        if (command != null && !command.isEmpty()) return R.drawable.ic_lc_square_terminal;
        return R.drawable.ic_lc_wrench;
    }

    // ------------------------------------------------------------ holders

    static final class MessageVH extends RecyclerView.ViewHolder {
        final TextView text;
        final View actions;
        final TextView time;
        final ImageButton btnCopy;
        final ImageButton btnRegen;
        final ImageButton btnShare;

        MessageVH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.text);
            actions = v.findViewById(R.id.actions);
            time = v.findViewById(R.id.time);
            btnCopy = v.findViewById(R.id.btn_copy);
            btnRegen = v.findViewById(R.id.btn_regen);
            btnShare = v.findViewById(R.id.btn_share);
        }
    }

    /** Fragment hook for the action row under a reply. */
    public interface MessageActions {
        /** Regenerate the reply at {@code item} (re-sends the prompt above it). */
        void onRegenerate(ChatItem item);
    }

    private MessageActions messageActions;

    public void setMessageActions(MessageActions actions) {
        this.messageActions = actions;
    }

    static final class ErrorVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView text;

        ErrorVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.error_title);
            text = v.findViewById(R.id.error_text);
        }
    }

    static final class SummaryVH extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageView chevron;

        SummaryVH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.summary_text);
            chevron = v.findViewById(R.id.summary_chevron);
        }
    }

    abstract static class TickingVH extends RecyclerView.ViewHolder {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int frame;
        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                onFrame(ChatAdapter.FRAMES[frame = (frame + 1) % ChatAdapter.FRAMES.length]);
                handler.postDelayed(this, 80);
            }
        };

        TickingVH(@NonNull View v) {
            super(v);
        }

        abstract void onFrame(String frame);

        void startTicker() {
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }

        void stopTicker() {
            handler.removeCallbacks(ticker);
        }
    }

    static final class WorkingVH extends RecyclerView.ViewHolder {
        final TextView text;

        WorkingVH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.working_text);
        }
    }

    static final class NoticeVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView body;
        final TextView retry;

        NoticeVH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.notice_title);
            body = v.findViewById(R.id.notice_body);
            retry = v.findViewById(R.id.notice_retry);
        }
    }

    /** Fragment hook so a notice can offer "Retry" without knowing about items. */
    public interface NoticeAction {
        void onRetry();
    }

    private NoticeAction noticeAction;

    public void setNoticeAction(NoticeAction action) {
        this.noticeAction = action;
    }

    private void bindNotice(NoticeVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.title.setText(item.title);
        h.body.setText(item.text);
        if (noticeAction != null) {
            h.retry.setVisibility(View.VISIBLE);
            h.retry.setOnClickListener(v -> noticeAction.onRetry());
        } else {
            h.retry.setVisibility(View.GONE);
        }
    }

    /** Engine error panel under the reply (left red border + title + message). */
    private void bindError(ErrorVH h, ChatItem item) {
        animateIn(h.itemView, item);
        h.title.setText(TextUtils.isEmpty(item.title)
                ? ctx.getString(R.string.error_panel_title) : item.title);
        h.text.setText(item.text == null ? "" : item.text);
    }

    static final class TodoVH extends RecyclerView.ViewHolder {
        final TextView count;
        final android.widget.LinearLayout rows;

        TodoVH(@NonNull View v) {
            super(v);
            count = v.findViewById(R.id.todo_count);
            rows = v.findViewById(R.id.todo_rows);
        }
    }

    static final class ThoughtVH extends TickingVH {
        final View header;
        final ImageView spinner;
        final TextView title;
        final TextView body;
        final ImageView chevron;
        private final int baseColor;
        private android.animation.ValueAnimator shimmer;
        private android.graphics.LinearGradient gradient;
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();

        ThoughtVH(@NonNull View v) {
            super(v);
            header = v.findViewById(R.id.thought_header);
            spinner = v.findViewById(R.id.thought_spinner);
            title = v.findViewById(R.id.thought_title);
            body = v.findViewById(R.id.thought_body);
            chevron = v.findViewById(R.id.thought_chevron);
            baseColor = title.getCurrentTextColor();
        }

        /** Grok-style shimmer: a soft highlight runs across "Thinking…". */
        void startShimmer() {
            if (shimmer != null) return;
            float d = title.getResources().getDisplayMetrics().density;
            final int w = (int) (150 * d);
            if (gradient == null) {
                gradient = new android.graphics.LinearGradient(0f, 0f, w, 0f,
                        new int[]{0x666B6B6B, 0xFFFFFFFF, 0x666B6B6B},
                        new float[]{0f, 0.5f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
            }
            title.getPaint().setShader(gradient);
            shimmer = android.animation.ValueAnimator.ofFloat(-w, w * 1.4f);
            shimmer.setDuration(1250);
            shimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            shimmer.setInterpolator(new android.view.animation.LinearInterpolator());
            shimmer.addUpdateListener(a -> {
                matrix.setTranslate((float) a.getAnimatedValue(), 0f);
                gradient.setLocalMatrix(matrix);
                title.invalidate();
            });
            shimmer.start();
        }

        void stopShimmer() {
            if (shimmer != null) {
                shimmer.cancel();
                shimmer = null;
            }
            if (title.getPaint().getShader() != null) {
                title.getPaint().setShader(null);
                title.setTextColor(baseColor);
                title.invalidate();
            }
        }

        @Override
        void onFrame(String frame) {
            // soft pulse instead of a braille spinner: the brain breathes while it thinks
            double t = (System.currentTimeMillis() % 1400) / 1400.0;
            float pulse = (float) (0.55 + 0.45 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t)));
            spinner.setAlpha(pulse);
        }
    }

    static final class ToolVH extends TickingVH {
        final View card;
        final View body;
        final TextView spinner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final TextView cmdLine;
        final TextView diff;
        final TextView output;
        final TextView expand;
        final TextView diffBody;
        final ImageView chevron;

        ToolVH(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.tool_card);
            body = v.findViewById(R.id.tool_body);
            spinner = v.findViewById(R.id.tool_spinner);
            icon = v.findViewById(R.id.tool_icon);
            label = v.findViewById(R.id.tool_label);
            name = v.findViewById(R.id.tool_name);
            cmdLine = v.findViewById(R.id.cmd_line);
            diff = v.findViewById(R.id.diff);
            output = v.findViewById(R.id.output);
            expand = v.findViewById(R.id.expand);
            diffBody = v.findViewById(R.id.diff_body);
            chevron = v.findViewById(R.id.chevron);
            spinner.setText(ChatAdapter.FRAMES[0]);
        }

        @Override
        void onFrame(String frame) {
            spinner.setText(frame);
        }
    }
}
