package com.example.demo.info;

import com.example.demo.service.RadioStreamService;
import com.example.demo.service.RadioStreamServiceHolder;
import lombok.Data;
import lombok.extern.java.Log;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Data
@Log
public class RadioRoom {
    public RadioRoom(RadioStreamService radioService, String roomId) {
        this.radioService = radioService;
        this.roomId = roomId;
        startKeepAliveLoop();
    }




    private static final String[] RAW_ALLOWED_AUTHORS = {
            "Ícaro e Gilmar",
            "Henrique e Juliano",
            "Zé Neto e Cristiano",
            "Bruno & Marrone",
            "EduardoCostaVEVO",
            "ZezeeLucianoVEVO",
            "Panda Cantor",
            "Humberto e Ronaldo",
            "Cê tá doido Festival",
            "Zezé Di Camargo & Luciano - Topic"
    };

    private static final Set<String> ALLOWED_AUTHORS =
            Arrays.stream(RAW_ALLOWED_AUTHORS)
                    .map(RadioRoom::normalizeString)
                    .collect(Collectors.toSet());
    private final RadioStreamService radioService; // nova dependência

    private static final long ROOM_IDLE_TIMEOUT_MS = 1 * 60 * 1000;
    private long noActivitySince = System.currentTimeMillis();
    private String roomId;
    private volatile String currentVideoId = null;
    private volatile long currentVideoStartTimeMs = 0;
    private Future<?> playlistFuture;
    private final Queue<VideoInfo> youtubePlaylist = new ArrayBlockingQueue<>(200);
    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    //o set foi adicionado a fim de verificar mais rapidamente se a música à ser inserida já está presente
    private final Set<String> musicasPresentes = ConcurrentHashMap.newKeySet();

