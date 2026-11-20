import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.util.DisplayMetrics;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads an isolated, uninstalled AAPT2 table using the real Android framework.
 * No app Context, account, provider, SharedPreferences, or global setting is used.
 */
public final class WaexLocaleTableProbe {
    private static final String OWNER = "com.waenhancer";
    private static final String[] LOCALES = {"es", "es-419", "es-MX", "es-ES", "es-CL", "en"};
    private static int stringReads;
    private static int formatReads;
    private static int pluralReads;

    private static final class CheckFailure extends AssertionError {
        final String expectedBase64, actualBase64;
        CheckFailure(String marker) { this(marker, null, null); }
        CheckFailure(String marker, String expected, String actual) {
            super(marker);
            expectedBase64 = expected == null ? null : Base64.getEncoder().encodeToString(expected.getBytes(StandardCharsets.UTF_8));
            actualBase64 = actual == null ? null : Base64.getEncoder().encodeToString(actual.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Row {
        final String kind, name, quantity, english, spanish, types;
        Row(String line) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 6) throw new IllegalArgumentException("ROW_WIDTH");
            kind = parts[0]; name = parts[1]; quantity = parts[2];
            english = decode(parts[3]); spanish = decode(parts[4]); types = parts[5];
        }
        String value(String tag) { return tag.equals("en") ? english : spanish; }
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String marker) {
        if (!condition) throw new CheckFailure(marker);
    }

    private static void equal(String expected, String actual, String marker) {
        if (!expected.equals(actual)) throw new CheckFailure(marker, expected, actual);
    }

    private static Resources configured(AssetManager assets, String tag) {
        Configuration config = new Configuration();
        config.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return new Resources(assets, metrics, config);
    }

    private static int id(Resources resources, Row row) {
        int id = resources.getIdentifier(row.name, row.kind, OWNER);
        require(id != 0, "MISSING_ID:" + row.name);
        equal(OWNER, resources.getResourcePackageName(id), "RESOURCE_OWNER:" + row.name);
        return id;
    }

    private static Object[] arguments(String codes, int pluralCount) {
        Object[] values = new Object[codes.length()];
        for (int i = 0; i < codes.length(); i++) {
            switch (codes.charAt(i)) {
                case 'd': values[i] = Long.valueOf(pluralCount >= 0 ? pluralCount : 19007001L + i); break;
                case 'f': values[i] = Double.valueOf(321.25 + i); break;
                case 't': values[i] = Long.valueOf(1700000000000L); break;
                case 'c': values[i] = Integer.valueOf(65 + i); break;
                case 's': values[i] = "ARG" + (i + 1) + "_%2$s_ñ_\""; break;
                default: throw new IllegalArgumentException("ARGUMENT_TYPE");
            }
        }
        return values;
    }

    private static Row find(List<Row> rows, String name, String quantity) {
        for (Row row : rows) if (row.name.equals(name) && row.quantity.equals(quantity)) return row;
        throw new IllegalArgumentException("EXPECTED_TEST_RESOURCE_MISSING:" + name);
    }

    private static void negative(String mode, AssetManager assets, List<Row> rows) {
        Resources resources = configured(assets, "es-419");
        if (mode.equals("negative-expected")) {
            Row row = find(rows, "report_issue_title", "");
            equal("__INTENTIONALLY_WRONG_SPANISH_EXPECTATION__", resources.getString(id(resources, row)), "NEGATIVE_EXPECTED_VALUE");
        } else if (mode.equals("negative-owner")) {
            Row row = find(rows, "report_issue_title", "");
            equal("com.whatsapp", resources.getResourcePackageName(id(resources, row)), "NEGATIVE_RESOURCE_OWNER");
        } else if (mode.equals("negative-plural")) {
            Row one = find(rows, "waex_recordings_deleted", "one");
            equal(one.spanish, resources.getQuantityString(id(resources, one), 2), "NEGATIVE_PLURAL_SELECTION");
        } else if (mode.equals("negative-format")) {
            Row row = find(rows, "custom_emojis_downloading", "");
            // The expected value deliberately retains the unexpanded template.
            // The real Android resource overload must instead format it.
            equal(row.spanish, resources.getString(id(resources, row), Long.valueOf(19)), "NEGATIVE_FORMAT_EXPANSION");
        } else {
            throw new IllegalArgumentException("UNKNOWN_MODE");
        }
        throw new IllegalStateException("NEGATIVE_CONTROL_SURVIVED");
    }

