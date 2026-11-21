package com.waenhancer.licensing.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BootloaderSpooferFeatureKeyboxTest {

    @Test
    public void testManagedKeyboxLiveUrlParsing() throws Exception {
        URL url = new URL("https://waex.mubashar.dev/keybox.json");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "WAEX-Android");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int responseCode = conn.getResponseCode();
        org.junit.Assert.assertEquals("Keybox endpoint must return 200", 200, responseCode);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        String jsonText = sb.toString();
        
        // In local JVM unit tests, android.jar's JSONObject is stubbed with default values.
        // Extract the keybox XML string directly:
        int keyboxIndex = jsonText.indexOf("\"keybox\":\"");
        assertTrue("Response must contain \"keybox\":\"", keyboxIndex != -1);
        int startIndex = keyboxIndex + "\"keybox\":\"".length();
        int endIndex = jsonText.indexOf("\",\"last_updated\":", startIndex);
        if (endIndex == -1) {
            endIndex = jsonText.lastIndexOf("\"");
        }
        String rawXmlEscaped = jsonText.substring(startIndex, endIndex);
        String xml = rawXmlEscaped
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");

        assertNotNull("Keybox XML must not be null", xml);
        assertFalse("Keybox XML must not be empty", xml.trim().isEmpty());
        assertTrue("Keybox XML must contain AndroidAttestation tag", xml.contains("<AndroidAttestation>"));

        // Test 1: BootloaderSpooferFeature.parse(xml) validation
        BootloaderSpooferFeature.parse(xml);

        // Test 2: BootloaderSpooferFeature.verify(null, xml, true) validation report
        BootloaderSpooferFeature.VerificationReport report = BootloaderSpooferFeature.verify(null, xml, true);
        assertNotNull("Verification report must not be null", report);

        System.out.println("=== KEYBOX VERIFICATION REPORT ===");
        System.out.println("Source: " + report.source);
        System.out.println("Parsed: " + report.parsed);
        System.out.println("Total Score: " + report.totalScore);
        System.out.println("EC Present: " + report.ecPresent + " | ChainValid: " + report.ecChainValid + " | KeyMatches: " + report.ecKeyMatches + " | Expiry: " + report.ecExpiry);
        System.out.println("RSA Present: " + report.rsaPresent + " | ChainValid: " + report.rsaChainValid + " | NotInKeybox: " + report.rsaNotInKeybox + " | Expiry: " + report.rsaExpiry);
        System.out.println("Basic Integrity: " + report.basicIntegrity + " | Device Integrity: " + report.deviceIntegrity);
        System.out.println("Error: " + report.error);
        System.out.println("===================================");

        assertTrue("Keybox must be parsed successfully: " + report.error, report.parsed);
        assertTrue("EC key must be present in the managed keybox", report.ecPresent);
        assertTrue("EC key must match certificate", report.ecKeyMatches);
        assertTrue("EC chain must be valid", report.ecChainValid);
    }
}
