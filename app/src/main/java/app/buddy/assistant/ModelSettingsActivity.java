package app.buddy.assistant;

import static app.buddy.assistant.Ui.*;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings → API keys → Model on this phone → Advanced settings: the llama.cpp options the on-phone model takes in this
 * build, in plain words. The options this build can't use are shown, but turned off.
 */
public class ModelSettingsActivity extends Screen {
    private static final int BAD_COLOR = 0xFFC62828, WARN_COLOR = 0xFFB26A00, GOOD_COLOR = 0xFF2E7D32;

    /** The choices the rows and chips change. Save reads the text boxes as well. */
    private LocalModelOptions opts;
    /** The text boxes by key. */
    private final Map<String, EditText> fields = new HashMap<>();
    private long modelBytes;
    private LinearLayout body, presetHolder, cacheHolder;
    private TextView fitTitle, fitDetail, presetNote;

    /** Checks the fit and the preset again whenever a box changes. */
    private final TextWatcher live = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        opts = LocalModel.options(this);
        modelBytes = LocalModel.file(this) == null ? 0 : LocalModel.file(this).length();

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout heads = column(this);
        heads.setPaddingRelative(dp(this, 6), 0, 0, 0);
        heads.addView(title(this, "Advanced settings", 18));
        heads.addView(text(this, "Sampling, performance and memory for the model on this phone", 12, MUTED));
        top.addView(heads, weight1());
        root.addView(top, full());

        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(frame);
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(fitCard(), full());

        body.addView(sectionLabel(this, "PRESETS"), gap(18));
        LinearLayout presets = card();
        presetHolder = column(this);
        presets.addView(presetHolder);
        presetNote = text(this, "", 12, MUTED);
        presets.addView(presetNote, gap(8));
        body.addView(presets, full());

        body.addView(sectionLabel(this, "SAMPLING"), gap(18));
        LinearLayout sampling = card();
        sampling.addView(field("Temperature", "temp", fmt(opts.temperature), true, false));
        sampling.addView(field("Dynamic temperature range", "dynrange", fmt(opts.dynamicRange), true, false));
        sampling.addView(field("Top P", "topp", fmt(opts.topP), true, false));
        sampling.addView(field("Top K", "topk", String.valueOf(opts.topK), false, false));
        sampling.addView(field("Min P", "minp", fmt(opts.minP), true, false));
        sampling.addView(field("Context length (0 = automatic)", "context", String.valueOf(opts.context), false, false));
        sampling.addView(field("Max response length (-1 = no limit)", "maxresp", String.valueOf(opts.maxResponse), false, true));
        sampling.addView(field("XTC probability", "xtcp", fmt(opts.xtcProbability), true, false));
        sampling.addView(field("XTC threshold", "xtct", fmt(opts.xtcThreshold), true, false));
        body.addView(sampling, full());

        body.addView(sectionLabel(this, "REPETITION CONTROL"), gap(18));
        LinearLayout repetition = card();
        repetition.addView(field("Repetition penalty", "reppen", fmt(opts.repeatPenalty), true, false));
        repetition.addView(field("Repetition penalty range", "replast", String.valueOf(opts.repeatRange), false, false));
        repetition.addView(field("DRY multiplier", "drymult", fmt(opts.dryMultiplier), true, false));
        repetition.addView(field("DRY base", "drybase", fmt(opts.dryBase), true, false));
        repetition.addView(field("DRY allowed length", "dryallowed", String.valueOf(opts.dryAllowedLength), false, false));
        repetition.addView(field("DRY penalty range", "drypen", String.valueOf(opts.dryPenaltyRange), false, false));
        body.addView(repetition, full());

        body.addView(sectionLabel(this, "CPU AND MEMORY"), gap(18));
        LinearLayout cpu = card();
        cpu.addView(field("N threads (0 = automatic)", "threads", String.valueOf(opts.threads), false, false));
        cpu.addView(field("N batch (smaller uses less memory)", "batch", String.valueOf(opts.batch), false, false));
        cpu.addView(switchRow(this, "CPU repack", "Rearranges the model's weights for faster CPU math", opts.repack,
                on -> opts.repack = on));
        cpu.addView(switchRow(this, "Flash attention", "Needed for a quantised cache; saves memory on long chats",
                opts.flashAttention, on -> {
                    opts.flashAttention = on;
                    refresh();
                }));
        LinearLayout noMmap = switchRow(this, "No MMap", "Not available in this build", false, on -> {
        });
        noMmap.setOnClickListener(null);
        noMmap.setAlpha(0.38f);
        cpu.addView(noMmap);
        body.addView(cpu, full());

        body.addView(sectionLabel(this, "KV CACHE QUANTISATION"), gap(18));
        LinearLayout cache = card();
        cacheHolder = column(this);
        cache.addView(cacheHolder);
        cache.addView(text(this, "Smaller types save memory. Any type except f16, f32 and bf16 needs Flash attention on.",
                12, MUTED), gap(8));
        body.addView(cache, full());
        renderCache();