    private final RestTemplate restTemplate = new RestTemplate();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("radio-room-" + roomId + "-thread");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("radio-room-" + roomId + "-keepalive");
        t.setDaemon(true);
        return t;
    });
    public int getListenerCount() {
        return metadataListeners.size();
    }
    public synchronized String addMusic(String videoId) {
        if (videoId.trim().isEmpty()) return "Erro: ID/URL inválido.";

        if (!musicasPresentes.add(videoId)) return "Erro: Esta música já está na fila.";


        VideoDetails details = getDetailsFromYoutube(videoId);
        VideoInfo newVideo = new VideoInfo(videoId, details.title(), details.statusMessage());

        boolean offered = youtubePlaylist.offer(newVideo);
        if (!offered) {
            musicasPresentes.remove(videoId);
            return "Erro: Playlist cheia.";
        }

        if (playlistFuture == null || playlistFuture.isDone()) {
            playlistFuture = executor.submit(this::playlistManagerLoop);
        }

        metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("playlist_update").data("true"));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });

        return "Música adicionada (" + details.statusMessage() + "): " + details.title();
    }

    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> {
            metadataListeners.remove(emitter);
        });
        emitter.onTimeout(() -> {
            metadataListeners.remove(emitter);
            emitter.complete();
        });
        emitter.onError((e) -> {
            metadataListeners.remove(emitter);
            emitter.completeWithError(e);
        });

        metadataListeners.add(emitter);

        try {
            emitter.send(SseEmitter.event().comment("Conexão estabelecida com sala: " + roomId));
        } catch (IOException e) {
            metadataListeners.remove(emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }


    public void sendInitialSync(SseEmitter emitter) throws IOException {
        String current = currentVideoId;
        if (current != null) {
            long start = currentVideoStartTimeMs;
            VideoDetails details = getDetailsFromYoutube(current);
            notifySingleEmitter(emitter, current, details.title(), details.statusMessage(), start);
        } else {
            notifySingleEmitter(emitter, "RADIO_STOPPED_ID", "Rádio pausada. Fila vazia.", "Aguardando músicas.", currentVideoStartTimeMs);
        }
    }

    public synchronized String skipCurrentSong(String videoId) {
        if (playlistFuture != null && currentVideoId != null && currentVideoId.equals(videoId)) {
            boolean cancelled = playlistFuture.cancel(true);
            if (cancelled) {
                playlistFuture = executor.submit(this::playlistManagerLoop);
                return "Música pulada. Próxima será carregada.";
            } else {
                return "Falha ao pular: já em transição ou cancelamento não sucedeu.";
            }
        }
        return "Erro: música informada não é a atual ou rádio não está executando.";
    }

    private void playlistManagerLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {

                VideoInfo next = youtubePlaylist.poll();
                String currentTitle = null;
                String currentStatus = null;

                if (next == null) {
                    notifyMetadataUpdate("RADIO_STOPPED_ID", null, null, 0);
                    Thread.sleep(5000);
                    continue;
                } else {
                    setCurrentVideoId(next.videoId());
                    currentTitle = next.title();
                    currentStatus = next.status();
                    currentVideoStartTimeMs = System.currentTimeMillis();
                    musicasPresentes.remove(next.videoId()); // remove ao iniciar
                }

                notifyMetadataUpdate(getCurrentVideoId(), currentTitle, currentStatus, currentVideoStartTimeMs);

                try {

                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(500);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            playlistFuture = null;
        }
    }



    private void startKeepAliveLoop() {
        keepAliveScheduler.scheduleAtFixedRate(() -> {

            metadataListeners.removeIf(emitter -> {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    return false;
                } catch (Exception e) {
                    emitter.complete();
                    return true;
                }
            });

            String countPayload = String.valueOf(metadataListeners.size());
            metadataListeners.removeIf(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("listener_count").data(countPayload));
                    return false;
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    return true;
                }
            });

            boolean noListeners   = metadataListeners.isEmpty();
            boolean playlistVazia = youtubePlaylist.isEmpty();

            if (noListeners && playlistVazia) {

                long agora = System.currentTimeMillis();

                if (agora - noActivitySince >= ROOM_IDLE_TIMEOUT_MS) {
                    log.info("fechando sala "+roomId);
                    radioService.closeRoom(roomId);
                }

            } else {
                noActivitySince = System.currentTimeMillis();
            }

        }, 3, 3, TimeUnit.SECONDS);
    }



    private void notifyMetadataUpdate(String videoId, String title, String status, long startTimeMs) {
        String finalTitle;
        String finalVideoId = videoId;
        String finalStatus = status;

        if (finalVideoId == null || finalVideoId.equals("RADIO_STOPPED_ID")) {
            finalVideoId = "RADIO_STOPPED_ID";
            finalTitle = "Rádio pausada. Fila vazia.";
            finalStatus = "Aguardando músicas.";
        } else if (title == null || title.isEmpty()) {
            finalTitle = "Título desconhecido (ID: " + finalVideoId + ")";
            if (finalStatus == null || finalStatus.isEmpty()) {
                finalStatus = "Verificação pendente ou falhou.";
            }
        } else {
            finalTitle = title;
            if (finalStatus == null || finalStatus.isEmpty()) {
                finalStatus = "Status Indefinido";
            }
        }

        String escapedTitle = finalTitle.replace("\"", "\\\"");
        String escapedStatus = finalStatus.replace("\"", "\\\"");
        String payload = String.format("{\"videoId\":\"%s\", \"title\":\"%s\", \"statusMessage\":\"%s\", \"startTime\":%d}",
                finalVideoId, escapedTitle, escapedStatus, startTimeMs);

        this.metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("sync").data(payload, MediaType.APPLICATION_JSON));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });
    }

    private void notifySingleEmitter(SseEmitter emitter, String videoId, String title, String status, long startTimeMs) {
        String escapedTitle = (title == null ? "" : title.replace("\"", "\\\""));
        String escapedStatus = (status == null ? "" : status.replace("\"", "\\\""));
        String payload = String.format("{\"videoId\":\"%s\", \"title\":\"%s\", \"statusMessage\":\"%s\", \"startTime\":%d}",
                videoId, escapedTitle, escapedStatus, startTimeMs);
        try {
            emitter.send(SseEmitter.event().name("sync").data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            metadataListeners.remove(emitter);
            emitter.completeWithError(e);
        }
    }

    private static String normalizeString(String input) {
        if (input == null) return "";
        String normalized = input.toLowerCase();
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("[^\\p{ASCII}]", "");
        normalized = normalized.replaceAll("[^a-z0-9\\s]", "");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private boolean checkTitleForAuthorMatch(String title) {
        if (title == null) return false;
        String normalizedTitle = normalizeString(title);
        for (String rawAuthor : RAW_ALLOWED_AUTHORS) {
            String normalizedAuthor = normalizeString(rawAuthor);
            if (normalizedTitle.contains(normalizedAuthor)) {
                return true;
            }
        }
        return false;
    }

    private VideoDetails getDetailsFromYoutube(String videoId) {
        String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";
        String title = "Título não encontrado";
        String authorName = null;
        String statusMessage = "Erro ao buscar detalhes.";
        boolean isPure = false;

        try {
            Map result = restTemplate.getForObject(oembedUrl, Map.class);
            if (result != null) {
                title = (String) result.getOrDefault("title", title);
                authorName = (String) result.get("author_name");
            }

            if (authorName != null && !authorName.isEmpty()) {
                String normalizedAuthor = normalizeString(authorName);
                if (ALLOWED_AUTHORS.contains(normalizedAuthor)) {
                    isPure = true;
                }
            }

            if (!isPure && title != null && !title.isEmpty()) {
                if (checkTitleForAuthorMatch(title)) {
                    isPure = true;
                }
            }

            statusMessage = isPure ? "Aí sim, essa é a pura" : "Essa música é de bagre.";

        } catch (Exception e) {
            title = "Falha ao carregar título";
            statusMessage = "Autor não encontrado para verificação.";
        }

        return new VideoDetails(title, authorName, statusMessage);
    }

    public void shutdown() {
        try {
            try {
                if (playlistFuture != null) {
                    playlistFuture.cancel(true);
                }
            } catch (Exception ignored) {}

            playlistFuture = null;

            try {
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdownNow();
                    executor.awaitTermination(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {}

            try {
                if (keepAliveScheduler != null && !keepAliveScheduler.isShutdown()) {
                    keepAliveScheduler.shutdownNow();
                    keepAliveScheduler.awaitTermination(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {}

            try {
                for (SseEmitter e : metadataListeners) {
                    try { e.complete(); } catch (Exception ex) {  }
                }
            } catch (Exception ignored) {}
            metadataListeners.clear();

            try {
                youtubePlaylist.clear();
                musicasPresentes.clear();
            } catch (Exception ignored) {}

            currentVideoId = null;
            currentVideoStartTimeMs = 0L;
            noActivitySince = 0L;




        } catch (Throwable t) {
            log.severe("Erro no shutdown da room " + roomId + ": " + t.getMessage());
        }
    }

}
