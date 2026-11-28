package com.waenhancer.utils;

import java.io.File;
import java.util.function.Predicate;

/** Deletes only named diagnostic files and checks the observed result. */
final class DiagnosticsFileCleanup {
    private DiagnosticsFileCleanup() {}

    static boolean clearKnownFiles(File directory, Predicate<String> ownedName) {
        if (directory == null || !directory.isAbsolute() || ownedName == null) return false;
        File[] files;
        try {
            files = directory.listFiles();
        } catch (SecurityException ignored) {
            return false;
        }
        if (files == null) return confirmedAbsent(directory);
        for (File file : files) {
            if (!ownedName.test(file.getName())) continue;
            try {
                // Never recurse into directories or delete unrelated application files.
                if (file.isFile()) file.delete();
            } catch (SecurityException ignored) {
                // Continue the other deletions; the final listing determines completion.
            }
        }
        try {
            String[] remaining = directory.list();
            if (remaining == null) return confirmedAbsent(directory);
            for (String name : remaining) {
                if (ownedName.test(name)) return false;
            }
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static boolean confirmedAbsent(File directory) {
        // exists()==false alone cannot distinguish absence from an inaccessible path.
        File parent = directory.getParentFile();
        if (parent == null) return false;
        try {
            String[] siblings = parent.list();
            if (siblings == null) return false;
            for (String name : siblings) {
                if (directory.getName().equals(name)) return false;
            }
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