        body.addView(sectionLabel(this, "HARDWARE ACCELERATION"), gap(18));
        LinearLayout hardware = card();
        String[][] devices = {{"none", "None"}, {"opencl", "OpenCL (GPU)"}, {"vulkan", "Vulkan (GPU)"}, {"npu", "Qualcomm (NPU)"}};
        Set<String> off = new HashSet<>(Arrays.asList("opencl", "vulkan", "npu"));
        hardware.addView(segmented(this, devices, "none", false, device -> {
        }, off, device -> Toast.makeText(this,
                "Not in this build: the model engine runs on the CPU only.", Toast.LENGTH_LONG).show()));
        hardware.addView(fieldRow("Number of layers to offload", "layers", "0", false, false, false), gap(4));
        hardware.addView(text(this, "This build runs on the CPU only, so GPU and NPU are shown but turned off.", 12, MUTED),
                gap(8));
        body.addView(hardware, full());

        body.addView(sectionLabel(this, "LLAMA.CPP EXTRA ARGUMENTS"), gap(18));
        LinearLayout extraCard = card();
        EditText extra = new EditText(this);
        extra.setText(opts.extra);
        extra.setHint("For example: --some-flag 1");
        extra.setHintTextColor(MUTED);
        extra.setTextColor(TEXT);
        extra.setTextSize(14);
        extra.setMinLines(3);
        extra.setGravity(Gravity.TOP | Gravity.START);
        extra.setBackground(rounded(this, CARD2, 10));
        extra.setPaddingRelative(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        fields.put("extra", extra);
        extra.addTextChangedListener(live);
        extraCard.addView(extra);
        extraCard.addView(text(this, "Added to the end of the server command. Buddy sets the address, key and model file itself.",
                12, MUTED), gap(8));
        body.addView(extraCard, full());

        Button saveButton = button(this, "Save", true);
        saveButton.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        saveParams.topMargin = dp(this, 20);
        body.addView(saveButton, saveParams);
        Button resetButton = button(this, "Reset to recommended", false);
        resetButton.setOnClickListener(v -> reset());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        resetParams.topMargin = dp(this, 8);
        body.addView(resetButton, resetParams);

        renderPresets();
        refresh();
    }

    private LinearLayout card() {
        LinearLayout c = column(this);
        c.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        c.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        return c;
    }

    private LinearLayout fitCard() {
        LinearLayout c = card();
        fitTitle = title(this, "", 15);
        fitDetail = text(this, "", 12, MUTED);
        c.addView(fitTitle);
        c.addView(fitDetail, gap(4));
        return c;
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(this, topDp);
        return p;
    }

    /** A labelled box for one value. decimal and signed set the keyboard. */
    private LinearLayout field(String label, String key, String value, boolean decimal, boolean signed) {
        return fieldRow(label, key, value, decimal, signed, true);
    }

