package app.buddy.assistant;

/**
 * The memory side of the model on this phone's settings (Settings → API keys → Model on this phone → Model settings).
 * Plain Java with no Android in it, so the numbers can be checked anywhere. They are estimates for a model of about
 * 4 billion parameters with 36 layers and 8 key-value heads, like Qwen3-VL-4B: a q8 cache takes about 80 MB per 1,000
 * tokens, a full-quality (f16) one about 150 MB. Other models differ; the server's own log says what it really takes.
 */
final class LocalModelTuning {
    /** The phone tools alone take about 7k tokens (README, Local models), so a smaller chat memory can't hold a chat. */
    static final int MIN_CONTEXT = 8192;
    /** The sizes offered, smallest first. The largest is the size every version before these settings used. */
    static final int[] CONTEXTS = {8192, 12288, 16384, 24576};
    /** Room left for the engine's own buffers and the app's, a rough margin. */
    static final long MARGIN = 300_000_000L;
    /** How a choice runs on this phone: GOOD fits the RAM free now, TIGHT fits the phone once other apps let go, TOO_BIG does not. */
    static final int GOOD = 0, TIGHT = 1, TOO_BIG = 2;
    private static final long Q8_PER_THOUSAND = 80_000_000L;
    private static final long F16_PER_THOUSAND = 150_000_000L;

    private LocalModelTuning() {
    }

    /** The memory the chat memory's cache takes, in bytes. */
    static long cacheBytes(int context, boolean f16) {
        return (long) context * (f16 ? F16_PER_THOUSAND : Q8_PER_THOUSAND) / 1000;
    }

    /** What the server needs in memory: the model file, the cache for the chat memory, and the margin. */
    static long needBytes(long modelBytes, int context, boolean f16) {
        return modelBytes + cacheBytes(context, f16) + MARGIN;
    }

    /**
     * The largest offered size that needs at most half of the phone's RAM, so Android keeps room for its own apps
     * (a model that starts swapping is far slower than a smaller one). MIN_CONTEXT when even that is too much.
     */
    static int automatic(long modelBytes, long totalRam, boolean f16) {
        int best = MIN_CONTEXT;
        for (int c : CONTEXTS) {
            if (needBytes(modelBytes, c, f16) <= totalRam / 2) best = c;
        }
        return best;
    }

    /** Whether the size fits in the RAM that is free right now. */
    static boolean fits(long modelBytes, int context, boolean f16, long freeRam) {
        return needBytes(modelBytes, context, f16) <= freeRam;
    }

    /** GOOD when it fits the RAM free now, TIGHT when it fits half the phone, TOO_BIG otherwise. */
    static int level(long modelBytes, int context, boolean f16, long freeRam, long totalRam) {
        long need = needBytes(modelBytes, context, f16);
        if (need <= freeRam) return GOOD;
        if (need <= totalRam / 2) return TIGHT;
        return TOO_BIG;
    }
}
