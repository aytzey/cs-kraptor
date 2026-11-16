package com.kraptor;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.jvm.functions.Function1;
import com.lagradost.cloudstream3.utils.ExtractorLink;
import com.lagradost.cloudstream3.utils.ExtractorLinkType;
import org.json.JSONObject;

public final class BackupChecks {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String tagged = MovieBackup.tag("https://www.filmmodu.one/movie", "The Matrix - Matrix", 1999, "");
        JSONObject metadata = MovieBackup.metadata(tagged);
        check(metadata != null, "movie metadata must survive the saved playback URL");
        check(MovieBackup.matches(metadata, "The Matrix", 1999, "tt0133093"), "original title and year must match");
        check(MovieBackup.matches(metadata, "Matrix - The Matrix", 1999, "tt0133093"),
            "translated and original title order must not change the movie match");
        check(!MovieBackup.matches(metadata, "The Matrix", 2003, "tt0234215"), "a different year must not match");
        check(!MovieBackup.matches(metadata, "The Matrix Reloaded", 1999, "tt0234215"), "a partial title must not match");
        metadata.put("imdb", "tt0133093");
        check(!MovieBackup.matches(metadata, "The Matrix", 1999, "tt0234215"), "IMDb mismatch must reject a result");
        check(MovieBackup.matches(metadata, "Matrix 1", 1999, "tt0133093"), "IMDb must allow a translated title");
        check(MovieBackup.metadata("https://example.test/#unrelated") == null, "unrelated fragments must be preserved");
        check(!MovieBackup.sameOrigin("https://www.hdfilmcehennemi.nl:8443/movie"),"a different port must not be treated as the backup origin");
        check(!MovieBackup.sameOrigin("https://user@www.hdfilmcehennemi.nl/movie"),"credentials must not be accepted in backup links");

        AtomicInteger emitted = new AtomicInteger();
        Function1 callback = value -> { emitted.incrementAndGet(); return null; };
        Function1 video=MovieBackup.wrap(tagged,callback,callback);
        video.invoke(new ExtractorLink("Original","Direct video","https://example.test/untouched.mp4","",720,
            ExtractorLinkType.VIDEO,Collections.emptyMap(),null));
        check(MovieBackup.finish(video) && emitted.get()==1,"non-HLS source formats must remain untouched");
        emitted.set(0);
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            Thread responder = new Thread(() -> {
                try {
                    for (int i=0; i<2; i++) {
                        try (Socket socket = server.accept()) {
                            java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),"UTF-8"));
                            for (String line;(line=input.readLine())!=null && !line.isEmpty();)
                                check(!line.toLowerCase(java.util.Locale.ROOT).startsWith("x-requested-with:"),
                                    "page and media fetches must not request AJAX JSON");
                            byte[] body = i==0 ? "#EXTM3U\n#EXTINF:1,\n/part.ts\n".getBytes("UTF-8") : new byte[376];
                            if (i==1) body[0]=0x47;
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                            out.write(body); out.flush();
                        }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            responder.start();
            Function1 wrapped = MovieBackup.wrap(tagged, callback, callback);
            wrapped.invoke(new ExtractorLink("Original", "Original", "http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8",
                "", 720, ExtractorLinkType.M3U8, Collections.emptyMap(), null));
            check(MovieBackup.finish(wrapped), "a working original source must skip backup lookup");
            check(emitted.get()==1, "the original source must be emitted once");
            responder.join(3000);
            check(!responder.isAlive(), "media checks must close their connections");
        }
        System.out.println("BackupChecks: passed");
        System.exit(0);
    }
}
