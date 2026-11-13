package com.waenhancer.xposed.features.media;

import com.waenhancer.xposed.features.customization.PropsOverrideHook;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Independent fixture contract transcribed from WhatsApp 2.26.43.76 DEX:
 * X.9P3 -> PS8(abProps.int(1577), int(1578), int(1576));
 * X.J2X -> PS8(abProps.int(2477), int(2475), int(2476)).
 * PS8 constructor/toString bind those positions to maxKb, quality and maxEdge.
 * The local registry is an explicit, strict test boundary; WhatsApp is not run.
 */
public final class MediaScreenshotPropsChecks {
    private static int assertions;
    private static int failures;

    private static void check(boolean ok, String description) {
        assertions++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + description);
        }
    }

    private static void imageProperties(Map<Integer, Integer> map, int maxKbId,
                                        int qualityId, int maxEdgeId, String name) {
        check(Integer.valueOf(51200).equals(map.get(maxKbId)), name + " maxKb " + maxKbId);
        check(Integer.valueOf(100).equals(map.get(qualityId)), name + " quality " + qualityId);
        check(Integer.valueOf(6000).equals(map.get(maxEdgeId)), name + " maxEdge " + maxEdgeId);
    }

    private static void cleared(Map<Integer, Integer> map, int maxKbId,
                                int qualityId, int maxEdgeId, String name) {
        check(!map.containsKey(maxKbId), name + " disable maxKb");
        check(!map.containsKey(qualityId), name + " disable quality");
        check(!map.containsKey(maxEdgeId), name + " disable maxEdge");
    }

    public static void main(String[] args) {
        PropsOverrideHook.INTEGERS.clear();
        PropsOverrideHook.BOOLEANS.clear();
        PropsOverrideHook.INTEGERS.put(876543, 42); // An unrelated user-authored ID.
        PropsOverrideHook.BOOLEANS.put(876544, false);

        MediaQualityBypassHook.configurePropertyOverrides(true);
        imageProperties(PropsOverrideHook.INTEGERS, 1577, 1578, 1576, "default image");
        imageProperties(PropsOverrideHook.INTEGERS, 2477, 2475, 2476, "screenshot image");
        Set<Integer> expectedIds = new HashSet<>(Arrays.asList(
                1577, 6030, 2656, 15752, 15746, 2477,
                1581, 1575, 1578, 6029, 2655, 15749, 2475,
                1576, 2654, 6032, 15748, 3068, 2476, 876543));
        check(PropsOverrideHook.INTEGERS.keySet().equals(expectedIds),
                "only expected integer properties registered");
        for (int[] other : new int[][] {
                {6030, 6029, 6032}, {2656, 2655, 2654},
                {15752, 15749, 15748}, {15746, 1581, 3068}}) {
            imageProperties(PropsOverrideHook.INTEGERS, other[0], other[1], other[2],
                    "other legacy property family");
        }
        check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(6033)), "enabled 6033");
        check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(9569)), "enabled 9569");
        check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(26289)), "enabled 26289");
        check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(22375)), "enabled 22375");
        check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(14447)), "enabled 14447");
        check(Integer.valueOf(42).equals(PropsOverrideHook.INTEGERS.get(876543)),
                "unrelated integer retained on enable");
        check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(876544)),
                "unrelated boolean retained on enable");

        // The feature registry loads custom overrides *after* these defaults.
        // Applying a user override last must remain possible.
        PropsOverrideHook.INTEGERS.put(2477, 2048);
        check(Integer.valueOf(2048).equals(PropsOverrideHook.INTEGERS.get(2477)),
                "later custom property retains priority");
        MediaQualityBypassHook.configurePropertyOverrides(false);
        cleared(PropsOverrideHook.INTEGERS, 1577, 1578, 1576, "default image");
        cleared(PropsOverrideHook.INTEGERS, 2477, 2475, 2476, "screenshot image");
        for (int[] other : new int[][] {
                {6030, 6029, 6032}, {2656, 2655, 2654},
                {15752, 15749, 15748}, {15746, 1581, 3068}}) {
            cleared(PropsOverrideHook.INTEGERS, other[0], other[1], other[2],
                    "other legacy property family");
        }
        for (int id : new int[] {6033, 9569, 26289, 22375, 14447}) {
            check(!PropsOverrideHook.BOOLEANS.containsKey(id), "disable boolean " + id);
        }
        check(Integer.valueOf(42).equals(PropsOverrideHook.INTEGERS.get(876543)),
                "unrelated integer retained on disable");
        check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(876544)),
                "unrelated boolean retained on disable");

        MediaQualityBypassHook.configurePropertyOverrides(true);
        imageProperties(PropsOverrideHook.INTEGERS, 1577, 1578, 1576, "re-enabled default");
        imageProperties(PropsOverrideHook.INTEGERS, 2477, 2475, 2476, "re-enabled screenshot");
        check(PropsOverrideHook.INTEGERS.keySet().equals(expectedIds),
                "re-enable restores only expected integer property set");

        System.out.println("RESULT assertions=" + assertions + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
