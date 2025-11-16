package com.example.demo.service;

import com.example.demo.info.RadioRoom;
import com.example.demo.info.VideoInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.naming.directory.SearchResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RadioStreamService {
    public record SearchResult(String id, String title) {}
    // Mapa de salas (por nome, id, etc)
    private final Map<String, RadioRoom> rooms = new ConcurrentHashMap<>();

    // Obtém ou cria uma sala
    private RadioRoom getRoom(String roomName) {
        return rooms.computeIfAbsent(roomName, k -> new RadioRoom(roomName));
    }

    // Métodos delegando para a sala específica
    public void addMusicToPlaylist(String room, String videoId) {

        getRoom(room).addMusic(videoId);
    }
    public String resolveQueryToVideoId(String urlOrQuery) {
        if (urlOrQuery == null || urlOrQuery.trim().isEmpty()) {
            return null;
        }

        String trimmed = urlOrQuery.trim();

        String videoId = extractVideoId(trimmed);
        if (videoId != null && !videoId.isEmpty()) {
            return videoId;
        }

        SearchResult result = searchYoutubeVideo(trimmed);
        if (result != null) {
            return result.id();
        }

        return null;
    }

    public SseEmitter createSseEmitter(String room) {
        return getRoom(room).createSseEmitter();
    }

    public void sendInitialSync(String room, SseEmitter emitter) throws IOException {
        getRoom(room).sendInitialSync(emitter);
    }

    public String skipCurrentSong(String room, String videoId) {
        return getRoom(room).skipCurrentSong(videoId);
    }

    public Queue<VideoInfo> getPlaylist(String room) {
        return getRoom(room).getYoutubePlaylist();
    }

    public int getListenerCount(String room) {
        return getRoom(room).getListenerCount();
    }


    public SearchResult searchYoutubeVideo(String query) {
        ObjectMapper mapper = new ObjectMapper();

        String fullSearchArgument = "ytsearch1:" + query;

        String[] command = {
                "python3",
                "-m",
                "yt_dlp",
                "--dump-json",
                "--flat-playlist",
                fullSearchArgument
        };

        System.out.println("Executando busca: " + command);

        try {
            Process process = Runtime.getRuntime().exec(command);

            String jsonOutput = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));

            new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))
                    .lines().forEach(System.err::println);

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0) {
                System.err.println("Erro ou Timeout ao executar yt-dlp.");
                return null;
            }

            if (jsonOutput.trim().isEmpty()) return null;


            String firstLine = jsonOutput.split("\n")[0];

            Map<String, Object> result = mapper.readValue(firstLine, new TypeReference<Map<String, Object>>() {});

            String videoId = (String) result.get("id");
            String title = (String) result.get("title");

            if (videoId != null && title != null) {
                return new SearchResult(videoId, title);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Exceção ao executar yt-dlp: " + e.getMessage());
        }
        return null;
    }
    private String extractVideoId(String urlOrVideoId) {
        if (urlOrVideoId.length() <= 11 && !urlOrVideoId.contains("/")) {
            return urlOrVideoId;
        }
        String regexShort = "(?:youtu\\.be\\/|\\/embed\\/|\\/v\\/|watch\\?v=|v%3D|v\\=|youtu\\.be\\/)([^#\\&\\?]{11})";
        Pattern pattern = Pattern.compile(regexShort, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(urlOrVideoId);
        if (matcher.find()) {
            return matcher.group(1);
        }
        try {
            URL url = new URL(urlOrVideoId);
            String query = url.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("v=")) {
                        return param.substring(2);
                    }
                }
            }
        } catch (MalformedURLException ignored) {
        }
        return null;
    }
}
