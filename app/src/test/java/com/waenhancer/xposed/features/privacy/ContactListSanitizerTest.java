package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ContactListSanitizerTest {
    /** Stand-in for WhatsApp's JID: only the readable getRawString contract matters. */
    public static final class Jid {
        private final String raw;
        Jid(String raw) { this.raw = raw; }
        public String getRawString() { return raw; }
    }

    /** Stand-in for an obfuscated contact wrapper that is not a JID but holds one. */
    static final class Row {
        final Object whoever;
        final String label;
        Row(String raw, String label) { this.whoever = new Jid(raw); this.label = label; }
    }

    /** Stand-in for the picker's results holder: many lists, none identifiable by name. */
    static final class Results {
        final List<Object> a;
        final List<Object> b;
        final List<Object> c;
        final List<Object> d;
        final Set<Object> notAList = new HashSet<>();
        Results(List<Object> a, List<Object> b, List<Object> c, List<Object> d) {
            this.a = a; this.b = b; this.c = c; this.d = d;
        }
    }

    private static Collection<String> ids(Object value) {
        return ContactIdentityExtractor.identities(value);
    }

    private static final Set<String> LOCKED = Collections.singleton("locked@s.whatsapp.net");

    private static List<Object> rows(String... raws) {
        List<Object> rows = new ArrayList<>();
        for (String raw : raws) rows.add(new Row(raw, raw));
        return rows;
    }

    @Test
    public void everyListFieldIsCleanedNotJustOne() {
        Results results = new Results(
                rows("a@s.whatsapp.net", "locked@s.whatsapp.net"),
                rows("locked@s.whatsapp.net", "b@s.whatsapp.net"),
                rows("c@s.whatsapp.net"),
                rows("locked@s.whatsapp.net"));

        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(
                new Object[]{results}, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(3, outcome.removedContacts);
        assertEquals(3, outcome.cleanedLists);
        assertEquals(1, results.a.size());
        assertEquals(1, results.b.size());
        assertEquals(1, results.c.size());
        assertEquals(0, results.d.size());
    }

    @Test
    public void bareListArgumentIsReplacedWithFilteredCopy() {
        List<Object> original = rows("x@s.whatsapp.net", "locked@s.whatsapp.net");
        Object[] args = {original, "unrelated"};

        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(2, original.size()); // the host's own list is never mutated
        assertEquals(1, ((List<?>) args[0]).size());
        assertEquals("unrelated", args[1]);
    }

    @Test
    public void unchangedListsAndForeignEntriesAreKept() {
        List<Object> mixed = new ArrayList<>(Arrays.asList(
                new Row("x@s.whatsapp.net", "x"), "section header", 42));
        Object[] args = {mixed};

        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(
                args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(0, outcome.removedContacts);
        assertSame(mixed, args[0]);
    }

    @Test
    public void identityIsMatchedCaseInsensitivelyThroughWrappers() {
        Set<String> locked = LockedContactFilterPolicy.collectIds(
                Collections.singleton(new Jid("Locked@S.whatsapp.net")), ContactListSanitizerTest::ids);
        List<Object> list = rows("LOCKED@s.whatsapp.net", "ok@s.whatsapp.net");

        ContactListSanitizer.scrub(new Object[]{list}, locked, ContactListSanitizerTest::ids);

        assertEquals(2, list.size());
        Object[] args = {list};
        ContactListSanitizer.scrub(args, locked, ContactListSanitizerTest::ids);
        assertEquals(1, ((List<?>) args[0]).size());
    }

    /** A group row that also references the locked person (e.g. last sender) stays visible. */
    static final class GroupRow {
        final Object group = new Jid("123-456@g.us");
        final Object lastSender;
        GroupRow(String sender) { this.lastSender = new Jid(sender); }
    }

    /** Real rows are wrappers several levels deep around a record that holds the JIDs. */
    static final class Record {
        final Object lid;
        final Object phone;
        Record(String lid, String phone) { this.lid = new Jid(lid); this.phone = new Jid(phone); }
    }
    static final class Outer { final Object inner; Outer(Object inner) { this.inner = inner; } }

    @Test
    public void groupsReferencingTheLockedPersonAreNotHidden() {
        List<Object> list = new ArrayList<>(Arrays.asList(
                new GroupRow("locked@s.whatsapp.net"), new Row("locked@s.whatsapp.net", "person")));
        Object[] args = {list};

        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(
                args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(1, outcome.removedContacts);
        assertTrue(((List<?>) args[0]).get(0) instanceof GroupRow);
    }

    @Test
    public void lockedGroupIsHiddenByItsOwnId() {
        Set<String> locked = Collections.singleton("123-456@g.us");
        List<Object> list = new ArrayList<>(Arrays.asList(new GroupRow("x@s.whatsapp.net")));
        Object[] args = {list};

        ContactListSanitizer.scrub(args, locked, ContactListSanitizerTest::ids);

        assertEquals(0, ((List<?>) args[0]).size());
    }

    @Test
    public void jidsNestedSeveralLevelsDownAreFound() {
        Object deep = new Outer(new Outer(new Record("locked@s.whatsapp.net", "other@s.whatsapp.net")));
        Object other = new Outer(new Outer(new Record("ok@lid", "ok@s.whatsapp.net")));
        Object[] args = {new ArrayList<Object>(Arrays.asList(deep, other))};

        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(1, ((List<?>) args[0]).size());
        assertSame(other, ((List<?>) args[0]).get(0));
    }

    @Test
    public void previouslyCleanedListsAreRescanned() {
        Set<String> locked = new HashSet<>(Collections.singleton("locked@s.whatsapp.net"));
        Results results = new Results(
                rows("a@s.whatsapp.net", "locked@s.whatsapp.net"),
                rows("b@s.whatsapp.net"), rows("c@s.whatsapp.net"), rows());
        int[] calls = {0};
        Function<Object, Collection<String>> counting = item -> {
            calls[0]++;
            return ContactIdentityExtractor.identities(item);
        };

        ContactListSanitizer.scrub(new Object[]{results}, locked, counting);
        assertTrue(calls[0] > 0);
        int afterFirst = calls[0];

        // A second hooked method of the same picker load receives the same holder.
        ContactListSanitizer.scrub(new Object[]{results}, locked, counting);
        assertEquals("each of the three remaining rows is rechecked", afterFirst + 3, calls[0]);

        // A different locked set (the user locked another chat) must re-check everything.
        Set<String> changed = new HashSet<>(Arrays.asList("locked@s.whatsapp.net", "b@s.whatsapp.net"));
        ContactListSanitizer.scrub(new Object[]{results}, changed, counting);
        assertTrue(calls[0] > afterFirst);
        assertEquals(0, results.b.size());
    }

    @Test
    public void unidentifiableRowsAreReportedSoHostChangesAreNotSilent() {
        List<Object> opaque = new ArrayList<>(Arrays.asList(new Object(), "text", 42));
        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(
                new Object[]{opaque}, Collections.singleton("x@lid"), ContactListSanitizerTest::ids);
        assertEquals(3, outcome.rowsSeen);
        assertEquals(0, outcome.rowsIdentified);

        List<Object> identifiable = rows("a@s.whatsapp.net");
        ContactListSanitizer.Result healthy = ContactListSanitizer.scrub(
                new Object[]{identifiable}, Collections.singleton("x@lid"), ContactListSanitizerTest::ids);
        assertEquals(1, healthy.rowsIdentified);
    }

    @Test
    public void noLockedChatsMeansNoWork() {
        Results results = new Results(rows("locked@s.whatsapp.net"), rows(), rows(), rows());
        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(
                new Object[]{results}, Collections.emptySet(), ContactListSanitizerTest::ids);
        assertEquals(0, outcome.removedContacts);
        assertEquals(1, results.a.size());
    }

    @Test
    public void resultsHolderDetectionNeedsSeveralHostLists() {
        assertTrue(ContactListSanitizer.isResultsHolder(Results.class, 3));
        assertFalse(ContactListSanitizer.isResultsHolder(Row.class, 1));
        assertFalse(ContactListSanitizer.isResultsHolder(String.class, 1));
        assertFalse(ContactListSanitizer.isResultsHolder(ArrayList.class, 1));
    }

    static final class MutableRow {
        Object identity;
        MutableRow(String raw) { identity = new Jid(raw); }
    }

    static final class OneList {
        List<Object> list;
        OneList(List<Object> list) { this.list = list; }
    }

    public static final class ChangingJid {
        int calls;
        public String getRawString() {
            return ++calls == 1 ? "open@s.whatsapp.net" : "locked@s.whatsapp.net";
        }
    }

    public static final class RecoveringJid {
        int calls;
        public String getRawString() {
            if (++calls == 1) throw new IllegalStateException("synthetic unavailable identity");
            return "locked@s.whatsapp.net";
        }
    }

    @Test
    public void sameSizeReplacementInPreviouslyCleanListIsFiltered() {
        List<Object> source = rows("open@s.whatsapp.net");
        Object[] args = {source};
        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
        source.set(0, new Row("locked@s.whatsapp.net", "replacement"));

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(1, outcome.removedContacts);
        assertEquals(0, ((List<?>) args[0]).size());
        assertEquals(1, source.size());
    }

    @Test
    public void previouslyInstalledFilteredCopyIsRecheckedAfterReplacement() {
        Object[] args = {rows("locked@s.whatsapp.net", "open@s.whatsapp.net")};
        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
        @SuppressWarnings("unchecked")
        List<Object> filtered = (List<Object>) args[0];
        filtered.set(0, new Row("locked@s.whatsapp.net", "replacement"));

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(1, outcome.removedContacts);
        assertEquals(0, ((List<?>) args[0]).size());
        assertEquals(1, filtered.size());
    }

    @Test
    public void holderFieldIsRecheckedAfterSameSizeReplacement() {
        OneList holder = new OneList(rows("open@s.whatsapp.net"));
        Object[] args = {holder};
        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
        holder.list.set(0, new Row("locked@s.whatsapp.net", "replacement"));

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertSame(holder, args[0]);
        assertEquals(1, outcome.removedContacts);
        assertTrue(holder.list.isEmpty());
    }

    @Test
    public void unmodifiableListWithChangedNestedIdentityIsRechecked() {
        MutableRow row = new MutableRow("open@s.whatsapp.net");
        List<Object> source = Collections.singletonList(row);
        Object[] args = {source};
        ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
        row.identity = new Jid("locked@s.whatsapp.net");

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(1, outcome.removedContacts);
        assertEquals(0, ((List<?>) args[0]).size());
        assertSame(row, source.get(0));
    }

    @Test
    public void changedMembershipInSameSetInstanceIsRechecked() {
        // A generic scrub contract control; LockedChatIds publishes unmodifiable snapshots.
        Set<String> locked = new HashSet<>(LOCKED);
        List<Object> source = rows("open@s.whatsapp.net");
        Object[] args = {source};
        ContactListSanitizer.scrub(args, locked, ContactListSanitizerTest::ids);
        locked.clear();
        locked.add("open@s.whatsapp.net");

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, locked, ContactListSanitizerTest::ids);

        assertEquals(1, outcome.removedContacts);
        assertTrue(((List<?>) args[0]).isEmpty());
    }

    @Test
    public void dirtyAliasesReceiveIndependentCopiesWithoutMutatingSource() {
        Object unknown = new Object();
        Row open = new Row("open@s.whatsapp.net", "open");
        Row locked = new Row("locked@s.whatsapp.net", "locked");
        List<Object> source = Collections.unmodifiableList(
                Arrays.asList(unknown, open, locked, open, locked, null));
        OneList holder = new OneList(source);
        Object[] args = {source, holder};

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertEquals(Arrays.asList(unknown, open, open, null), args[0]);
        assertEquals(Arrays.asList(unknown, open, open, null), holder.list);
        assertNotSame(args[0], holder.list);
        assertEquals(6, source.size());
        assertSame(locked, source.get(2));
        assertSame(locked, source.get(4));
        assertEquals(4, outcome.removedContacts);
        assertEquals(2, outcome.cleanedLists);
        assertEquals(12, outcome.rowsSeen);
        assertEquals(8, outcome.rowsIdentified);
    }

    @Test
    public void aliasesAreRecheckedWithinTheSameScrub() {
        ChangingJid row = new ChangingJid();
        List<Object> source = Collections.singletonList(row);
        Object[] args = {source, source};

        ContactListSanitizer.Result outcome =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertSame(source, args[0]);
        assertTrue(((List<?>) args[1]).isEmpty());
        assertEquals(2, row.calls);
        assertEquals(2, outcome.rowsSeen);
        assertEquals(1, outcome.removedContacts);
    }

    @Test
    public void identityGetterFailureDoesNotCacheUnknownAsClean() {
        RecoveringJid row = new RecoveringJid();
        List<Object> source = Collections.singletonList(row);
        Object[] args = {source};
        ContactListSanitizer.Result first =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
        assertSame(source, args[0]);
        assertEquals(0, first.rowsIdentified);

        ContactListSanitizer.Result second =
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);

        assertTrue(((List<?>) args[0]).isEmpty());
        assertEquals(1, second.removedContacts);
        assertEquals(2, row.calls);
    }

    @Test
    public void holderCallbackFailureKeepsFieldAndContinuesWithLaterArgument() {
        Object sentinel = new Object();
        List<Object> original = Collections.singletonList(sentinel);
        OneList holder = new OneList(original);
        Object[] args = {holder, rows("locked@s.whatsapp.net")};
        Function<Object, Collection<String>> extractor = row -> {
            if (row == sentinel) throw new IllegalStateException("synthetic callback failure");
            return ids(row);
        };

        ContactListSanitizer.Result outcome = ContactListSanitizer.scrub(args, LOCKED, extractor);

        assertSame(original, holder.list);
        assertTrue(((List<?>) args[1]).isEmpty());
        assertEquals(1, outcome.removedContacts);
        assertEquals(1, outcome.rowsSeen);
    }

    @Test
    public void bareArgumentCallbackFailureStillPropagates() {
        RuntimeException failure = new IllegalStateException("synthetic callback failure");
        List<Object> original = Collections.singletonList(new Object());
        Object[] args = {original};
        try {
            ContactListSanitizer.scrub(args, LOCKED, row -> { throw failure; });
            fail("a bare-argument callback failure must propagate");
        } catch (RuntimeException caught) {
            assertSame(failure, caught);
        }
        assertSame(original, args[0]);
    }

    @Test(timeout = 10000)
    public void rechecksAfterExplicitCrossThreadHandoff() throws Exception {
        MutableRow row = new MutableRow("open@s.whatsapp.net");
        List<Object> source = Collections.singletonList(row);
        Object[] args = {source};
        CountDownLatch firstFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ContactListSanitizer.Result> second = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                firstFinished.countDown();
            }
        });
        Thread next = new Thread(() -> {
            try {
                firstFinished.await();
                row.identity = new Jid("locked@s.whatsapp.net");
                second.set(ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        first.start();
        next.start();
        first.join();
        next.join();
        assertNull(failure.get());
        assertEquals(1, second.get().removedContacts);
        assertTrue(((List<?>) args[0]).isEmpty());
        assertSame(row, source.get(0));
    }

    @Test(timeout = 10000)
    public void simultaneousIndependentScrubsKeepResultsLocal() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                tasks.add(() -> {
                    Object[] args = {rows("open@s.whatsapp.net", "locked@s.whatsapp.net")};
                    ContactListSanitizer.Result outcome =
                            ContactListSanitizer.scrub(args, LOCKED, ContactListSanitizerTest::ids);
                    return outcome.rowsSeen == 2 && outcome.rowsIdentified == 2
                            && outcome.removedContacts == 1 && outcome.cleanedLists == 1
                            && ((List<?>) args[0]).size() == 1;
                });
            }
            for (Future<Boolean> result : workers.invokeAll(tasks)) assertTrue(result.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void nullAndEmptyInputsDoNotInvokeIdentityExtraction() {
        Function<Object, Collection<String>> forbidden = row -> {
            throw new AssertionError("identity extraction should not run");
        };
        assertEquals(0, ContactListSanitizer.scrub(null, LOCKED, forbidden).rowsSeen);
        assertEquals(0, ContactListSanitizer.scrub(new Object[]{rows("open@s.whatsapp.net")},
                null, forbidden).rowsSeen);
        assertEquals(0, ContactListSanitizer.scrub(new Object[]{Collections.emptyList()},
                LOCKED, forbidden).rowsSeen);
    }
}
