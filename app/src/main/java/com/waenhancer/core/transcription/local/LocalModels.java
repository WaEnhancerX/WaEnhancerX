package com.waenhancer.core.transcription.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Whisper models offered for on-device transcription, and which one suits a phone. Sizes and
 * SHA-256 checksums are pinned to the files published in ggerganov/whisper.cpp on Hugging Face.
 */
public final class LocalModels {

    public static final String DOWNLOAD_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    public static final class Model {
        @NonNull public final String id;
        @NonNull public final String fileName;
        @NonNull public final String label;
        public final long sizeBytes;
        @NonNull public final String sha256;
        /** English-only models are more accurate for English at the same size. */
        public final boolean englishOnly;
        /** Total device RAM below which the model is likely to be too slow or run out of memory. */
        public final double minRamGb;

        Model(String id, String fileName, String label, long sizeBytes, String sha256,
              boolean englishOnly, double minRamGb) {
            this.id = id;
            this.fileName = fileName;
            this.label = label;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.englishOnly = englishOnly;
            this.minRamGb = minRamGb;
        }

        public String sizeLabel() {
            return sizeBytes >= 1_000_000_000 ? String.format(java.util.Locale.ROOT, "%.1f GB", sizeBytes / 1e9)
                    : (sizeBytes / 1_000_000) + " MB";
        }
    }

    public static final List<Model> ALL = Collections.unmodifiableList(Arrays.asList(
            new Model("tiny", "ggml-tiny-q5_1.bin", "Tiny", 32_152_673L,
                    "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", false, 2),
            new Model("tiny.en", "ggml-tiny.en-q5_1.bin", "Tiny (English)", 32_166_155L,
                    "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", true, 2),
            new Model("base", "ggml-base-q5_1.bin", "Base", 59_707_625L,
                    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", false, 3),
            new Model("base.en", "ggml-base.en-q5_1.bin", "Base (English)", 59_721_011L,
                    "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", true, 3),
            new Model("small", "ggml-small-q5_1.bin", "Small", 190_085_487L,
                    "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", false, 4),
            new Model("small.en", "ggml-small.en-q5_1.bin", "Small (English)", 190_098_681L,
                    "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", true, 4),
            new Model("large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin", "Large v3 Turbo", 574_041_195L,
                    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", false, 8)
    ));

    private LocalModels() {}

    @Nullable
    public static Model byId(@Nullable String id) {
        if (id == null) return null;
        for (Model model : ALL) {
            if (model.id.equals(id)) return model;
        }
        return null;
    }

    /** The suggested model and a short reason to show next to it. */
    public static final class Recommendation {
        @NonNull public final Model model;
        @NonNull public final String reason;

        Recommendation(Model model, String reason) {
            this.model = model;
            this.reason = reason;
        }
    }

    /**
     * Picks a model from total RAM, CPU core count and the chosen transcription language
     * (empty for automatic). Small and tiny models are weak outside English, so for other
     * languages the smallest suggestion is Base and phones with 4 GB or more get Small.
     * Large v3 Turbo is never the default: it is the most accurate but several times slower.
     */
    @NonNull
    public static Recommendation recommend(long totalRamBytes, int cores, @Nullable String language) {
        // Android reports less than the nominal size (an "8 GB" phone shows about 7.4 GiB).
        double ramGb = Math.ceil(totalRamBytes / (1024.0 * 1024 * 1024));
        boolean english = "en".equals(language);
        String ram = String.format(java.util.Locale.ROOT, "%.0f GB RAM", ramGb);
        if (english) {
            if (ramGb < 3 || cores < 4) return new Recommendation(byId("tiny.en"), ram + ": fastest English model");
            if (ramGb < 6) return new Recommendation(byId("base.en"), ram + ": good English accuracy, fast");
            return new Recommendation(byId("small.en"), ram + ": best English accuracy at good speed");
        }
        if (ramGb < 4 || cores < 4) {
            return new Recommendation(byId("base"), ram + ": lighter model; accuracy outside English is limited");
        }
        return new Recommendation(byId("small"), ram + ": good accuracy for most languages");
    }
}
