package com.zyvo.code;

import com.termux.BuildConfig;

import android.content.Context;
import android.content.SharedPreferences;

/** Small typed wrapper over SharedPreferences. */
public final class Prefs {

    private static final String FILE = "zyvo_code_prefs";

    /** The built-in zyvo lane (same model the CLI defaults to). */
    public static final String LANE_PROVIDER = "opencode";
    public static final String LANE_MODEL = "big-pickle";

    /** Free default lane (all opencode models are free for this build). */
    public static final String DEFAULT_PROVIDER = "opencode";
    public static final String DEFAULT_MODEL = "big-pickle";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean onboardingDone() {
        return sp.getBoolean("onboarding_done", false);
    }

    public void setOnboardingDone() {
        sp.edit().putBoolean("onboarding_done", true).apply();
    }

    public String providerId() {
        String v = sp.getString("provider_id", null);
        if (v != null && !v.isEmpty()) return v;
        return hasLaneKey() ? LANE_PROVIDER : DEFAULT_PROVIDER;
    }

    public String modelId() {
        String v = sp.getString("model_id", null);
        if (v != null && !v.isEmpty()) return v;
        return hasLaneKey() ? LANE_MODEL : DEFAULT_MODEL;
    }

    /** True when this build ships the zyvo lane key. */
    public boolean hasLaneKey() {
        return !opencodeKey().isEmpty();
    }

    /** Key for the built-in opencode lane, injected at build time. */
    public String opencodeKey() {
        String baked = BuildConfig.ZYVO_OPENCODE_KEY;
        return baked == null ? "" : baked.trim();
    }

    public void setModel(String providerId, String modelId) {
        sp.edit().putString("provider_id", providerId).putString("model_id", modelId).apply();
    }

    /** Key the user pasted, or the one baked in at build time. */
    public String gatewayKey() {
        String v = sp.getString("gateway_key", null);
        if (v != null && !v.trim().isEmpty()) return v.trim();
        String baked = BuildConfig.ZYVO_GATEWAY_KEY;
        return baked == null ? "" : baked.trim();
    }

    public void setGatewayKey(String key) {
        sp.edit().putString("gateway_key", key == null ? "" : key.trim()).apply();
    }

    public String systemPrompt() {
        String v = sp.getString("system_prompt", null);
        return v == null ? "" : v;
    }

    public void setSystemPrompt(String prompt) {
        sp.edit().putString("system_prompt", prompt == null ? "" : prompt).apply();
    }

    public String lastSessionId() {
        return sp.getString("last_session", "");
    }

    public void setLastSessionId(String id) {
        sp.edit().putString("last_session", id == null ? "" : id).apply();
    }

    /** Primary agent for prompts: "build" (full tools) or "plan" (read-only). */
    public String agentMode() {
        String v = sp.getString("agent_mode", null);
        return v == null || v.isEmpty() ? "build" : v;
    }

    public void setAgentMode(String mode) {
        sp.edit().putString("agent_mode", mode == null ? "build" : mode).apply();
    }

    /** Reasoning effort ("low"/"medium"/"high"/…) chosen for one model. */
    public String variantOf(String modelId) {
        return sp.getString("variant_" + modelId, "");
    }

    public void setVariant(String modelId, String variant) {
        sp.edit().putString("variant_" + modelId, variant == null ? "" : variant).apply();
    }

    public String serverPassword() {
        return sp.getString("server_password", "");
    }
}