    private static void run(String apk, String input, String mode) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.isEmpty()) rows.add(new Row(line));
        }
        require(!rows.isEmpty(), "EMPTY_INPUT");
        Locale globalBefore = Locale.getDefault();
        String systemLocalesBefore = Resources.getSystem().getConfiguration().getLocales().toLanguageTags();
        Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AssetManager assets = constructor.newInstance();
        try {
            Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            add.setAccessible(true);
            int cookie = ((Integer) add.invoke(assets, apk)).intValue();
            require(cookie > 0, "ASSET_PATH_NOT_LOADED");
            if (!mode.equals("candidate")) {
                negative(mode, assets, rows);
                return;
            }
            Map<String, Map<String, Row>> plurals = new LinkedHashMap<>();
            for (Row row : rows) if (row.kind.equals("plurals")) {
                Map<String, Row> forms = plurals.get(row.name);
                if (forms == null) { forms = new LinkedHashMap<>(); plurals.put(row.name, forms); }
                require(forms.put(row.quantity, row) == null, "DUPLICATE_PLURAL_INPUT");
            }
            for (String tag : LOCALES) {
                Resources resources = configured(assets, tag);
                Locale locale = Locale.forLanguageTag(tag);
                for (Row row : rows) if (row.kind.equals("string")) {
                    int key = id(resources, row);
                    equal(row.value(tag), resources.getString(key), "STRING_VALUE:" + tag + ":" + row.name);
                    stringReads++;
                    if (!row.types.isEmpty()) {
                        Object[] values = arguments(row.types, -1);
                        String expected = String.format(locale, row.value(tag), values);
                        equal(expected, resources.getString(key, values), "FORMATTED_VALUE:" + tag + ":" + row.name);
                        formatReads++;
                    }
                }
                for (Map<String, Row> forms : plurals.values()) {
                    require(forms.size() == 2 && forms.containsKey("one") && forms.containsKey("other"), "PLURAL_INPUT_SHAPE");
                    for (int count : new int[]{0, 1, 2, 99}) {
                        Row row = forms.get(count == 1 ? "one" : "other");
                        int key = id(resources, row);
                        equal(row.value(tag), resources.getQuantityString(key, count), "PLURAL_VALUE:" + tag + ":" + count);
                        Object[] values = arguments(row.types, count);
                        equal(String.format(locale, row.value(tag), values), resources.getQuantityString(key, count, values), "PLURAL_FORMAT:" + tag + ":" + count);
                        pluralReads++;
                    }
                }
            }
            equal(globalBefore.toLanguageTag(), Locale.getDefault().toLanguageTag(), "GLOBAL_LOCALE_CHANGED");
            equal(systemLocalesBefore, Resources.getSystem().getConfiguration().getLocales().toLanguageTags(), "SYSTEM_CONFIGURATION_CHANGED");
            System.out.println("WAEX_PROBE_RESULT={\"status\":\"PASS\",\"mode\":\"candidate\",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"locale_count\":" + LOCALES.length + ",\"string_reads\":" + stringReads
                + ",\"formatted_string_reads\":" + formatReads + ",\"plural_selections\":" + pluralReads
                + ",\"global_locale_unchanged\":true,\"system_locale_unchanged\":true}");
        } finally {
            assets.close();
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("EXPECTED_APK_INPUT_MODE");
            run(args[0], args[1], args[2]);
        } catch (Throwable failure) {
            String marker = failure.getMessage();
            if (marker == null) marker = "NO_MESSAGE";
            marker = marker.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
            String values = "";
            if (failure instanceof CheckFailure) {
                CheckFailure check = (CheckFailure) failure;
                if (check.expectedBase64 != null && check.actualBase64 != null) {
                    values = ",\"expected_base64\":\"" + check.expectedBase64 + "\",\"actual_base64\":\"" + check.actualBase64 + "\"";
                }
            }
            System.out.println("WAEX_PROBE_RESULT={\"status\":\"FAIL\",\"failure_class\":\"" + failure.getClass().getName()
                + "\",\"marker\":\"" + marker + "\"" + values + "}");
            failure.printStackTrace(System.out);
            System.exit(3);
        }
    }
}
