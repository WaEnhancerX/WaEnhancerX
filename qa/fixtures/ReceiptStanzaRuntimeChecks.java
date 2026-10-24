package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Message;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Synthetic host shapes, real production hook; never a host/network acceptance test. */
public final class ReceiptStanzaRuntimeChecks {
    private static final String PERSON = "1001@s.whatsapp.net";
    private static final String OTHER = "2002@s.whatsapp.net";
    private static final String GROUP = "1001-2002@g.us";
    private static final String BROADCAST = "3003@broadcast";
    private static final Preferences PREFS = new Preferences();
    private static final Generator GENERATOR = new Generator();
    private static final Incoming INCOMING = new Incoming();
    private static final Writer WRITER = new Writer();
    private static Method generate, incoming, execution, dispatch;
    private static Constructor<?> constructor;
    private static int checks, scenarios;
    private static final List<String> failures = new ArrayList<>();

    public static final class Preferences implements SharedPreferences {
        final Map<String,Object> values = new HashMap<>();
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value == null ? fallback : (Boolean) value;
        }
        public boolean contains(String key) { return values.containsKey(key); }
        public Map<String,?> getAll() { return values; }
    }

    public static final class Attribute {
        public final String key;
        public final String value;
        public Attribute(String key, String value) { this.key = key; this.value = value; }
    }

    public static final class Node {
        public final String tag = "receipt";
        public Attribute[] attributes;
        public final Object[] children = {new Object(), new Object()};
        public final byte[] data = {1, 2, 3};
        boolean fail;
        Node(Attribute[] attributes) { this.attributes = attributes; }
    }

    public static final class DeviceJid {}

    public static final class Generator {
        int calls;
        public Node generate(DeviceJid jid, Node node) {
            calls++;
            if (node != null && node.fail) throw new IllegalStateException("fixture generator failure");
            return node;
        }
    }

    public static final class IncomingMessage {
        public String key;
        public String participant;
        IncomingMessage(String key, String participant) { this.key = key; this.participant = participant; }
    }

    public static final class Incoming {
        int calls;
        public void receive(IncomingMessage message) { calls++; }
    }

    public static final class ReadJob {
        public String jid;
        public String participant;
        public String[] ids;
        public int executions;
        public int lifecycleCalls;
        public ReadJob(String jid, String participant, String[] ids) {
            this.jid = jid; this.participant = participant; this.ids = ids;
        }
        public void run() { executions++; }
        public boolean retry() { lifecycleCalls++; return true; }
    }

    public static final class Writer {
        public int calls;
        public Message last;
        public void dispatch(Message message) { calls++; last = message; }
    }

    public static final class Delivery {
        public void combined() {}
        public void ordinary() {}
    }

    public static final class Played {
        public void dispatch(Set<?> messages, boolean flag) {}
    }

    @FunctionalInterface interface Scenario { void run() throws Throwable; }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void scenario(String label, Scenario action) {
        scenarios++;
        PREFS.values.clear();
        CustomPrivacyHook.contacts.clear();
        CustomPrivacyHook.lookups = 0;
        BlueOnReplyHook.captured.clear();
        try { action.run(); }
        catch (Throwable error) {
            failures.add(label + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static Node node(String type, String target, String participant, String id) {
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("to", target));
        attributes.add(new Attribute("id", id));
        if (type != null) attributes.add(new Attribute("type", type));
        if (participant != null) attributes.add(new Attribute("participant", participant));
        attributes.add(new Attribute("sts", "100"));
        attributes.add(new Attribute("t", "200"));
        attributes.add(new Attribute("retry_count", "2"));
        attributes.add(new Attribute("future_attribute", "retained"));
        return new Node(attributes.toArray(new Attribute[0]));
    }

    private static String attribute(Node node, String key) {
        if (node.attributes == null) return null;
        for (Attribute attribute : node.attributes) {
            if (attribute != null && key.equals(attribute.key)) return attribute.value;
        }
        return null;
    }

    private static Object transform(Node node) throws Throwable {
        return XposedBridge.invoke(generate, GENERATOR, new DeviceJid(), node);
    }

    private static List<String> attributeValues(Node node, boolean includePrivacyFields) {
        List<String> values = new ArrayList<>();
        for (Attribute attribute : node.attributes) {
            if (includePrivacyFields || (!"type".equals(attribute.key) && !"sts".equals(attribute.key))) {
                values.add(attribute.key);
                values.add(attribute.value);
            }
        }
        return values;
    }

    private static void expectReadHidden(Node node, String expectedType) throws Throwable {
        Object[] children = node.children;
        Object[] childValues = children.clone();
        byte[] data = node.data;
        byte[] bytes = data.clone();
        List<String> otherAttributes = attributeValues(node, false);
        Attribute to = node.attributes[0], id = node.attributes[1];
        int before = GENERATOR.calls;
        check(transform(node) == node, "generator result object was replaced");
        check(GENERATOR.calls == before + 1, "host generator did not run exactly once");
        check(java.util.Objects.equals(attribute(node, "type"), expectedType), "wrong privacy receipt type");
        check(attribute(node, "sts") == null, "privacy timestamp was retained");
        check(node.attributes[0] == to && node.attributes[1] == id,
                "unrelated recipient/id attribute objects were replaced");
        check(node.children == children && node.data == data,
                "receipt children or binary payload were replaced");
        check(java.util.Arrays.equals(childValues, node.children) && java.util.Arrays.equals(bytes, node.data),
                "receipt children or binary payload were mutated");
        check(otherAttributes.equals(attributeValues(node, false)),
                "unrelated attributes were changed");
    }

    private static void expectUntouched(Node node) throws Throwable {
        Attribute[] attributes = node.attributes;
        List<String> values = attributeValues(node, true);
        Object[] children = node.children;
        Object[] childValues = children.clone();
        byte[] data = node.data;
        byte[] bytes = data.clone();
        check(transform(node) == node, "unchanged receipt result was replaced");
        check(node.attributes == attributes, "control or unrelated receipt attributes were rewritten");
        check(values.equals(attributeValues(node, true)), "control or unrelated receipt attribute values changed");
        check(node.children == children && node.data == data, "control payload changed");
        check(java.util.Arrays.equals(childValues, node.children) && java.util.Arrays.equals(bytes, node.data),
                "control payload contents changed");
    }

    private static ReadJob job(String jid, String participant) throws Throwable {
        return (ReadJob) XposedBridge.construct(constructor, jid, participant, new String[]{"fixture-id"});
    }

    private static void install() throws Throwable {
        generate = Generator.class.getMethod("generate", DeviceJid.class, Node.class);
        incoming = Incoming.class.getMethod("receive", IncomingMessage.class);
        execution = ReadJob.class.getMethod("run");
        dispatch = Writer.class.getMethod("dispatch", Message.class);
        constructor = ReadJob.class.getConstructor(String.class, String.class, String[].class);
        DexSearchEngine.classes.put("wpp_protocol_tree_node_class", Node.class);
        DexSearchEngine.classes.put("wpp_key_value_class", Attribute.class);
        DexSearchEngine.classes.put("wpp_send_read_receipt_job_class", ReadJob.class);
        DexSearchEngine.methods.put("wpp_read_receipt_incoming_message", incoming);
        DexSearchEngine.methods.put("wpp_send_read_receipt_execution_v1", execution);
        DexSearchEngine.methods.put("wpp_receipt_node_generator", generate);
        DexSearchEngine.methods.put("wpp_on_dispatch_message_419", dispatch);
        DexSearchEngine.methods.put("wpp_delivery_anchor_ReadReceipts_sendDeliveryReadReceipt",
                Delivery.class.getMethod("combined"));
        DexSearchEngine.methods.put("wpp_delivery_anchor_ReadReceipts_sendDeliveryReceiptIfNotRetry",
                Delivery.class.getMethod("ordinary"));
        DexSearchEngine.methods.put("wpp_played_batch_dispatch_v1",
                Played.class.getMethod("dispatch", Set.class, boolean.class));
        new HideReceiptsHook(new Context(), ReceiptStanzaRuntimeChecks.class.getClassLoader(), PREFS).hook();
        for (Method method : List.of(generate, incoming, execution, dispatch)) {
            check(XposedBridge.hooks.containsKey(method) && XposedBridge.hooks.get(method).size() == 1,
                    "production callback did not register once: " + method.getName());
        }
        check(XposedBridge.logs.stream().noneMatch(s -> s.contains("Error") || s.contains("unavailable")),
                "production hook installation failed");
    }

    public static void main(String[] ignored) throws Throwable {
        install();
        String[] controlTypes = {"retry", "sender", "read-self", "hist_sync", "peer_msg", "server-error",
                "played", "inactive", "readstatus", "delivered", "future-receipt", ""};
        for (String preference : new String[]{"hide_delivery_receipts", "hidereceipt", "hide_read_receipts",
                "hideread", "ghostmode_active"}) {
            for (String type : controlTypes) {
                scenario(preference + "/preserve-control/" + type, () -> {
                    PREFS.values.put(preference, true);
                    Node node = node(type, PERSON, OTHER, "control-id");
                    expectUntouched(node);
                    check(CustomPrivacyHook.lookups == 0, "control receipt parsed per-contact privacy unnecessarily");
                });
            }
        }
        for (String preference : new String[]{"hide_delivery_receipts", "hidereceipt", "ghostmode_active"}) {
            scenario(preference + "/delivery", () -> {
                PREFS.values.put(preference, true);
                expectReadHidden(node(null, PERSON, null, "delivery-id"), "inactive");
            });
            scenario(preference + "/read", () -> {
                PREFS.values.put(preference, true);
                expectReadHidden(node("read", PERSON, null, "read-id"), "inactive");
            });
        }
        for (String preference : new String[]{"hide_read_receipts", "hideread"}) {
            scenario(preference + "/read", () -> {
                PREFS.values.put(preference, true);
                expectReadHidden(node("read", PERSON, null, "read-id"), null);
            });
            scenario(preference + "/ordinary-delivery", () -> {
                PREFS.values.put(preference, true);
                expectUntouched(node(null, PERSON, null, "delivery-id"));
            });
        }
        scenario("all-toggles-off", () -> expectUntouched(node("read", PERSON, null, "read-id")));
        scenario("group-read-preference", () -> {
            PREFS.values.put("hideread_group", true);
            expectReadHidden(node("read", GROUP, PERSON, "group-id"), null);
            expectUntouched(node("read", PERSON, null, "person-id"));
        });
        scenario("contact-hide-read", () -> {
            CustomPrivacyHook.contacts.put(PERSON, new JSONObject().put("HideSeen", true));
            expectReadHidden(node("read", PERSON, null, "read-id"), null);
            expectUntouched(node("read", OTHER, null, "other-id"));
        });
        scenario("contact-hide-delivery", () -> {
            CustomPrivacyHook.contacts.put(PERSON, new JSONObject().put("HideReceipt", true));
            expectReadHidden(node(null, PERSON, null, "delivery-id"), "inactive");
            expectUntouched(node(null, OTHER, null, "other-id"));
        });
        scenario("contact-exceptions", () -> {
            PREFS.values.put("hide_read_receipts", true);
            PREFS.values.put("hide_delivery_receipts", true);
            CustomPrivacyHook.contacts.put(PERSON, new JSONObject().put("HideSeen", false).put("HideReceipt", false));
            expectUntouched(node("read", PERSON, null, "read-id"));
        });
        scenario("ghost-overrides-contact-exceptions", () -> {
            PREFS.values.put("ghostmode_active", true);
            CustomPrivacyHook.contacts.put(PERSON, new JSONObject().put("HideSeen", false).put("HideReceipt", false));
            expectReadHidden(node("read", PERSON, null, "read-id"), "inactive");
        });
        scenario("exact-explicit-read-grant", () -> {
            PREFS.values.put("hide_read_receipts", true);
            Object[] args = {new String[]{"allowed-id"}};
            HideReceiptsHook.allowExplicitReceiptIds(PERSON, args);
            try {
                expectUntouched(node("read", PERSON, null, "allowed-id"));
                expectReadHidden(node("read", PERSON, null, "other-id"), null);
                expectReadHidden(node("read", OTHER, null, "allowed-id"), null);
                PREFS.values.put("hide_delivery_receipts", true);
                expectReadHidden(node("read", PERSON, null, "allowed-id"), "inactive");
                PREFS.values.clear(); PREFS.values.put("ghostmode_active", true);
                expectReadHidden(node("read", PERSON, null, "allowed-id"), "inactive");
            } finally { HideReceiptsHook.revokeExplicitReceiptIds(PERSON, args); }
            PREFS.values.clear(); PREFS.values.put("hide_read_receipts", true);
            expectReadHidden(node("read", PERSON, null, "allowed-id"), null);
        });
        scenario("numeric-broadcast-is-not-status-stanza", () -> {
            PREFS.values.put("hide_read_receipts", true);
            expectReadHidden(node("read", BROADCAST, PERSON, "broadcast-id"), null);
        });
        scenario("numeric-broadcast-incoming-is-not-status", () -> {
            PREFS.values.put("stealth_status_view", true);
            int before = INCOMING.calls;
            XposedBridge.invoke(incoming, INCOMING, new IncomingMessage(BROADCAST, PERSON));
            check(INCOMING.calls == before + 1, "stealth status cancelled a broadcast-list incoming receipt");
        });
        scenario("numeric-broadcast-job-is-not-status", () -> {
            PREFS.values.put("stealth_status_view", true);
            PREFS.values.put("blueonreply", true);
            ReadJob job = job(BROADCAST, PERSON);
            check(BlueOnReplyHook.captured.equals(List.of(BROADCAST)), "broadcast read job was not captured for its conversation");
            XposedBridge.invoke(execution, job);
            check(job.executions == 1, "stealth status cancelled a broadcast-list job");
        });
        scenario("status-substring-is-not-address", () -> {
            PREFS.values.put("hide_read_receipts", true);
            expectReadHidden(node("read", "not-a-status-address", null, "read-id"), null);
        });
        scenario("exact-status-is-suppressed-upstream", () -> {
            PREFS.values.put("stealth_status_view", true);
            int before = INCOMING.calls;
            XposedBridge.invoke(incoming, INCOMING, new IncomingMessage("status@broadcast", PERSON));
            check(INCOMING.calls == before, "exact status incoming receipt escaped stealth mode");
            ReadJob job = job("status@broadcast", PERSON);
            XposedBridge.invoke(execution, job);
            check(job.executions == 0, "exact status job escaped stealth mode");
            check(BlueOnReplyHook.captured.isEmpty(), "status job entered ordinary blue-reply capture");
            expectUntouched(node("read", "status@broadcast", PERSON, "status-id"));
        });
        scenario("exact-status-participant", () -> {
            PREFS.values.put("stealth_status_view", true);
            ReadJob job = job(PERSON, "status@broadcast");
            XposedBridge.invoke(execution, job);
            check(job.executions == 0, "exact status participant escaped stealth mode");
            PREFS.values.put("hide_delivery_receipts", true);
            expectUntouched(node("read", PERSON, "status@broadcast", "status-id"));
        });
        scenario("read-only-incoming-keeps-delivery-path", () -> {
            PREFS.values.put("hide_read_receipts", true);
            int before = INCOMING.calls;
            XposedBridge.invoke(incoming, INCOMING, new IncomingMessage(PERSON, null));
            check(INCOMING.calls == before + 1, "ordinary incoming receipt path was cancelled by read privacy");
        });
        scenario("delivery-job-only-execution-cancelled", () -> {
            PREFS.values.put("hide_delivery_receipts", true);
            ReadJob job = job(PERSON, null);
            XposedBridge.invoke(execution, job);
            check(job.executions == 0, "known read-job execution was not suppressed for hide delivery");
            check((Boolean) XposedBridge.invoke(ReadJob.class.getMethod("retry"), job), "job retry result changed");
            check(job.lifecycleCalls == 1, "job retry lifecycle was cancelled");
        });
        scenario("writer-keeps-job-89-and-unrelated-codes", () -> {
            PREFS.values.put("hide_delivery_receipts", true);
            for (int code : new int[]{0, 1, 5, 89, 418, 420, 999}) {
                Message message = new Message();
                message.what = 7; message.arg1 = code; message.arg2 = 11;
                Object payload = new Object(); message.obj = payload;
                int before = WRITER.calls;
                XposedBridge.invoke(dispatch, WRITER, message);
                check(WRITER.calls == before + 1 && WRITER.last == message, "writer path was cancelled");
                check(message.arg1 == code && message.what == 7 && message.arg2 == 11 && message.obj == payload,
                        "job or unrelated writer message was changed");
            }
        });
        scenario("known-writer-419-remains-existing-policy", () -> {
            PREFS.values.put("hide_delivery_receipts", true);
            Message message = new Message(); message.arg1 = 419;
            Object payload = new Object(); message.obj = payload;
            XposedBridge.invoke(dispatch, WRITER, message);
            check(message.arg1 == -1 && message.obj == payload, "existing 419 policy changed without a replacement contract");
        });
        scenario("null-result-and-generator-failure", () -> {
            PREFS.values.put("ghostmode_active", true);
            check(transform(null) == null, "null result was replaced");
            Node failing = node("read", PERSON, null, "failure-id"); failing.fail = true;
            try { transform(failing); throw new AssertionError("host failure was hidden"); }
            catch (IllegalStateException expected) {
                check("fixture generator failure".equals(expected.getMessage()), "host failure changed");
            }
        });
        for (String failure : failures) System.out.println("FAIL " + failure);
        System.out.println("Receipt stanza runtime: " + scenarios + " scenarios, " + checks
                + " checks, " + failures.size() + " failed scenarios; complete production hook and BaseFeature.");
        if (!failures.isEmpty()) throw new AssertionError("Receipt stanza invariant failures: " + failures.size());
    }
}
