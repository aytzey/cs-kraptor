package com.kraptor;

import java.lang.reflect.Method;

public final class InatDirectChecks {
    public static void main(String[] args) {
        try {
            Class<?> type = Class.forName("com.keyiflerolsun.InatBoxBC");
            Object provider = type.getConstructor().newInstance();
            Method direct = type.getDeclaredMethod("isDirectStream", String.class);
            direct.setAccessible(true);
            boolean wrapper = (Boolean) direct.invoke(provider,
                "https://token.example.test/webtv/secure?url=https://media.example.test/live.m3u8");
            if (wrapper) throw new AssertionError("a video URL inside a query must not skip stream resolution");
            boolean playlist = (Boolean) direct.invoke(provider,
                "https://media.example.test/live.m3u8?signature=fixture");
            if (!playlist) throw new AssertionError("signed playlists must remain direct streams");
            System.out.println("InatDirectChecks: passed");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("InatDirectChecks: failed " + failure.getClass().getSimpleName());
            if (failure instanceof AssertionError) System.out.println(failure.getMessage());
            System.exit(1);
        }
    }
}
