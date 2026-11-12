package com.zyvo.code;

/**
 * The opencode provider's free model catalogue (from models.dev, cost 0 in and
 * out). Kept in the APK so the picker works before the network is up.
 * {modelId, displayName, contextTokens}
 */
public final class Models {

    /** Built-in zyvo lane: provider opencode, models.dev entry. */
    public static final String LANE_NAME = "Big Pickle";
    public static final String LANE_CONTEXT = "200000";

    public static final String[][] FREE = {
            {"big-pickle", "Big Pickle", "200000"},
            {"beam-free", "Beam Free", "1048576"},
            {"cat-jam-free", "Cat Jam Free", "1048576"},
            {"crouton-alpha-free", "Crouton Alpha Free", "1048576"},
            {"deepseek-v4-flash-free", "DeepSeek V4 Flash Free", "200000"},
            {"deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash Free", "200000"},
            {"exo-free", "Exo Free", "1048576"},
            {"fledge-alpha-free", "Fledge Alpha Free", "1048576"},
            {"flint-40b-free", "Flint 40B Free", "262144"},
            {"glm-4.7-free", "GLM-4.7 Free", "204800"},
            {"glm-5-free", "GLM-5 Free", "204800"},
            {"glm-5.3-flash-free", "GLM-5.3-Flash Free", "200000"},
            {"grok-code", "Grok Code Fast 1", "256000"},
            {"hy3-free", "Hy3 Free", "190000"},
            {"hy3-preview-free", "Hy3 preview Free", "256000"},
            {"iceman-free", "Iceman Free", "262144"},
            {"inkling-2-small-free", "Inkling 2 Small Free", "1048576"},
            {"kimi-k2.5-free", "Kimi K2.5 Free", "262144"},
            {"laguna-s-2.1-free", "Laguna S 2.1 Free", "256000"},
            {"ling-2.6-flash-free", "Ling 2.6 Flash Free", "262100"},
            {"ling-3.0-flash-fin-free", "Ling 3.0 Flash Fin Free", "262144"},
            {"ling-3.1-flash-free", "Ling 3.1 Flash Free", "262144"},
            {"ling-3.0-flash-free", "Ling-3.0-flash Free", "262144"},
            {"ling-3.0-tiny-free", "Ling-3.0-tiny Free", "262144"},
            {"ll-alpha-free", "LL Alpha Free", "262144"},
            {"longcat-2.5-free", "LongCat 2.5 Free", "1000000"},
            {"longcat-2.5-preview-free", "LongCat 2.5 Preview Free", "1000000"},
            {"longcat-2.0-free", "LongCat-2.0 Free", "1000000"},
            {"mimo-v2-flash-free", "MiMo V2 Flash Free", "262144"},
            {"mimo-v2-omni-free", "MiMo V2 Omni Free", "262144"},
            {"mimo-v2-pro-free", "MiMo V2 Pro Free", "1048576"},
            {"mimo-v2.5-free", "MiMo V2.5 Free", "200000"},
            {"mimo-v2.6-flash-free", "MiMo-V2.6-Flash Free", "200000"},
            {"minimax-m3.1-flash-free", "MiniMax M3.1 Flash Free", "1000000"},
            {"minimax-m2.1-free", "MiniMax-M2.1 Free", "204800"},
            {"minimax-m2.5-free", "MiniMax-M2.5 Free", "204800"},
            {"minimax-m3-free", "MiniMax-M3 Free", "200000"},
            {"muse-spark-1.2-contributor-free", "Muse Spark 1.2 Free", "1048576"},
            {"muse-spark-1.3-contributor-free", "Muse Spark 1.3 Free", "1048576"},
            {"nemotron-3-super-free", "Nemotron 3 Super Free", "204800"},
            {"nemotron-3-ultra-free", "Nemotron 3 Ultra Free", "1000000"},
            {"nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free", "262144"},
            {"nemotron-3.5-super-vl-free", "Nemotron 3.5 Super VL Free", "1048576"},
            {"north-mini-code-free", "North Mini Code Free", "256000"},
            {"x-preview-f-free", "Ox Alpha Free (Unlimited)", "1000000"},
            {"pulse-alpha-free", "Pulse Alpha Free", "1048576"},
            {"qwen3.6-plus-free", "Qwen3.6 Plus Free", "262144"},
            {"ring-2.6-1t-free", "Ring 2.6 1T Free", "262000"},
            {"space-bunny-free", "Space Bunny Free", "1048576"},
            {"step-5-preview-free", "Step 5 Preview Free", "1000000"},
            {"trinity-large-preview-free", "Trinity Large Preview", "131072"},
    };

    public static String nameOf(String modelId) {
        for (String[] m : FREE) {
            if (m[0].equals(modelId)) return m[1];
        }
        int slash = modelId.lastIndexOf('/');
        return slash >= 0 && slash + 1 < modelId.length() ? modelId.substring(slash + 1) : modelId;
    }

    public static String contextOf(String modelId) {
        for (String[] m : FREE) {
            if (m[0].equals(modelId)) return m[2];
        }
        return LANE_CONTEXT;
    }

    private Models() {
    }
}
