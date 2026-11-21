package com.waenhancer.xposed.features.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MediaFileLocatorTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File file(String name, int size) throws IOException {
        File f = temp.newFile(name);
        Files.write(f.toPath(), new byte[size]);
        return f;
    }

    private static final class Holder {
        File media;
        Holder nested;
        Object untyped;
    }

    private static final class Box {
        final Holder holder = new Holder();
        final List<String> paths = new ArrayList<>();
        final Map<String, Object> byKey = new HashMap<>();
        String text;
        private File getter;

        @SuppressWarnings("unused")
        private File mediaFile() { return getter; }
    }

    @Test
    public void nullRootReturnsNull() {
        assertNull(MediaFileLocator.find(null, 5, MediaFileLocator.Mode.SHALLOW, null));
    }

    @Test
    public void shallowFindsFileInNestedFieldAndPrefersLargest() throws Exception {
        Box box = new Box();
        box.holder.media = file("small.bin", 10);
        Holder inner = new Holder();
        inner.media = file("large.bin", 100);
        box.holder.nested = inner;

        File found = MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW, null);
        assertEquals("large.bin", found.getName());
    }

    @Test
    public void shallowRespectsMaxDepth() throws Exception {
        Box box = new Box();
        Holder inner = new Holder();
        inner.media = file("deep.bin", 10);
        box.holder.nested = inner;

        assertNull(MediaFileLocator.find(box, 0, MediaFileLocator.Mode.SHALLOW, null));
    }

    @Test
    public void shallowDoesNotFollowFieldsDeclaredAsJavaLangTypesButDeepDoes() throws Exception {
        Box box = new Box();
        Holder inner = new Holder();
        inner.media = file("hidden.bin", 10);
        box.holder.untyped = inner;

        assertNull(MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW, null));
        assertEquals("hidden.bin",
                MediaFileLocator.find(box, 8, MediaFileLocator.Mode.DEEP, null).getName());
    }

    @Test
    public void shallowIgnoresEmptyFilesAndStringPaths() throws Exception {
        Box box = new Box();
        box.holder.media = file("empty.bin", 0);
        box.text = file("viaString.bin", 10).getAbsolutePath();

        assertNull(MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW, null));
    }

    @Test
    public void deepFollowsStringPathsCollectionsMapsAndFileGetters() throws Exception {
        Box viaString = new Box();
        viaString.text = file("a.bin", 10).getAbsolutePath();
        assertEquals("a.bin", MediaFileLocator.find(viaString, 8, MediaFileLocator.Mode.DEEP, null).getName());

        Box viaList = new Box();
        viaList.paths.add("file://" + file("b.bin", 10).getAbsolutePath());
        assertEquals("b.bin", MediaFileLocator.find(viaList, 8, MediaFileLocator.Mode.DEEP, null).getName());

        Box viaMap = new Box();
        viaMap.byKey.put("k", file("c.bin", 10));
        assertEquals("c.bin", MediaFileLocator.find(viaMap, 8, MediaFileLocator.Mode.DEEP, null).getName());

        Box viaGetter = new Box();
        viaGetter.getter = file("d.bin", 10);
        assertEquals("d.bin", MediaFileLocator.find(viaGetter, 8, MediaFileLocator.Mode.DEEP, null).getName());
    }

    @Test
    public void acceptFilterRejectsCandidatesAndThrowingFilterIsSafe() throws Exception {
        Box box = new Box();
        box.holder.media = file("big.bin", 100);
        Holder inner = new Holder();
        inner.media = file("ok.bin", 10);
        box.holder.nested = inner;

        File found = MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW,
                f -> f.getName().equals("ok.bin"));
        assertEquals("ok.bin", found.getName());

        assertNull(MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW, f -> {
            throw new IllegalStateException("invalid media");
        }));
    }

    @Test
    public void repeatedLookupsUseCachedReflectionAndStayCorrect() throws Exception {
        Box box = new Box();
        box.holder.media = file("again.bin", 10);
        for (int i = 0; i < 3; i++) {
            assertEquals("again.bin",
                    MediaFileLocator.find(box, 5, MediaFileLocator.Mode.SHALLOW, null).getName());
        }
        assertEquals(Arrays.asList("again.bin"),
                Arrays.asList(MediaFileLocator.find(box, 5, MediaFileLocator.Mode.DEEP, null).getName()));
    }
}
