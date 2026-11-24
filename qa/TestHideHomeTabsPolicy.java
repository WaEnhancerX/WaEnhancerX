package com.waenhancer.xposed.features.customization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JVM-only checks of the production tab-ID policy; no host hook execution claimed. */
public final class TestHideHomeTabsPolicy {
    private static int checks;

    private static void check(boolean condition, String problem) {
        checks++;
        if (!condition) throw new AssertionError(problem);
    }

    private static Set<String> selection(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    public static void main(String[] args) {
        check(TypedStyleHookPolicy.parseHiddenTabIds(null).isEmpty(), "null selection");
        check(TypedStyleHookPolicy.parseHiddenTabIds(selection()).isEmpty(), "empty selection");

        Set<String> configured = selection("300");
        Set<Integer> hidden = TypedStyleHookPolicy.parseHiddenTabIds(configured);
        check(hidden.equals(Set.of(300)), "Updates selection must survive unchanged");
        check(configured.equals(selection("300")), "stored selection was mutated");

        ArrayList<Object> home = new ArrayList<>(Arrays.asList(100, 300, 600, 300, "300"));
        TypedStyleHookPolicy.filterTabIds(home, hidden);
        check(home.equals(List.of(100, 600, "300")), "both Updates model entries must be removed");

        Set<Integer> combined = TypedStyleHookPolicy.parseHiddenTabIds(selection("300", "400", "600"));
        check(combined.equals(Set.of(300, 400, 600)), "all selected tab IDs must survive");

        ArrayList<Object> otherTabs = new ArrayList<>(Arrays.asList(200, 300, 400, 600, 700));
        TypedStyleHookPolicy.filterTabIds(otherTabs, combined);
        check(otherTabs.equals(List.of(200, 700)), "multiple selection must preserve unselected tabs");

        Set<Integer> invalid = TypedStyleHookPolicy.parseHiddenTabIds(selection("300", "not-a-tab", "", "2147483648"));
        check(invalid.equals(Set.of(300)), "invalid stored IDs must not prevent valid Updates hiding");

        ArrayList<Object> unaffected = new ArrayList<>(Arrays.asList(200, 400, 600));
        TypedStyleHookPolicy.filterTabIds(unaffected, Set.of(300));
        check(unaffected.equals(List.of(200, 400, 600)), "unselected tabs must be preserved");

        check(TypedStyleHookPolicy.parseHiddenTabIds(selection("300", "300")).equals(Set.of(300)),
                "duplicate legacy selection should be idempotent");

        System.out.println("PASS: " + checks + " production tab-ID parsing/filtering checks");
    }
}
