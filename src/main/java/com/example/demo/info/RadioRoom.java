package com.example.demo.info;

import com.example.demo.info.VideoInfo;
import com.example.demo.service.RadioStreamService;
import com.example.demo.service.RadioStreamServiceHolder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
public class RadioRoom {
    public RadioRoom( String roomId) {
        this.roomId = roomId;
        startKeepAliveLoop();
    }



    public record VideoDetails(String title, String authorName, String statusMessage) {}

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

    private static final long ROOM_IDLE_TIMEOUT_MS = 10 * 60 * 1000;
    private long noActivitySince = System.currentTimeMillis();
    private String roomId;
    private volatile String currentVideoId = null;
    private volatile long currentVideoStartTimeMs = 0;
    private Future<?> playlistFuture;
    private final Queue<VideoInfo> youtubePlaylist = new ArrayBlockingQueue<>(200);
    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    // Set concorrente para checagem rápida de duplicados
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


//        String videoId = extractVideoId(urlOrVideoId);
//        if (videoId.trim().length() != 11) {
//            return "Erro: Não foi possível extrair ID do vídeo.";
//        }

        if (!musicasPresentes.add(videoId)) return "Erro: Esta música já está na fila.";


        VideoDetails details = getDetailsFromYoutube(videoId);
        VideoInfo newVideo = new VideoInfo(videoId, details.title, details.statusMessage);

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

        return "Música adicionada (" + details.statusMessage + "): " + details.title;
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
            notifySingleEmitter(emitter, current, details.title, details.statusMessage, start);
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
                    // fila vazia: notifica e espera
                    notifyMetadataUpdate("RADIO_STOPPED_ID", null, null, 0);
                    Thread.sleep(5000);
                    continue;
                } else {
                    setCurrentVideoId(next.getVideoId());
                    currentTitle = next.getTitle();
                    currentStatus = next.getStatus();
                    currentVideoStartTimeMs = System.currentTimeMillis();
                    // Remover do set de presentes pois agora está sendo reproduzida (ou já está)
                    musicasPresentes.remove(next.getVideoId());
                }

                notifyMetadataUpdate(getCurrentVideoId(), currentTitle, currentStatus, currentVideoStartTimeMs);

                try {
                    // aguarda tempo máximo (20 minutos) simulando reprodução
                    long maxWaitTime = 20L * 60L * 1000L;
                    long interval = 500L;
                    long waited = 0L;
                    while (waited < maxWaitTime) {
                        Thread.sleep(interval);
                        waited += interval;
                    }
                    // quando sair do while, pode ir para próxima
                } catch (InterruptedException e) {
                    // skip foi acionado: interrompe e segue para a próxima iteração
                    Thread.currentThread().interrupt();
                    // continuar para próxima música (o loop externo tratará)
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // finaliza estado da playlsitFuture para permitir re-submissão futura
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
                    RadioStreamServiceHolder.closeRoomExternally(roomId);
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
            // se falhar, remover emitter (ou apenas ignorar)
            metadataListeners.remove(emitter);
            emitter.completeWithError(e);
        }
    }

    // ---------- utilitários (extraídos do seu código original) ----------

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

    private String extractVideoId(String urlOrVideoId) {
        if (urlOrVideoId == null) return null;
        String candidate = urlOrVideoId.trim();
        if (candidate.length() == 11 && !candidate.contains("/")) {
            return candidate;
        }
        // regex para capturar ID em urls youtube/shorts/embed etc
        String regexShort = "(?:youtu\\.be\\/|\\/embed\\/|\\/v\\/|watch\\?v=|v%3D|v\\=|youtu\\.be\\/)([^#\\&\\?]{11})";
        Pattern pattern = Pattern.compile(regexShort, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(candidate);
        if (matcher.find()) {
            return matcher.group(1);
        }
        try {
            URL url = new URL(candidate);
            String query = url.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("v=")) {
                        return param.substring(2);
                    }
                }
            }
        } catch (MalformedURLException ignored) {}
        return null;
    }

    public void shutdown() {
        try {
            if (playlistFuture != null) playlistFuture.cancel(true);
        } catch (Exception ignored) {}
        executor.shutdownNow();
        keepAliveScheduler.shutdownNow();
        metadataListeners.forEach(SseEmitter::complete);
        metadataListeners.clear();
    }
}
