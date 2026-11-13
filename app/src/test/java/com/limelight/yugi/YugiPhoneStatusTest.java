package com.limelight.yugi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YugiPhoneStatusTest {
    @Test public void wifiBarsFollowRssi() {
        assertEquals(4, YugiPhoneStatus.wifiBars(-40));
        assertEquals(4, YugiPhoneStatus.wifiBars(-55));
        assertEquals(3, YugiPhoneStatus.wifiBars(-60));
        assertEquals(2, YugiPhoneStatus.wifiBars(-70));
        assertEquals(1, YugiPhoneStatus.wifiBars(-85));
        assertEquals(0, YugiPhoneStatus.wifiBars(-95));
        assertEquals(0, YugiPhoneStatus.wifiBars(-127));
        assertEquals(0, YugiPhoneStatus.wifiBars(Integer.MIN_VALUE));
    }
}
