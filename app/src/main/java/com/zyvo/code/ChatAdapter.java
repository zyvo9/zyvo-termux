package com.zyvo.code;

import com.termux.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

    static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final Context ctx;
    private final List<ChatItem> items;
    private final Markwon markwon;
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
        return new MessageVH(inf.inflate(R.layout.item_message_assistant, parent, false));
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
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ThoughtVH) ((ThoughtVH) holder).stopTicker();
        if (holder instanceof ToolVH) ((ToolVH) holder).stopTicker();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ------------------------------------------------------------ messages

    private void bindMessage(MessageVH h, ChatItem item) {
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
        h.time.setText(revealing ? "" : stamp(item.timeMs));

        if (hasText) {
            h.btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("zyvo", item.text));
                    UiUtil.toast(ctx, ctx.getString(R.string.copied));
                }
            });
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

    // ------------------------------------------------------------- thought

    private void bindWorking(WorkingVH h, ChatItem item) {
        h.text.setText(TextUtils.isEmpty(item.text)
                ? ctx.getString(R.string.working) : item.text);
    }

    private void bindThought(ThoughtVH h, ChatItem item, int position) {
        boolean hasTitle = !TextUtils.isEmpty(item.title);
        if (item.streaming) {
            h.title.setText(hasTitle
                    ? ctx.getString(R.string.thinking_of, item.title)
                    : ctx.getString(R.string.thinking));
            h.spinner.setVisibility(View.VISIBLE);
            h.chevron.setVisibility(View.GONE);
            h.startTicker();
        } else {
            h.stopTicker();
            h.spinner.setVisibility(View.VISIBLE);
            h.spinner.setAlpha(0.75f);
            StringBuilder sb = new StringBuilder(ctx.getString(R.string.thought));
            if (hasTitle) sb.append(": ").append(item.title);
            String d = fmtDuration(item.durationMs);
            if (!d.isEmpty()) sb.append(" · ").append(d);
            h.title.setText(sb.toString());
            h.chevron.setVisibility(View.VISIBLE);
            h.chevron.setRotation(item.expanded ? 180f : 0f);
        }

        boolean hasBody = !TextUtils.isEmpty(item.text);
        boolean showBody = hasBody && item.expanded;
        h.body.setVisibility(showBody ? View.VISIBLE : View.GONE);
        if (showBody) markwon.setMarkdown(h.body, item.text);

        boolean toggleable = !item.streaming && hasBody;
        h.header.setOnClickListener(toggleable ? v -> {
            item.expanded = !item.expanded;
            notifyItemChanged(position);
        } : null);
        h.header.setClickable(toggleable);
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

        h.spinner.setVisibility(running ? View.VISIBLE : View.GONE);
        h.icon.setVisibility(running ? View.GONE : View.VISIBLE);
        if (running) h.startTicker();
        else h.stopTicker();

        h.icon.setImageResource(iconFor(item.toolName, item.command));
        h.icon.setColorFilter(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_disabled));

        String clean = cleanCommand(item.command);
        h.name.setText(titleFor(item.toolName, clean));
        h.name.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_secondary));

        String detail = !TextUtils.isEmpty(item.label) ? item.label : clean;
        if (TextUtils.isEmpty(detail)) detail = firstLine(item.output);
        h.command.setText(detail == null ? "" : detail);
        h.command.setVisibility(TextUtils.isEmpty(detail) ? View.GONE : View.VISIBLE);
        h.command.setTextColor(ContextCompat.getColor(ctx,
                failed ? R.color.danger : R.color.text_disabled));

        if (!TextUtils.isEmpty(item.diff)) {
            bindDiff(h, item.diff);
        } else if (item.liveLines > 0) {
            h.diff.setText("+" + item.liveLines);
            h.diff.setTextColor(ContextCompat.getColor(ctx, R.color.ok));
            h.diff.setVisibility(View.VISIBLE);
        } else {
            h.diff.setVisibility(View.GONE);
        }

        boolean hasOutput = !TextUtils.isEmpty(item.output);
        boolean showOutput = hasOutput && (item.expanded || failed || running);
        int maxLines = isShell(item.toolName, item.command) ? 10 : 3;
        boolean overflow = hasOutput && countLines(item.output) > maxLines;

        h.output.setVisibility(showOutput ? View.VISIBLE : View.GONE);
        if (showOutput) {
            h.output.setText(item.full ? item.output : preview(item.output, maxLines));
            h.output.setMaxLines(item.full ? 400 : maxLines);
            h.output.setEllipsize(item.full ? null : TextUtils.TruncateAt.END);
        }

        h.expand.setVisibility(showOutput && overflow ? View.VISIBLE : View.GONE);
        h.expand.setText(ctx.getString(item.full ? R.string.tap_collapse : R.string.tap_expand));
        h.expand.setOnClickListener(v -> {
            item.full = !item.full;
            notifyItemChanged(position);
        });

        h.card.setOnClickListener(v -> {
            item.expanded = !item.expanded;
            item.full = false;
            notifyItemChanged(position);
        });
    }

    private void bindDiff(ToolVH h, String diff) {
        if (TextUtils.isEmpty(diff)) {
            h.diff.setVisibility(View.GONE);
            return;
        }
        int add = 0;
        int del = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---")) continue;
            if (line.startsWith("+")) add++;
            else if (line.startsWith("-")) del++;
        }
        if (add == 0 && del == 0) {
            h.diff.setVisibility(View.GONE);
            return;
        }
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
        h.diff.setText(span);
        h.diff.setVisibility(View.VISIBLE);
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

    /** Lucide glyph per tool class (extracted from ZCode's icon set, ISC). */
    private static int iconFor(String tool, String command) {
        String n = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (n.contains("bash") || n.contains("shell") || n.contains("exec")
                || n.contains("terminal")) return R.drawable.ic_lc_terminal;
        if (n.contains("todo")) return R.drawable.ic_lc_list_checks;
        if (n.contains("glob") || n.contains("find")) return R.drawable.ic_lc_folder_search;
        if (n.equals("read") || n.startsWith("read_") || n.endsWith("read")) {
            return R.drawable.ic_lc_file_text;
        }
        if (n.contains("edit") || n.contains("write") || n.contains("patch")) {
            return R.drawable.ic_lc_pen_line;
        }
        if (n.contains("web") || n.contains("fetch") || n.contains("http")) {
            return R.drawable.ic_lc_globe;
        }
        if (n.contains("grep") || n.contains("search")) return R.drawable.ic_lc_search;
        if (n.contains("diff")) return R.drawable.ic_lc_file_diff;
        if (n.contains("task") || n.contains("agent") || n.contains("subagent")) {
            return R.drawable.ic_lc_bot;
        }
        if (n.contains("skill")) return R.drawable.ic_lc_sparkles;
        if (command != null && !command.isEmpty()) return R.drawable.ic_lc_terminal;
        return R.drawable.ic_lc_wrench;
    }

    // ------------------------------------------------------------ holders

    static final class MessageVH extends RecyclerView.ViewHolder {
        final TextView text;
        final View actions;
        final TextView time;
        final ImageButton btnCopy;

        MessageVH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.text);
            actions = v.findViewById(R.id.actions);
            time = v.findViewById(R.id.time);
            btnCopy = v.findViewById(R.id.btn_copy);
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

    static final class ThoughtVH extends TickingVH {
        final View header;
        final ImageView spinner;
        final TextView title;
        final TextView body;
        final ImageView chevron;

        ThoughtVH(@NonNull View v) {
            super(v);
            header = v.findViewById(R.id.thought_header);
            spinner = v.findViewById(R.id.thought_spinner);
            title = v.findViewById(R.id.thought_title);
            body = v.findViewById(R.id.thought_body);
            chevron = v.findViewById(R.id.thought_chevron);
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
        final TextView spinner;
        final ImageView icon;
        final TextView name;
        final TextView command;
        final TextView diff;
        final TextView output;
        final TextView expand;

        ToolVH(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.tool_card);
            spinner = v.findViewById(R.id.tool_spinner);
            icon = v.findViewById(R.id.tool_icon);
            name = v.findViewById(R.id.tool_name);
            command = v.findViewById(R.id.command);
            diff = v.findViewById(R.id.diff);
            output = v.findViewById(R.id.output);
            expand = v.findViewById(R.id.expand);
            spinner.setText(ChatAdapter.FRAMES[0]);
        }

        @Override
        void onFrame(String frame) {
            spinner.setText(frame);
        }
    }
}
