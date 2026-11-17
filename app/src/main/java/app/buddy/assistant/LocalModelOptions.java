package app.buddy.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Every setting of the model on this phone, and the llama.cpp options they become. Plain Java, so the mapping can be
 * checked anywhere. Each option named here is in the llama.cpp build the app bundles (b12383), checked flag by flag;
 * what that build doesn't have (memory mapping, GPU and NPU offload) is shown on the screen but turned off.
 */
final class LocalModelOptions {
    static final String[] PRESET_NAMES = {"Precise", "Default", "Creative"};
    /** The KV cache types this build takes, from the largest to the smallest. */
    static final String[] CACHE_TYPES = {"f32", "f16", "bf16", "q8_0", "q5_0", "q5_1", "q4_0", "q4_1", "iq4_nl"};
    /** Flags the extra arguments may not carry: they would move the server off this phone or swap the model. */
    private static final String[] BLOCKED = {"--host", "--port", "--api-key", "--api-key-file", "-m", "--model",
            "-hf", "--hf-repo", "--hf-file", "--model-url", "--mmproj", "--mmproj-url", "--log-file"};

    // context and CPU
    int context = 0;                // -c, in tokens; 0 means automatic
    int threads = 0;                // -t; 0 means automatic
    int batch = 128;                // -b
    boolean repack = true;          // --repack 1, or 0 to turn it off
    boolean flashAttention = true;  // -fa on, or off
    String cacheType = "q8_0";      // -ctk and -ctv
    // sampling
    double temperature = 0.3;       // --temp
    double dynamicRange = 0;        // --dynatemp-range; 0 means off
    double topP = 0.9;              // --top-p
    int topK = 20;                  // --top-k
    double minP = 0.05;             // --min-p
    int maxResponse = -1;           // -n; -1 means no limit
    double xtcProbability = 0;      // --xtc-probability; 0 means off
    double xtcThreshold = 0.1;      // --xtc-threshold
    // repetition control
    double repeatPenalty = 1.05;    // --repeat-penalty
    int repeatRange = 512;          // --repeat-last-n
    double dryMultiplier = 0.8;     // --dry-multiplier
    double dryBase = 1.75;          // --dry-base
    int dryAllowedLength = 2;       // --dry-allowed-length
    int dryPenaltyRange = 1024;     // --dry-penalty-last-n
    /** Extra llama.cpp arguments, separated by spaces. */
    String extra = "";

    static LocalModelOptions preset(String name) {
        LocalModelOptions o = new LocalModelOptions();
        o.applyPreset(name);
        return o;
    }

    /** Sets the sampling and repetition values of a preset. The context, CPU and cache choices stay as they are. */
    void applyPreset(String name) {
        if ("Precise".equals(name)) {
            setSampling(0.1, 0, 0.9, 20, 0.05, 1.05);
        } else if ("Creative".equals(name)) {
            setSampling(0.9, 0.5, 0.95, 60, 0.05, 1.03);
        } else {
            setSampling(0.3, 0, 0.9, 20, 0.05, 1.05);
        }
        maxResponse = -1;
        xtcProbability = 0;
        xtcThreshold = 0.1;
        repeatRange = 512;
        dryMultiplier = 0.8;
        dryBase = 1.75;
        dryAllowedLength = 2;
        dryPenaltyRange = 1024;
    }

    private void setSampling(double temp, double dynRange, double p, int k, double min, double repeat) {
        temperature = temp;
        dynamicRange = dynRange;
        topP = p;
        topK = k;
        minP = min;
        repeatPenalty = repeat;
    }

    /** The preset these values are, or "Custom" when they differ from every preset. */
    String matchingPreset() {
        for (String name : PRESET_NAMES) {
            if (samplingKey().equals(preset(name).samplingKey())) return name;
        }
        return "Custom";
    }

