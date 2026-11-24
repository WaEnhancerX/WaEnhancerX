package com.waenhancer.xposed.features.privacy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Splits a played-receipt batch into messages whose receipt may be sent and messages whose
 * receipt is hidden. Each message is decided on its own, so hiding a voice note never holds back
 * the receipt for an ordinary message sent in the same batch.
 */
public final class PlayedReceiptBatch {

    private PlayedReceiptBatch() {}

    /**
     * @return {@code null} when nothing is hidden (leave the call untouched), an empty set when
     *         everything is hidden (skip the call), otherwise the messages to keep, in order.
     */
    @Nullable
    public static <T> Set<T> keep(@NonNull Set<? extends T> batch,
                                  @NonNull Predicate<? super T> hide,
                                  @NonNull Consumer<? super T> onHidden) {
        Set<T> kept = new LinkedHashSet<>();
        boolean hiddenAny = false;
        for (T message : batch) {
            boolean hidden;
            try {
                hidden = hide.test(message);
            } catch (Throwable error) {
                hidden = false; // An unreadable message is never held back by mistake.
            }
            if (hidden) {
                hiddenAny = true;
                onHidden.accept(message);
            } else {
                kept.add(message);
            }
        }
        return hiddenAny ? kept : null;
    }
}
