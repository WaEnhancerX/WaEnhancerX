package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public class PendingReadReceiptStoreTest {
    @Test public void returnsOldestEvictionAndRetainsNewestItems() {
        PendingReadReceiptStore<String> store = new PendingReadReceiptStore<>(2, 2);
        assertEquals(null, store.add("a", "one"));
        assertEquals(null, store.add("a", "two"));
        assertEquals("one", store.add("a", "three"));
        assertEquals(Arrays.asList("two", "three"), store.snapshot("a"));
    }
}