    private String samplingKey() {
        return String.format(Locale.ROOT, "%.4f|%.4f|%.4f|%d|%.4f|%d|%.4f|%.4f|%.4f|%d|%.4f|%.4f|%d|%d",
                temperature, dynamicRange, topP, topK, minP, maxResponse, xtcProbability, xtcThreshold,
                repeatPenalty, repeatRange, dryMultiplier, dryBase, dryAllowedLength, dryPenaltyRange);
    }

    /** The first thing wrong with these values, or null when they can be used. cores is the phone's core count. */
    String problem(int cores) {
        if (context != 0 && (context < 8192 || context > 32768)) {
            return "Context length must be 0 (automatic) or between 8192 and 32768.";
        }
        if (threads < 0 || threads > cores) return "N threads must be 0 (automatic) or between 1 and " + cores + ".";
        if (batch < 16 || batch > 2048) return "N batch must be between 16 and 2048.";
        if (temperature < 0 || temperature > 2) return "Temperature must be between 0 and 2.";
        if (dynamicRange < 0 || dynamicRange > 2) return "Dynamic temperature range must be between 0 and 2.";
        if (topP <= 0 || topP > 1) return "Top P must be above 0 and at most 1.";
        if (topK < 0 || topK > 500) return "Top K must be between 0 and 500.";
        if (minP < 0 || minP > 1) return "Min P must be between 0 and 1.";
        if (maxResponse < -1 || maxResponse == 0) return "Max response length must be -1 (no limit) or at least 1.";
        if (xtcProbability < 0 || xtcProbability > 1) return "XTC probability must be between 0 and 1.";
        if (xtcThreshold <= 0 || xtcThreshold > 1) return "XTC threshold must be above 0 and at most 1.";
        if (repeatPenalty < 1 || repeatPenalty > 2) return "Repetition penalty must be between 1 and 2.";
        if (repeatRange < 0 || repeatRange > 8192) return "Repetition penalty range must be between 0 and 8192.";
        if (dryMultiplier < 0 || dryMultiplier > 2) return "DRY multiplier must be between 0 and 2.";
        if (dryBase < 1 || dryBase > 4) return "DRY base must be between 1 and 4.";
        if (dryAllowedLength < 0 || dryAllowedLength > 20) return "DRY allowed length must be between 0 and 20.";
        if (dryPenaltyRange < 0 || dryPenaltyRange > 8192) return "DRY penalty range must be between 0 and 8192.";
        if (!Arrays.asList(CACHE_TYPES).contains(cacheType)) return "Unknown cache type: " + cacheType + ".";
        if (!flashAttention && isQuantised(cacheType)) return "A " + cacheType + " cache needs Flash attention on.";
        return extraProblem(extra);
    }

    /** Caches other than f32, f16 and bf16 are quantised: they save memory, and need Flash attention. */
    static boolean isQuantised(String type) {
        return !("f32".equals(type) || "f16".equals(type) || "bf16".equals(type));
    }

    /** The first blocked flag in the extra arguments, as a message, or null. */
    static String extraProblem(String extra) {
        for (String token : extra.trim().split("\\s+")) {
            for (String blocked : BLOCKED) {
                if (token.equals(blocked) || token.startsWith(blocked + "=")) {
                    return "Remove " + blocked + " from the extra arguments: Buddy sets the server's address, key and model itself.";
                }
            }
        }
        return null;
    }