    private LinearLayout fieldRow(String label, String key, String value, boolean decimal, boolean signed, boolean enabled) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(0, dp(this, 6), 0, dp(this, 6));
        r.addView(title(this, label, 14), weight1());
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setBackground(rounded(this, CARD2, 10));
        e.setPaddingRelative(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
        int kind = InputType.TYPE_CLASS_NUMBER;
        if (decimal) kind |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) kind |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        e.setInputType(kind);
        e.setEnabled(enabled);
        if (!enabled) e.setAlpha(0.38f);
        fields.put(key, e);
        e.addTextChangedListener(live);
        r.addView(e, new LinearLayout.LayoutParams(dp(this, 112), LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void renderPresets() {
        presetHolder.removeAllViews();
        String[][] options = {{"Precise", "Precise"}, {"Default", "Default"}, {"Creative", "Creative"}};
        presetHolder.addView(segmented(this, options, presetSelected(), false, name -> {
            opts.applyPreset(name);
            fillSampling();
            renderPresets();
            refresh();
        }));
    }

    private void renderCache() {
        cacheHolder.removeAllViews();
        String[] types = LocalModelOptions.CACHE_TYPES;
        String[][] options = new String[types.length][];
        for (int i = 0; i < types.length; i++) options[i] = new String[]{types[i], types[i]};
        cacheHolder.addView(segmented(this, options, opts.cacheType, true, type -> {
            opts.cacheType = type;
            renderCache();
            refresh();
        }));
    }

    /** The preset the boxes match right now, or "" when none does. */
    private String presetSelected() {
        try {
            String name = collect().matchingPreset();
            return "Custom".equals(name) ? "" : name;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Puts the sampling and repetition values of the working choices into their boxes. */
    private void fillSampling() {
        set("temp", fmt(opts.temperature));
        set("dynrange", fmt(opts.dynamicRange));
        set("topp", fmt(opts.topP));
        set("topk", String.valueOf(opts.topK));
        set("minp", fmt(opts.minP));
        set("maxresp", String.valueOf(opts.maxResponse));
        set("xtcp", fmt(opts.xtcProbability));
        set("xtct", fmt(opts.xtcThreshold));
        set("reppen", fmt(opts.repeatPenalty));
        set("replast", String.valueOf(opts.repeatRange));
        set("drymult", fmt(opts.dryMultiplier));
        set("drybase", fmt(opts.dryBase));
        set("dryallowed", String.valueOf(opts.dryAllowedLength));
        set("drypen", String.valueOf(opts.dryPenaltyRange));
    }

    /** The fit, the problem if there is one, and the preset name, from whatever the boxes show now. */
    private void refresh() {
        if (fitTitle == null || presetNote == null || fields.isEmpty()) return;
        LocalModelOptions o;
        try {
            o = collect();
        } catch (IllegalArgumentException e) {
            fitTitle.setText("Check a value");
            fitTitle.setTextColor(BAD_COLOR);
            fitDetail.setText(e.getMessage());
            presetNote.setText("");
            return;
        }
        String problem = o.problem(cores());
        boolean f16 = LocalModel.isF16Cache(o.cacheType);
        long total = LocalModel.totalRam(this);
        long free = LocalModel.freeRam(this);
        int context = o.context > 0 ? Math.max(LocalModelTuning.MIN_CONTEXT, o.context)
                : LocalModelTuning.automatic(modelBytes, total, f16);
        int level = LocalModelTuning.level(modelBytes, context, f16, free, total);
        if (problem != null) {
            fitTitle.setText("Check this setting");
            fitTitle.setTextColor(BAD_COLOR);
            fitDetail.setText(problem);
        } else {
            fitTitle.setText(level == LocalModelTuning.GOOD ? "Should run on this phone"
                    : level == LocalModelTuning.TIGHT ? "Tight: it may be slow, or Android may stop it"
                    : "Too big for this phone right now");
            fitTitle.setTextColor(level == LocalModelTuning.GOOD ? GOOD_COLOR
                    : level == LocalModelTuning.TIGHT ? WARN_COLOR : BAD_COLOR);
            fitDetail.setText(String.format(Locale.US,
                    "Chat memory %dk. Needs about %.1f GB; %.1f GB is free now, of %.1f GB on this phone.",
                    context / 1024, LocalModelTuning.needBytes(modelBytes, context, f16) / 1e9, free / 1e9, total / 1e9));
        }
        presetNote.setText(presetText(o.matchingPreset()));
    }

    private static String presetText(String name) {
        switch (name) {
            case "Precise":
                return "Using Precise: steadier, less varied answers.";
            case "Default":
                return "Using Default: balanced, and tuned for the agent's tool calls.";
            case "Creative":
                return "Using Creative: more varied wording, and less reliable for tool calls.";
            default:
                return "Custom values. Pick a preset to start again.";
        }
    }

    private String read(String key) {
        EditText e = fields.get(key);
        return e == null ? "" : e.getText().toString().trim();
    }

    private double dbl(String key, String name) {
        try {
            return Double.parseDouble(read(key).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }

    private int whole(String key, String name) {
        try {
            return Integer.parseInt(read(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number.");
        }
    }

    private void set(String key, String value) {
        EditText e = fields.get(key);
        if (e != null) e.setText(value);
    }

    /** Everything on the screen as settings. Throws IllegalArgumentException naming the first box that isn't a number. */
    private LocalModelOptions collect() {
        LocalModelOptions o = new LocalModelOptions();
        o.context = whole("context", "Context length");
        o.threads = whole("threads", "N threads");
        o.batch = whole("batch", "N batch");
        o.repack = opts.repack;
        o.flashAttention = opts.flashAttention;
        o.cacheType = opts.cacheType;
        o.temperature = dbl("temp", "Temperature");
        o.dynamicRange = dbl("dynrange", "Dynamic temperature range");
        o.topP = dbl("topp", "Top P");
        o.topK = whole("topk", "Top K");
        o.minP = dbl("minp", "Min P");
        o.maxResponse = whole("maxresp", "Max response length");
        o.xtcProbability = dbl("xtcp", "XTC probability");
        o.xtcThreshold = dbl("xtct", "XTC threshold");
        o.repeatPenalty = dbl("reppen", "Repetition penalty");
        o.repeatRange = whole("replast", "Repetition penalty range");
        o.dryMultiplier = dbl("drymult", "DRY multiplier");
        o.dryBase = dbl("drybase", "DRY base");
        o.dryAllowedLength = whole("dryallowed", "DRY allowed length");
        o.dryPenaltyRange = whole("drypen", "DRY penalty range");
        o.extra = read("extra");
        return o;
    }

    private void save() {
        LocalModelOptions chosen;
        try {
            chosen = collect();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        String problem = chosen.problem(cores());
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            return;
        }
        final android.content.Context app = getApplicationContext();
        Prefs.setLocalOptions(app, chosen.encode());
        new Thread(() -> {
            String msg;
            try {
                LocalModel.applySettings(app);
                msg = "Saved. The model starts with these settings next time you chat.";
            } catch (Exception e) {
                msg = "Saved, but Buddy couldn't update the model: " + e.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "model-settings").start();
    }

    private void reset() {
        opts = new LocalModelOptions();
        render();
        Toast.makeText(this, "Back to the recommended values. Tap Save to keep them.", Toast.LENGTH_LONG).show();
    }

    private static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
