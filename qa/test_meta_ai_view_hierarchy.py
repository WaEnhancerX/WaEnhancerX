#!/usr/bin/env python3
"""Compile the production Meta AI hook against explicit JVM UI/Xposed doubles.

The ViewStub double models the documented parent/replacement/LayoutParams contract:
https://developer.android.com/reference/android/view/ViewStub
This exercises real hook selection/traversal; it is not Android or WhatsApp acceptance.
"""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "app/src/main/java/com/waenhancer/xposed/features/homescreen/ChatListCustomizationsHook.java"

STUBS = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/content/SharedPreferences.java": """package android.content;
        public interface SharedPreferences { boolean getBoolean(String key, boolean fallback); }""",
    "android/content/Intent.java": """package android.content;
        public class Intent {
            public android.os.Bundle getExtras() { return null; }
            public String getStringExtra(String key) { return null; }
        }""",
    "android/os/Bundle.java": """package android.os;
        public class Bundle { public boolean containsKey(String key) { return false; } }""",
    "android/content/res/Resources.java": """package android.content.res;
        public class Resources { public static class NotFoundException extends RuntimeException {} }""",
    "android/view/ViewParent.java": "package android.view; public interface ViewParent {}",
    "android/view/View.java": """package android.view;
        public class View {
            public static final int NO_ID = -1, VISIBLE = 0, INVISIBLE = 4, GONE = 8;
            private static final java.util.ArrayDeque<Runnable> posted = new java.util.ArrayDeque<>();
            private int id = NO_ID, visibility = VISIBLE;
            private CharSequence description;
            private ViewGroup.LayoutParams params;
            public String entryName;
            ViewParent parent;
            public int getId() { return id; }
            public void setId(int value) { id = value; }
            public int getVisibility() { return visibility; }
            public void setVisibility(int value) { visibility = value; }
            public CharSequence getContentDescription() { return description; }
            public void setContentDescription(CharSequence value) { description = value; }
            public ViewParent getParent() { return parent; }
            public ViewGroup.LayoutParams getLayoutParams() { return params; }
            public void setLayoutParams(ViewGroup.LayoutParams value) { params = value; }
            public boolean post(Runnable work) { posted.addLast(work); return true; }
            public void setTag(Object value) {}
            public static void drainPosted() {
                int budget = 100;
                while (!posted.isEmpty()) {
                    if (--budget < 0) throw new AssertionError("Unbounded posted work");
                    posted.removeFirst().run();
                }
            }
            public static void clearPosted() { posted.clear(); }
        }""",
    "android/view/ViewGroup.java": """package android.view;
        public class ViewGroup extends View implements ViewParent {
            private final java.util.List<View> children = new java.util.ArrayList<>();
            public void addView(View view) { addView(view, children.size(), view.getLayoutParams()); }
            public void addView(View view, int index, LayoutParams params) {
                if (view.parent != null) throw new IllegalStateException("View already has a parent");
                children.add(index, view); view.parent = this; view.setLayoutParams(params);
            }
            public void removeView(View view) { if (children.remove(view)) view.parent = null; }
            public int indexOfChild(View view) { return children.indexOf(view); }
            public int getChildCount() { return children.size(); }
            public View getChildAt(int index) { return children.get(index); }
            public static class LayoutParams {
                public int width, height;
                public LayoutParams(int width, int height) { this.width = width; this.height = height; }
            }
            public static class MarginLayoutParams extends LayoutParams {
                public int leftMargin, topMargin, rightMargin, bottomMargin;
                public MarginLayoutParams(int width, int height) { super(width, height); }
                public void setMargins(int left, int top, int right, int bottom) {
                    leftMargin = left; topMargin = top; rightMargin = right; bottomMargin = bottom;
                }
            }
        }""",
    "android/view/ViewStub.java": """package android.view;
        public class ViewStub extends View {
            public View inflated;
            public ViewStub() { super.setVisibility(GONE); }
            public View inflate() {
                if (!(getParent() instanceof ViewGroup)) {
                    throw new IllegalStateException("ViewStub must have a non-null ViewGroup viewParent");
                }
                ViewGroup owner = (ViewGroup) getParent();
                int index = owner.indexOfChild(this);
                ViewGroup.LayoutParams params = getLayoutParams();
                inflated = new View();
                owner.removeView(this);
                owner.addView(inflated, index, params);
                return inflated;
            }
            @Override public void setVisibility(int visibility) {
                if (inflated != null) { inflated.setVisibility(visibility); return; }
                super.setVisibility(visibility);
                if (visibility == VISIBLE || visibility == INVISIBLE) inflate();
            }
        }""",
    "android/view/Window.java": """package android.view;
        public class Window {
            private final View decor;
            public Window(View decor) { this.decor = decor; }
            public View getDecorView() { return decor; }
        }""",
    "android/app/Activity.java": """package android.app;
        public class Activity extends android.content.Context {
            public android.view.Window window;
            public android.content.Intent getIntent() { return null; }
            public android.view.Window getWindow() { return window; }
            public void finish() {}
        }""",
    "de/robv/android/xposed/XC_MethodHook.java": """package de.robv.android.xposed;
        public class XC_MethodHook {
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
            public static class MethodHookParam {
                public Object thisObject; public Object[] args = new Object[0];
                public void setResult(Object value) {}
            }
        }""",
    "de/robv/android/xposed/XposedBridge.java": """package de.robv.android.xposed;
        public class XposedBridge {
            public static void hookAllMethods(Class<?> type, String name, XC_MethodHook callback) {}
            public static void hookAllConstructors(Class<?> type, XC_MethodHook callback) {}
            public static void log(String message) {}
        }""",
    "de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
        public class XposedHelpers {
            private static final java.util.Map<String, XC_MethodHook> callbacks = new java.util.HashMap<>();
            public static Class<?> findClassIfExists(String name, ClassLoader loader) { return Object.class; }
            public static void findAndHookMethod(Class<?> type, String name, Object... arguments) {
                callbacks.put(name, (XC_MethodHook) arguments[arguments.length - 1]);
            }
            public static void after(String name, Object owner) throws Throwable {
                XC_MethodHook callback = callbacks.get(name);
                if (callback != null) {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.thisObject = owner; callback.afterHookedMethod(param);
                }
            }
            public static void reset() { callbacks.clear(); }
        }""",
    "com/waenhancer/xposed/core/BaseFeature.java": """package com.waenhancer.xposed.core;
        public abstract class BaseFeature {
            protected final android.content.Context context;
            protected final ClassLoader classLoader;
            protected final android.content.SharedPreferences prefs;
            protected static final de.robv.android.xposed.XposedBridge XposedBridge = new de.robv.android.xposed.XposedBridge();
            protected static final de.robv.android.xposed.XposedHelpers XposedHelpers = new de.robv.android.xposed.XposedHelpers();
            protected BaseFeature(android.content.Context c, ClassLoader l, android.content.SharedPreferences p) {
                context = c; classLoader = l; prefs = p;
            }
            protected boolean isEnabled(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
            public abstract void hook();
            public abstract String getName();
        }""",
    "com/waenhancer/xposed/core/ViewHookDispatcher.java": """package com.waenhancer.xposed.core;
        public class ViewHookDispatcher {
            public interface Listener { void onView(android.view.View view); }
            private static Listener listener;
            public static void onAttached(Object owner, Listener callback) { listener = callback; }
            public static void attach(android.view.View view) { if (listener != null) listener.onView(view); }
            public static void reset() { listener = null; }
        }""",
    "com/waenhancer/xposed/utils/ActivityTracker.java": """package com.waenhancer.xposed.utils;
        public class ActivityTracker {
            public static android.app.Activity current;
            public static android.app.Activity getCurrentActivity() { return current; }
        }""",
    "com/waenhancer/xposed/utils/HostClassNames.java": """package com.waenhancer.xposed.utils;
        public class HostClassNames {
            public static boolean simpleNameContains(Class<?> type, String text) { return type.getSimpleName().contains(text); }
        }""",
    "com/waenhancer/xposed/utils/HostResourceIds.java": """package com.waenhancer.xposed.utils;
        public class HostResourceIds {
            public static String entryName(android.view.View view, int id) {
                if (view.entryName == null) throw new android.content.res.Resources.NotFoundException();
                return view.entryName;
            }
        }""",
    "com/waenhancer/xposed/utils/HookErrors.java": """package com.waenhancer.xposed.utils;
        public class HookErrors { public static void swallowed(String tag, Throwable error) {} }""",
    "org/luckypray/dexkit/query/FindClass.java": """package org.luckypray.dexkit.query;
        public class FindClass {
            public static FindClass create() { return new FindClass(); }
            public FindClass matcher(Object matcher) { return this; }
        }""",
    "org/luckypray/dexkit/query/enums/StringMatchType.java": "package org.luckypray.dexkit.query.enums; public enum StringMatchType { EndsWith }",
    "org/luckypray/dexkit/query/matchers/ClassMatcher.java": """package org.luckypray.dexkit.query.matchers;
        public class ClassMatcher {
            public static ClassMatcher create() { return new ClassMatcher(); }
            public ClassMatcher className(String name, Object mode) { return this; }
        }""",
    "org/luckypray/dexkit/result/ClassData.java": """package org.luckypray.dexkit.result;
        public class ClassData { public Class<?> getInstance(ClassLoader loader) { return Object.class; } }""",
    "com/waenhancer/xposed/core/devkit/DexSearchEngine.java": """package com.waenhancer.xposed.core.devkit;
        public class DexSearchEngine {
            public interface Query { Class<?> find(Bridge bridge, ClassLoader loader); }
            public static class Bridge { public Found findClass(Object query) { return new Found(); } }
            public static class Found { public org.luckypray.dexkit.result.ClassData firstOrNull() { return null; } }
            public static DexSearchEngine getInstance() { return new DexSearchEngine(); }
            public Class<?> findClassWithCache(Object context, ClassLoader loader, String key, Query query) { return null; }
        }""",
}