    /** The llama.cpp options for these settings, with the context and CPU threads already worked out for this phone. */
    List<String> serverFlags(int resolvedContext, int resolvedThreads) {
        List<String> f = new ArrayList<>();
        f.add("-c");
        f.add(String.valueOf(resolvedContext));
        f.add("-t");
        f.add(String.valueOf(resolvedThreads));
        f.add("-b");
        f.add(String.valueOf(batch));
        f.add("--repack");
        f.add(repack ? "1" : "0");
        f.add("-fa");
        f.add(flashAttention ? "on" : "off");
        f.add("-ctk");
        f.add(cacheType);
        f.add("-ctv");
        f.add(cacheType);
        f.add("--temp");
        f.add(num(temperature));
        if (dynamicRange > 0) {
            f.add("--dynatemp-range");
            f.add(num(dynamicRange));
        }
        f.add("--top-p");
        f.add(num(topP));
        f.add("--top-k");
        f.add(String.valueOf(topK));
        f.add("--min-p");
        f.add(num(minP));
        f.add("-n");
        f.add(String.valueOf(maxResponse));
        f.add("--xtc-probability");
        f.add(num(xtcProbability));
        f.add("--xtc-threshold");
        f.add(num(xtcThreshold));
        f.add("--repeat-penalty");
        f.add(num(repeatPenalty));
        f.add("--repeat-last-n");
        f.add(String.valueOf(repeatRange));
        f.add("--dry-multiplier");
        f.add(num(dryMultiplier));
        f.add("--dry-base");
        f.add(num(dryBase));
        f.add("--dry-allowed-length");
        f.add(String.valueOf(dryAllowedLength));
        f.add("--dry-penalty-last-n");
        f.add(String.valueOf(dryPenaltyRange));
        for (String token : extra.trim().split("\\s+")) {
            if (!token.isEmpty()) f.add(token);
        }
        return f;
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** The settings as lines of key=value, for saving. */
    String encode() {
        StringBuilder s = new StringBuilder();
        put(s, "context", context);
        put(s, "threads", threads);
        put(s, "batch", batch);
        put(s, "repack", repack);
        put(s, "flash", flashAttention);
        put(s, "cache", cacheType);
        put(s, "temp", temperature);
        put(s, "dynrange", dynamicRange);
        put(s, "topp", topP);
        put(s, "topk", topK);
        put(s, "minp", minP);
        put(s, "maxresp", maxResponse);
        put(s, "xtcp", xtcProbability);
        put(s, "xtct", xtcThreshold);
        put(s, "reppen", repeatPenalty);
        put(s, "replast", repeatRange);
        put(s, "drymult", dryMultiplier);
        put(s, "drybase", dryBase);
        put(s, "dryallowed", dryAllowedLength);
        put(s, "drypen", dryPenaltyRange);
        put(s, "extra", extra.replace('\n', ' '));
        return s.toString();
    }

    private static void put(StringBuilder s, String key, Object value) {
        s.append(key).append('=').append(value).append('\n');
    }

    /** Reads settings saved by encode(). A line that doesn't parse keeps its default. */
    static LocalModelOptions decode(String text) {
        LocalModelOptions o = new LocalModelOptions();
        if (text == null) return o;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                o.set(line.substring(0, eq), line.substring(eq + 1));
            } catch (RuntimeException ignored) {
                // a value that doesn't parse keeps its default
            }
        }
        return o;
    }

    private void set(String key, String v) {
        switch (key) {
            case "context": context = Integer.parseInt(v); break;
            case "threads": threads = Integer.parseInt(v); break;
            case "batch": batch = Integer.parseInt(v); break;
            case "repack": repack = Boolean.parseBoolean(v); break;
            case "flash": flashAttention = Boolean.parseBoolean(v); break;
            case "cache": cacheType = v; break;
            case "temp": temperature = Double.parseDouble(v); break;
            case "dynrange": dynamicRange = Double.parseDouble(v); break;
            case "topp": topP = Double.parseDouble(v); break;
            case "topk": topK = Integer.parseInt(v); break;
            case "minp": minP = Double.parseDouble(v); break;
            case "maxresp": maxResponse = Integer.parseInt(v); break;
            case "xtcp": xtcProbability = Double.parseDouble(v); break;
            case "xtct": xtcThreshold = Double.parseDouble(v); break;
            case "reppen": repeatPenalty = Double.parseDouble(v); break;
            case "replast": repeatRange = Integer.parseInt(v); break;
            case "drymult": dryMultiplier = Double.parseDouble(v); break;
            case "drybase": dryBase = Double.parseDouble(v); break;
            case "dryallowed": dryAllowedLength = Integer.parseInt(v); break;
            case "drypen": dryPenaltyRange = Integer.parseInt(v); break;
            case "extra": extra = v; break;
            default: break;
        }
    }
}
