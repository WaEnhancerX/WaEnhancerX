package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class LockedChatIdsTest {
    private final AtomicLong now = new AtomicLong(1_000L);
    private final Map<String, List<String>> tables = new HashMap<>();
    private final List<String> failing = new ArrayList<>();
    private int queries;

    private LockedChatIds source() {
        return new LockedChatIds(() -> sql -> {
            queries++;
            if (failing.contains(sql)) throw new IllegalStateException("no such column");
            return tables.getOrDefault(sql, Collections.emptyList());
        }, now::get);
    }

    @Test
    public void readsLockedChatsAndTheirMappedTwinsLowerCased() {
        tables.put(LockedChatIds.SQL_LOCKED, Arrays.asList("123@LID", " "));
        tables.put(LockedChatIds.SQL_LID_TO_PHONE, Collections.singletonList("15551234567@s.whatsapp.net"));

        Set<String> ids = source().current();

        assertEquals(new HashSet<>(Arrays.asList("123@lid", "15551234567@s.whatsapp.net")), ids);
    }

    @Test
    public void missingMappingTableKeepsThePrimaryResult() {
        tables.put(LockedChatIds.SQL_LOCKED, Collections.singletonList("123@lid"));
        failing.add(LockedChatIds.SQL_LID_TO_PHONE);
        failing.add(LockedChatIds.SQL_PHONE_TO_LID);

        assertEquals(Collections.singleton("123@lid"), source().current());
    }

    @Test
    public void noLockedChatsIsAnEmptySetNotAnError() {
        Set<String> ids = source().current();
        assertTrue(ids.isEmpty());
        assertEquals(1, queries); // nothing locked: the mapping queries are skipped
    }

    @Test
    public void unreadableSchemaReturnsNullSoTheCallerCanFallBack() {
        failing.add(LockedChatIds.SQL_LOCKED);
        assertNull(source().current());
    }

    @Test
    public void unavailableDatabaseReturnsNull() {
        assertNull(new LockedChatIds(() -> null, now::get).current());
    }

    @Test
    public void resultIsCachedBrieflyAndInstanceIsStableWhileUnchanged() {
        tables.put(LockedChatIds.SQL_LOCKED, Collections.singletonList("1@lid"));
        LockedChatIds ids = source();

        Set<String> first = ids.current();
        int afterFirst = queries;
        assertSame(first, ids.current());
        assertEquals(afterFirst, queries);

        now.addAndGet(2_000L);
        assertSame("unchanged content keeps the same instance", first, ids.current());
        assertTrue(queries > afterFirst);

        tables.put(LockedChatIds.SQL_LOCKED, Arrays.asList("1@lid", "2@lid"));
        now.addAndGet(2_000L);
        assertEquals(2, ids.current().size());
    }

    @Test
    public void afterAFailureTheDatabaseIsRetriedOnlyLater() {
        failing.add(LockedChatIds.SQL_LOCKED);
        LockedChatIds ids = source();
        assertNull(ids.current());
        int afterFailure = queries;

        failing.clear();
        tables.put(LockedChatIds.SQL_LOCKED, Collections.singletonList("1@lid"));
        now.addAndGet(5_000L);
        assertNull(ids.current());
        assertEquals(afterFailure, queries);

        now.addAndGet(60_000L);
        assertEquals(Collections.singleton("1@lid"), ids.current());
    }
}
