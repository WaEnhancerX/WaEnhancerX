#!/usr/bin/env python3
"""Compile and run the real, Android-independent SeparateGroups tab policy.

Checks selection/order/count and idempotence. Runtime routing and badges require
instrumented Android/LSPosed tests; this does not test those paths.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/waenhancer/xposed/features/customization/SeparateGroupsTabPolicy.java'
TEST = r'''package com.waenhancer.xposed.features.customization;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
public class TestTabPolicy {
    private static int count;
    static void check(List<Integer> before, List<Integer> expected) {
        ArrayList<Integer> result = SeparateGroupsTabPolicy.rewrite(before);
        if (!result.equals(expected)) throw new AssertionError(before + " -> " + result + " expected " + expected);
        if (result.size() > 5 && before.size() <= 5) throw new AssertionError("overflow");
        if (result.stream().filter(x -> x == 500).count() > 1) throw new AssertionError("duplicate Groups");
        if (!SeparateGroupsTabPolicy.rewrite(result).equals(result)) throw new AssertionError("not idempotent");
        count++;
    }
    static void permutations(int[] candidates, boolean[] used, ArrayList<Integer> input) {
        ArrayList<Integer> output = SeparateGroupsTabPolicy.rewrite(input);
        if (output.size() > 5) throw new AssertionError("overflow: " + input);
        if (output.contains(600)) throw new AssertionError("Communities remains: " + input);
        boolean shouldInsertGroups = input.contains(600) || input.size() < 5;
        if (output.contains(500) != shouldInsertGroups) throw new AssertionError("Groups fallback: " + input);
        ArrayList<Integer> preserved = new ArrayList<>(output);
        preserved.remove(Integer.valueOf(500));
        ArrayList<Integer> nativePreserved = new ArrayList<>(input);
        nativePreserved.remove(Integer.valueOf(600));
        if (!preserved.equals(nativePreserved)) throw new AssertionError("native order: " + input);
        if (!SeparateGroupsTabPolicy.rewrite(output).equals(output)) throw new AssertionError("not idempotent: " + input);
        count++;
        if (input.size() == 5) return;
        for (int i = 0; i < candidates.length; i++) {
            if (used[i]) continue;
            used[i] = true;
            input.add(candidates[i]);
            permutations(candidates, used, input);
            input.remove(input.size() - 1);
            used[i] = false;
        }
    }
    public static void main(String[] ignored) {
        check(Arrays.asList(200,300,600,400,700), Arrays.asList(200,500,300,400,700));
        check(Arrays.asList(200,300,600,400,1000), Arrays.asList(200,500,300,400,1000));
        check(Arrays.asList(200,300,400,700,1000), Arrays.asList(200,300,400,700,1000));
        check(Arrays.asList(200,300,400,700), Arrays.asList(200,500,300,400,700));
        check(Arrays.asList(200,500,300,600,400,700), Arrays.asList(200,500,300,400,700));
        check(Arrays.asList(200,500,500,300,600,400,700), Arrays.asList(200,500,300,400,700));
        check(Arrays.asList(200,300,400,700,1000,600), Arrays.asList(200,300,400,700,1000));
        check(Arrays.asList(), Arrays.asList(500));
        // Enumerate every native subset/order of the five known destinations.
        int[] nativeIds = {200,300,400,600,700};
        for (int mask = 0; mask < 32; mask++) {
            ArrayList<Integer> input = new ArrayList<>();
            for (int i = 0; i < nativeIds.length; i++) if ((mask & (1 << i)) != 0) input.add(nativeIds[i]);
            ArrayList<Integer> out = SeparateGroupsTabPolicy.rewrite(input);
            if (out.size() > 5) throw new AssertionError("overflow " + input);
            if (out.contains(600)) throw new AssertionError("Communities not replaced " + input);
            if (!out.contains(500)) throw new AssertionError("Groups missing " + input);
            if (out.size() != input.size() + (input.contains(600) ? 0 : 1))
                throw new AssertionError("size mismatch " + input);
            if (!SeparateGroupsTabPolicy.rewrite(out).equals(out)) throw new AssertionError("non-idempotent " + input);
            count++;
        }
        permutations(new int[]{200, 300, 400, 600, 700, 1000}, new boolean[6], new ArrayList<>());
        System.out.println("SeparateGroupsTabPolicy: " + count + " cases passed");
    }
}
'''
with tempfile.TemporaryDirectory() as tmp:
    home = Path(tmp)
    file = home / 'com/waenhancer/xposed/features/customization/TestTabPolicy.java'
    file.parent.mkdir(parents=True)
    file.write_text(TEST)
    subprocess.run(['javac', '-d', tmp, str(SOURCE), str(file)], check=True)
    subprocess.run(['java', '-cp', tmp, 'com.waenhancer.xposed.features.customization.TestTabPolicy'], check=True)
