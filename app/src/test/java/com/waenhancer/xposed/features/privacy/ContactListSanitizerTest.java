package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
    public void listsAlreadyCleanedAreNotScannedAgain() {
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
        assertEquals("no identity work on the second visit", afterFirst, calls[0]);

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
}
