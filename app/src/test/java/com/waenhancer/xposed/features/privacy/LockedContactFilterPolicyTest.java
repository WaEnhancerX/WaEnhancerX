package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class LockedContactFilterPolicyTest {
    @Test public void normalizesIdsAndPreservesUnknownEntries() {
        Set<String> locked = LockedContactFilterPolicy.collectIds(
                Arrays.asList(" 123@S.WHATSAPP.NET ", "abc@lid"),
                value -> Collections.singleton(String.valueOf(value)));
        List<String> result = LockedContactFilterPolicy.withoutLockedContacts(
                Arrays.asList("unknown", "first", "second"), locked,
                item -> {
                    if (item.equals("first")) return Collections.singleton("123@s.whatsapp.net");
                    if (item.equals("second")) return Collections.singleton("ABC@LID");
                    return Collections.emptyList();
                });
        assertEquals(Collections.singletonList("unknown"), result);
    }

    @Test public void doesNotMutateFixedSourceList() {
        List<String> source = Arrays.asList("open", "locked");
        Collection<String> one = Collections.singleton("1@s.whatsapp.net");
        List<String> result = LockedContactFilterPolicy.withoutLockedContacts(
                source, Collections.singleton("1@s.whatsapp.net"),
                item -> item.equals("locked") ? one : Collections.singleton("2@s.whatsapp.net"));
        assertEquals(Collections.singletonList("open"), result);
        assertEquals(2, source.size());
    }
}
