package com.zyvo.code;

/** One bubble in the conversation. */
public final class ChatItem {

    public static final int USER = 0;
    public static final int ASSISTANT = 1;
    public static final int TOOL = 2;
    public static final int ERROR = 3;
    public static final int SUMMARY = 4;
    public static final int THOUGHT = 5;
    public static final int WORKING = 6;
    public static final int TODO = 7;
    /** Amber card for engine problems the user must see (quota, auth, stall). */
    public static final int NOTICE = 8;

    /** One row of the engine's todo list. */
    public static final class Todo {
        public final String content;
        public final String status;

        public Todo(String content, String status) {
            this.content = content == null ? "" : content;
            this.status = status == null ? "pending" : status;
        }
    }

    public java.util.List<Todo> todos = null;

    public int kind;
    public String key = "";
    public String label = "";
    public String text = "";
    /** Full text received from the engine; {@link #shown} chars are revealed so far. */
    public String fullText = "";
    public int shown = 0;
    public String toolName = "";
    public String command = "";
    public String output = "";
    public String status = "";
    public String diff = "";
    /** Live line count while a write/edit streams (shown as +N before the diff lands). */
    public int liveLines = 0;
    public String title = "";
    /** Bookkeeping writes (AGENTS.md / SKILL.md / vault) stay invisible. */
    public boolean hidden = false;
    /** Entrance animation already played for this row. */
    public boolean animated = false;
    public boolean expanded = false;
    public boolean full = false;
    public boolean error = false;
    /** Tool cards belonging to one "Worked for ..." summary share a group. */
    public int group = 0;
    public long timeMs = 0;

    // thinking rows
    public long startMs = 0;
    public long durationMs = 0;
    public boolean streaming = false;

    public static ChatItem user(String text) {
        ChatItem i = new ChatItem();
        i.kind = USER;
        i.text = text;
        i.key = "u-" + System.nanoTime();
        i.timeMs = System.currentTimeMillis();
        return i;
    }

    public static ChatItem assistant(String key) {
        ChatItem i = new ChatItem();
        i.kind = ASSISTANT;
        i.key = key;
        i.label = "ZYVO";
        i.timeMs = System.currentTimeMillis();
        return i;
    }

    public static ChatItem tool(String key) {
        ChatItem i = new ChatItem();
        i.kind = TOOL;
        i.key = key;
        return i;
    }

    public static ChatItem summary(int group) {
        ChatItem i = new ChatItem();
        i.kind = SUMMARY;
        i.key = "sum-" + group;
        i.group = group;
        i.expanded = true;
        i.timeMs = System.currentTimeMillis();
        return i;
    }

    public static ChatItem thought(String key) {
        ChatItem i = new ChatItem();
        i.kind = THOUGHT;
        i.key = key;
        i.streaming = true;
        i.startMs = System.currentTimeMillis();
        return i;
    }

    public static ChatItem working() {
        ChatItem i = new ChatItem();
        i.kind = WORKING;
        i.key = "working";
        i.label = "Working";
        i.timeMs = System.currentTimeMillis();
        return i;
    }

    /** Single live card that the engine's todo list keeps updating. */
    public static ChatItem todoCard() {
        ChatItem i = new ChatItem();
        i.kind = TODO;
        i.key = "todos";
        return i;
    }

    public static ChatItem error(String text) {
        ChatItem i = new ChatItem();
        i.kind = ERROR;
        i.key = "e-" + System.nanoTime();
        i.label = "ERROR";
        i.text = text;
        i.error = true;
        i.timeMs = System.currentTimeMillis();
        return i;
    }

    /** Visible problem card; {@code text} is the body, {@code title} the headline. */
    public static ChatItem notice(String title, String body) {
        ChatItem i = new ChatItem();
        i.kind = NOTICE;
        i.key = "n-" + System.nanoTime();
        i.title = title == null ? "" : title;
        i.text = body == null ? "" : body;
        i.timeMs = System.currentTimeMillis();
        return i;
    }
}