HARNESS = r"""import android.view.*;
import android.content.SharedPreferences;
import com.waenhancer.xposed.core.ViewHookDispatcher;
import com.waenhancer.xposed.features.homescreen.ChatListCustomizationsHook;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XposedHelpers;

public class TestMetaAiHierarchy {
    interface Case { void run() throws Throwable; }
    static int passed, failed;
    static class HomeActivity extends android.app.Activity {}
    static class Preferences extends java.util.HashMap<String, Boolean> implements SharedPreferences {
        public boolean getBoolean(String key, boolean fallback) { return getOrDefault(key, fallback); }
    }
    static class Fixture {
        final ViewGroup root = new ViewGroup();
        final Preferences prefs = new Preferences();
        final HomeActivity home = new HomeActivity();
        Fixture(boolean enabled, boolean fab, boolean entryPoints) {
            View.clearPosted(); ViewHookDispatcher.reset(); XposedHelpers.reset();
            prefs.put("metaai", enabled); prefs.put("metaai_remove_home_fab", fab);
            prefs.put("metaai_remove_entry_points", entryPoints);
            home.window = new Window(root); ActivityTracker.current = home;
            new ChatListCustomizationsHook(home, getClass().getClassLoader(), prefs).hook();
        }
        void scan() throws Throwable { XposedHelpers.after("onPostResume", home); View.drainPosted(); }
        void attach(View view) { ViewHookDispatcher.attach(view); View.drainPosted(); }
    }
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    static void test(String label, Case body) {
        try { body.run(); passed++; }
        catch (Throwable error) { failed++; System.out.println("FAIL: " + label + ": " + error); }
    }
    static <T extends View> T named(T view, String name) { view.setId(100); view.entryName = name; return view; }
    static ViewGroup.MarginLayoutParams dimensions() {
        ViewGroup.MarginLayoutParams p = new ViewGroup.MarginLayoutParams(240, 48);
        p.setMargins(3, 5, 7, 11); return p;
    }
    static void dimensionsIntact(View view, ViewGroup.MarginLayoutParams params) {
        check(view.getLayoutParams() == params, "original LayoutParams identity");
        check(params.width == 240 && params.height == 48, "original width and height");
        check(params.leftMargin == 3 && params.topMargin == 5
                && params.rightMargin == 7 && params.bottomMargin == 11, "original margins");
    }
    public static void main(String[] args) {
        test("negative control rejects a detached ViewStub", () -> {
            try { new ViewStub().inflate(); throw new AssertionError("detached stub was accepted"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("viewParent"), "parent contract"); }
        });
        for (boolean attach : new boolean[] {false, true}) {
            test("host can inflate after " + (attach ? "attachment" : "resume scan"), () -> {
                Fixture f = new Fixture(true, true, true);
                ViewStub stub = named(new ViewStub(), "meta_ai_search_button");
                ViewGroup.MarginLayoutParams params = dimensions(); stub.setLayoutParams(params);
                f.root.addView(stub);
                if (attach) f.attach(stub); else f.scan();
                View replacement = stub.inflate();
                check(replacement.getParent() == f.root, "replacement retains host parent");
                check(f.root.getChildAt(0) == replacement, "replacement retains child position");
                dimensionsIntact(replacement, params);
            });
        }
        test("host visibility-triggered inflation still works", () -> {
            Fixture f = new Fixture(true, true, true);
            ViewStub stub = named(new ViewStub(), "meta_ai_entry_point");
            stub.setLayoutParams(dimensions()); f.root.addView(stub); f.scan();
            stub.setVisibility(View.VISIBLE);
            check(stub.inflated != null && stub.inflated.getParent() == f.root, "visible inflates in host parent");
        });
        test("hidden view retains geometry and parent through repeated scans", () -> {
            Fixture f = new Fixture(true, true, true);
            View view = named(new View(), "meta_ai_fab");
            ViewGroup.MarginLayoutParams params = dimensions(); view.setLayoutParams(params);
            f.root.addView(view); f.scan(); f.scan();
            dimensionsIntact(view, params);
            check(view.getParent() == f.root && f.root.getChildCount() == 1, "host hierarchy unchanged");
            check(view.getVisibility() == View.GONE, "assistant remains hidden");
        });
        test("posted work cannot remove a view reparented by the host", () -> {
            Fixture f = new Fixture(true, true, true);
            View view = named(new View(), "meta_ai_entry_point"); f.root.addView(view);
            ViewHookDispatcher.attach(view);
            ViewGroup newParent = new ViewGroup(); f.root.removeView(view); newParent.addView(view);
            View.drainPosted(); check(view.getParent() == newParent, "new host parent survives");
        });
        for (boolean fab : new boolean[] {false, true}) {
            for (boolean entry : new boolean[] {false, true}) {
                final boolean hideFab = fab, hideEntry = entry;
                test("independent switches fab=" + fab + " entry=" + entry, () -> {
                    Fixture f = new Fixture(true, hideFab, hideEntry);
                    View first = named(new View(), "fab_second");
                    View second = named(new View(), "meta_ai_entry_point");
                    View ordinary = named(new View(), "menuitem_search");
                    f.root.addView(first); f.root.addView(second); f.root.addView(ordinary); f.scan();
                    check(first.getVisibility() == (hideFab ? View.GONE : View.VISIBLE), "FAB preference");
                    check(second.getVisibility() == (hideEntry ? View.GONE : View.VISIBLE), "entry-point preference");
                    check(ordinary.getVisibility() == View.VISIBLE, "ordinary search visibility");
                });
            }
        }
        for (String label : new String[] {"Ask Meta AI or Search", "Search or ask Meta AI", "Message your assistant or search chats"}) {
            test("shared search accessibility label survives: " + label, () -> {
                Fixture f = new Fixture(true, true, true);
                View search = new View(); search.setContentDescription(label); f.root.addView(search); f.scan();
                check(search.getVisibility() == View.VISIBLE && search.getParent() == f.root, "shared control remains available");
                check(label.contentEquals(search.getContentDescription()), "accessibility label unchanged");
            });
        }
        for (String label : new String[] {"Meta AI", " Ask Meta AI ", "MESSAGE YOUR ASSISTANT"}) {
            test("dedicated assistant label remains hidden: " + label, () -> {
                Fixture f = new Fixture(true, true, true);
                View assistant = new View(); assistant.setContentDescription(label); f.root.addView(assistant); f.scan();
                check(assistant.getVisibility() == View.GONE, "dedicated control suppressed");
            });
        }
        test("disabled master switch preserves host controls", () -> {
            Fixture f = new Fixture(false, true, true);
            View view = named(new View(), "meta_ai_fab"); f.root.addView(view); f.attach(view); f.scan();
            check(view.getVisibility() == View.VISIBLE && view.getParent() == f.root, "disabled feature makes no change");
        });
        System.out.println("Meta AI hierarchy contract: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Meta AI hierarchy regression");
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=PRODUCTION)
    args = parser.parse_args()
    compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
    with tempfile.TemporaryDirectory(prefix="waex_meta_ai_hierarchy_") as directory:
        root = Path(directory)
        sources = []
        for relative, text in STUBS.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            sources.append(path)
        production = root / "com/waenhancer/xposed/features/homescreen/ChatListCustomizationsHook.java"
        production.parent.mkdir(parents=True, exist_ok=True)
        production.write_text(args.source.read_text(encoding="utf-8"), encoding="utf-8")
        harness = root / "TestMetaAiHierarchy.java"
        harness.write_text(HARNESS, encoding="utf-8")
        classes = root / "classes"
        classes.mkdir()
        subprocess.run([*compiler, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                        str(production), str(harness), *map(str, sources)], check=True, timeout=90)
        subprocess.run(["java", "-cp", str(classes), "TestMetaAiHierarchy"], check=True, timeout=30)


if __name__ == "__main__":
    main()
