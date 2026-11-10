package com.waenhancer.xposed.features.conversation;

import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

public class ForwardLimitPolicyTest {
    @Test public void changesSmallLimitsAndFlagsButPreservesResourceIds() {
        Class<?>[] types = {Integer.class, int.class, boolean.class, int.class};
        Object[] args = {5, 0x7f120123, true, 1};
        ForwardLimitPolicy.removeLimits(types, args);
        assertArrayEquals(new Object[]{Integer.MAX_VALUE, 0x7f120123, false, Integer.MAX_VALUE}, args);
    }
}
