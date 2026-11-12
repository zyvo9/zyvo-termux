package com.zyvo.code;

import com.termux.R;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** ChatGPT-style chat history list for the session drawer. */
public final class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {

    public static final class Session {
        public final String id;
        public final String title;
        public final long updated;

        public Session(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }
    }

    public interface Listener {
        void onSessionClick(Session session);

        void onSessionLongClick(Session session);
    }

    private final Context ctx;
    private final List<Session> items = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MMM d · h:mm a", Locale.getDefault());
    private String currentId = "";

    public SessionAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setItems(List<Session> sessions) {
        items.clear();
        if (sessions != null) items.addAll(sessions);
        notifyDataSetChanged();
    }

    public void setCurrent(String id) {
        currentId = id == null ? "" : id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_session, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Session s = items.get(position);
        h.title.setText(s.title);
        h.meta.setText(fmt.format(new Date(s.updated > 0 ? s.updated : System.currentTimeMillis())));
        boolean current = s.id.equals(currentId);
        h.itemView.setSelected(current);
        h.title.setTextColor(h.itemView.getResources().getColor(
                current ? R.color.text_primary : R.color.text_secondary));
        h.itemView.setOnClickListener(v -> listener.onSessionClick(s));
        h.itemView.setOnLongClickListener(v -> {
            listener.onSessionLongClick(s);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
        }
    }
}
