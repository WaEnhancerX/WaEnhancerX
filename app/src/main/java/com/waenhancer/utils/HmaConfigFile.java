package com.waenhancer.utils;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded snapshots and checked atomic replacements for already-discovered HMA config files. */
public final class HmaConfigFile {
    // Keep the base64 write command below Linux's per-argument size limit.
    public static final int MAX_CONFIG_BYTES = 64 * 1024;
    public static final int MAX_COMMAND_OUTPUT = 96 * 1024;
    public static final String WRITE_ACK = "WAEX_HMA_CONFIG_WRITTEN";
    public static final String BACKUP_ACK = "WAEX_HMA_CONFIG_BACKED_UP";
    public static final String RESTORE_ACK = "WAEX_HMA_CONFIG_RESTORED";

    public static final class Snapshot {
        public final String path;
        public final String digest;
        public final String json;
        Snapshot(String path, String digest, String json) {
            this.path = path; this.digest = digest; this.json = json;
        }
    }

    private HmaConfigFile() {}

    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static boolean isConfigPath(String path) {
        if (path == null || path.length() > 4096 || !path.endsWith("/config.json")
                || path.contains("/../") || path.contains("/./") || path.contains("//")) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !parts[0].isEmpty() || !"data".equals(parts[1])) return false;
        for (String part : parts) if (part.length() > 255) return false;
        // The inspected legacy and OSS services store this file directly below
        // /data/misc/hide_my_applist*, migrating the same directory from /data/system.
        // An HMA-looking descendant of another service's directory is not a config owner.
        if (("misc".equals(parts[2]) || "system".equals(parts[2])) && parts.length == 5) {
            return parts[3].startsWith("hide_my_applist");
        }
        // A known manager's private file is a configuration candidate, not proof
        // that the HMA service imported it. Callers must also check the user scope.
        // Keep the owner-user alias because canonicalization can return /data/data.
        if ("data".equals(parts[2]) && parts.length == 6) {
            return HmaConfigPolicy.isManagerPackage(parts[3]) && "files".equals(parts[4]);
        }
        return parts.length == 7 && "user".equals(parts[2]) && isCanonicalUserId(parts[3])
                && HmaConfigPolicy.isManagerPackage(parts[4]) && "files".equals(parts[5]);
    }

    /** Global service stores are shared; private manager candidates belong to one user. */
    public static boolean isConfigPathForUser(String path, int userId) {
        if (userId < 0 || !isConfigPath(path)) return false;
        if (path.startsWith("/data/user/")) {
            return path.startsWith("/data/user/" + userId + "/");
        }
        if (path.startsWith("/data/data/")) return userId == 0;
        return true;
    }

    private static boolean isCanonicalUserId(String value) {
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        try {
            return Integer.parseInt(value) >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    public static String readCommand(String path) {
        if (!isConfigPath(path)) throw new IllegalArgumentException("Unsupported HMA config path");
        String p = quote(path);
        return "[ -f " + p + " ] && [ ! -L " + p + " ] && "
                + "waex_real=$(readlink -f " + p + ") && [ \"$waex_real\" = " + p + " ] && "
                + "waex_size=$(stat -c '%s' " + p + ") && "
                + "[ \"$waex_size\" -le " + MAX_CONFIG_BYTES + " ] && "
                + "sha256sum " + p + " && base64 " + p;
    }

    public static Snapshot parseSnapshot(String path, String output) {
        if (!isConfigPath(path) || output == null) return null;
        int end = output.indexOf('\n');
        if (end < 64) return null;
        String expected = output.substring(0, 64);
        if (!expected.matches("[0-9a-f]{64}")) return null;
        try {
            byte[] bytes = Base64.decode(output.substring(end + 1), Base64.DEFAULT);
            if (bytes.length > MAX_CONFIG_BYTES || !expected.equals(sha256(bytes))) return null;
            return new Snapshot(path, expected, new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    public static String writeCommand(Snapshot snapshot, String json) {
        requireSnapshot(snapshot);
        if (json == null) throw new IllegalArgumentException("Missing HMA replacement");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONFIG_BYTES) throw new IllegalArgumentException("HMA config too large");
        String p = quote(snapshot.path);
        String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        String expected = sha256(bytes);
        // Every operation before rename must succeed. The final acknowledgement is
        // emitted only after the replaced file has the exact expected content.
        return "set -e\n"
                + verifiedFile(snapshot.path + ".waex.bak", snapshot.digest)
                + "[ -f " + p + " ]\n"
                + "[ ! -L " + p + " ]\n"
                + "waex_real=$(readlink -f " + p + ")\n"
                + "[ \"$waex_real\" = " + p + " ]\n"
                + "waex_before=$(sha256sum " + p + ")\n"
                + "[ \"${waex_before%% *}\" = " + quote(snapshot.digest) + " ]\n"
                + "waex_tmp=$(mktemp " + quote(snapshot.path + ".waex.XXXXXX") + ")\n"
                + "trap 'rm -f -- \"$waex_tmp\"' EXIT\n"
                + "trap 'exit 1' HUP INT TERM\n"
                + "waex_owner=$(stat -c '%u:%g' " + p + ")\n"
                + "waex_mode=$(stat -c '%a' " + p + ")\n"
                + "waex_label=$(ls -Zd " + p + ")\n"
                + "waex_label=${waex_label%% *}\n"
                + "printf '%s' " + quote(encoded) + " | base64 -d > \"$waex_tmp\"\n"
                + "waex_encoded=$(sha256sum \"$waex_tmp\")\n"
                + "[ \"${waex_encoded%% *}\" = " + quote(expected) + " ]\n"
                + "chown \"$waex_owner\" \"$waex_tmp\"\n"
                + "chmod \"$waex_mode\" \"$waex_tmp\"\n"
                + "case \"$waex_label\" in ''|'?') ;; *) chcon \"$waex_label\" \"$waex_tmp\" ;; esac\n"
                + "waex_before=$(sha256sum " + p + ")\n"
                + "[ \"${waex_before%% *}\" = " + quote(snapshot.digest) + " ]\n"
                + "waex_real=$(readlink -f " + p + ")\n"
                + "[ \"$waex_real\" = " + p + " ]\n"
                + "mv -f -- \"$waex_tmp\" " + p + "\n"
                + "waex_after=$(sha256sum " + p + ")\n"
                + "[ \"${waex_after%% *}\" = " + quote(expected) + " ]\n"
                + "printf '%s' " + quote(WRITE_ACK);
    }

    /** Preserve the exact pre-edit bytes and metadata before touching the live file. */
    public static String backupCommand(Snapshot snapshot) {
        requireSnapshot(snapshot);
        String backup = snapshot.path + ".waex.bak";
        return "set -e\n"
                + verifiedFile(snapshot.path, snapshot.digest)
                + backupDestination(backup)
                + copyToTemporary(snapshot.path, backup + ".XXXXXX", snapshot.digest)
                + verifiedFile(snapshot.path, snapshot.digest)
                + backupDestination(backup)
                + "mv -f -- \"$waex_tmp\" " + quote(backup) + "\n"
                + verifiedFile(backup, snapshot.digest)
                + "printf '%s' " + quote(BACKUP_ACK);
    }

    /**
     * Restore only a verified backup over this attempt's exact replacement.
     * A different live digest belongs to an unverified writer and is left alone.
     */
    public static String restoreCommand(Snapshot snapshot, String attemptedJson) {
        requireSnapshot(snapshot);
        if (attemptedJson == null) throw new IllegalArgumentException("Missing HMA replacement");
        byte[] attemptedBytes = attemptedJson.getBytes(StandardCharsets.UTF_8);
        if (attemptedBytes.length > MAX_CONFIG_BYTES) throw new IllegalArgumentException("HMA config too large");
        String backup = snapshot.path + ".waex.bak";
        String current = quote(snapshot.path);
        String replacementDigest = sha256(attemptedBytes);
        return "set -e\n"
                + verifiedFile(backup, snapshot.digest)
                + canonicalFile(snapshot.path)
                + "waex_hash=$(sha256sum " + current + ")\n"
                + "if [ \"${waex_hash%% *}\" = " + quote(snapshot.digest) + " ]; then "
                + "printf '%s' " + quote(RESTORE_ACK) + "; exit 0; fi\n"
                + verifiedFile(snapshot.path, replacementDigest)
                + copyToTemporary(backup, snapshot.path + ".waex.restore.XXXXXX", snapshot.digest)
                + verifiedFile(backup, snapshot.digest)
                + verifiedFile(snapshot.path, replacementDigest)
                + "mv -f -- \"$waex_tmp\" " + current + "\n"
                + verifiedFile(snapshot.path, snapshot.digest)
                + "printf '%s' " + quote(RESTORE_ACK);
    }

    private static void requireSnapshot(Snapshot snapshot) {
        if (snapshot == null || !isConfigPath(snapshot.path) || snapshot.digest == null
                || !snapshot.digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Missing verified HMA snapshot");
        }
    }

    private static String canonicalFile(String path) {
        String p = quote(path);
        return "[ -f " + p + " ]\n[ ! -L " + p + " ]\n"
                + "waex_real=$(readlink -f " + p + ")\n[ \"$waex_real\" = " + p + " ]\n";
    }

    private static String verifiedFile(String path, String digest) {
        return canonicalFile(path) + "waex_hash=$(sha256sum " + quote(path) + ")\n"
                + "[ \"${waex_hash%% *}\" = " + quote(digest) + " ]\n";
    }

    private static String backupDestination(String path) {
        String p = quote(path);
        return "[ ! -L " + p + " ]\nif [ -e " + p + " ]; then\n"
                + canonicalFile(path) + "fi\n";
    }

    private static String copyToTemporary(String source, String template, String digest) {
        String p = quote(source);
        return "waex_tmp=$(mktemp " + quote(template) + ")\n"
                + "trap 'rm -f -- \"$waex_tmp\"' EXIT\ntrap 'exit 1' HUP INT TERM\n"
                + "cp -p -- " + p + " \"$waex_tmp\"\n"
                + "waex_label=$(ls -Zd " + p + ")\nwaex_label=${waex_label%% *}\n"
                + "case \"$waex_label\" in ''|'?') ;; *) chcon \"$waex_label\" \"$waex_tmp\" ;; esac\n"
                + "waex_hash=$(sha256sum \"$waex_tmp\")\n"
                + "[ \"${waex_hash%% *}\" = " + quote(digest) + " ]\n";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
