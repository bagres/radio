package com.example.demo.service;

import com.example.demo.info.VideoInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
@Data
public class RadioStreamService {

    public record VideoDetails(String title, String authorName, String statusMessage) {
    }

    public record SearchResult(String id, String title) {}

    private volatile String currentVideoId = null;
    private volatile long currentVideoStartTimeMs = 0;
    private Future<?> playlistFuture;
    private final Queue<VideoInfo> youtubePlaylist = new ArrayBlockingQueue<>(200);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    private static final Set<String> ALLOWED_AUTHORS;
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

    static {
        ALLOWED_AUTHORS = Arrays.stream(RAW_ALLOWED_AUTHORS)
                .map(RadioStreamService::normalizeString)
                .collect(Collectors.toSet());
    }

    @PostConstruct
    public void init() {
        playlistFuture = executor.submit(this::playlistManagerLoop);
        startKeepAliveLoop();
    }

    private void playlistManagerLoop() {
        try {
            while (true) {
                VideoInfo nextVideoInfo = youtubePlaylist.poll();
                String currentTitle;
                String currentStatus = null;

                if (nextVideoInfo == null) {
                    System.out.println("Playlist vazia. Aguardando novos vídeos...");
                    notifyMetadataUpdate("RADIO_STOPPED_ID", null, null, 0);
                    Thread.sleep(5000);
                    continue;
                } else {
                    setCurrentVideoId(nextVideoInfo.getVideoId());
                    currentTitle = nextVideoInfo.getTitle();
                    currentStatus = nextVideoInfo.getStatus();
                    currentVideoStartTimeMs = System.currentTimeMillis();
                }

                System.out.println("Trocando para novo vídeo: " + getCurrentVideoId() + " | Tempo de início: " + currentVideoStartTimeMs);

                notifyMetadataUpdate(getCurrentVideoId(), currentTitle, currentStatus, currentVideoStartTimeMs);

                try {
                    long maxWaitTime = 20 * 60 * 1000;
                    long interval = 500;
                    long waitedTime = 0;

                    while (waitedTime < maxWaitTime) {
                        Thread.sleep(interval);
                        waitedTime += interval;
                    }
                    System.out.println("Música completou o tempo máximo. Passando para a próxima.");

                } catch (InterruptedException e) {
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            System.out.println("O loop da playlist foi interrompido (Skip acionado). Indo para o próximo vídeo.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startKeepAliveLoop() {
        executor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(3000);

                    metadataListeners.removeIf(emitter -> {
                        try {
                            emitter.send(SseEmitter.event().comment("keep-alive"));
                            return false;
                        } catch (Exception e) {
                            System.out.println("Keep-Alive falhou. Cliente SSE desconectado. Removendo emitter.");
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
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public String skipCurrentSong(String videoId) {
        if (playlistFuture != null && videoId.equals(getCurrentVideoId())) {
            boolean cancelled = playlistFuture.cancel(true);

            if (cancelled) {
                playlistFuture = executor.submit(this::playlistManagerLoop);
                return "Música pulada. O próximo vídeo será carregado imediatamente.";
            } else {
                return "Skip falhou. O rádio está em transição ou o skip já foi processado.";
            }
        }
        return "Erro: O serviço de rádio não está ativo.";
    }

    public void sendInitialSync(SseEmitter emitter) throws IOException {
        String currentVideoId = getCurrentVideoId();

        if (currentVideoId != null) {
            long startTime = getCurrentVideoStartTimeMs();

            VideoDetails currentDetails = getDetailsFromYoutube(currentVideoId);

            String currentTitle = currentDetails.title;
            String currentStatus = currentDetails.statusMessage;

            try {
                notifyMetadataUpdate(currentVideoId, currentTitle, currentStatus, startTime);
            } catch (Exception e) {
                System.err.println("Falha ao enviar evento de sincronização inicial. Cliente desconectou.");
                emitter.completeWithError(e);
                throw new IOException("Falha no sync inicial", e);
            }
        } else {
            notifyMetadataUpdate("RADIO_STOPPED_ID", null, null, getCurrentVideoStartTimeMs());
        }
    }

    public SseEmitter createSseEmitter() {

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> {
            System.out.println("Emitter completado. Removendo.");
            this.metadataListeners.remove(emitter);
        });
        emitter.onTimeout(() -> {
            System.out.println("Emitter timeout. Removendo.");
            this.metadataListeners.remove(emitter);
            emitter.complete();
        });
        emitter.onError((e) -> {
            System.out.println("Emitter erro. Removendo.");
            this.metadataListeners.remove(emitter);
            emitter.completeWithError(e);
        });

        this.metadataListeners.add(emitter);

        try {
            emitter.send(SseEmitter.event().comment("Conexão estabelecida com sucesso."));
        } catch (IOException e) {
            System.err.println("Falha no keep-alive inicial. Cliente desconectou imediatamente.");
            this.metadataListeners.remove(emitter);
            emitter.completeWithError(e);
            return emitter;
        }

        return emitter;
    }

    public int getListenerCount() {
        return metadataListeners.size();
    }

    public void notifyMetadataUpdate(String videoId, String title, String status, long startTimeMs) {
        String finalTitle;
        String finalVideoId = videoId;
        String finalStatus = status;

        if (finalVideoId == null || finalVideoId.equals("RADIO_STOPPED_ID")) {
            finalVideoId = "RADIO_STOPPED_ID";
            finalTitle = "Rádio pausada. Fila vazia.";
            finalStatus = "Aguardando músicas.";
        }
        else if (title == null || title.isEmpty()) {
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
                System.err.println("Falha ao notificar listener. Removendo.");
                emitter.completeWithError(e);
                return true;
            }
        });
    }

    public static String normalizeString(String input) {
        if (input == null) {
            return "";
        }
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
            title = (String) result.get("title");
            authorName = (String) result.get("author_name");

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

            if (isPure) {
                statusMessage = "Aí sim, essa é a pura";
            } else {
                statusMessage = "Essa música é de bagre.";
            }

        } catch (Exception e) {
            System.err.println("Erro ao buscar detalhes do vídeo " + videoId + ": " + e.getMessage());
            title = "Falha ao carregar título";
            statusMessage = "Autor não encontrado para verificação.";
        }

        return new VideoDetails(title, authorName, statusMessage);
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

    public ResponseEntity<String> addMusicToPlaylist(String urlOrVideoId) {
        if (urlOrVideoId == null || urlOrVideoId.isEmpty()) {
            return ResponseEntity.badRequest().body("Erro: ID do vídeo inválido.");
        }

        String videoId = extractVideoId(urlOrVideoId);

        if (videoId == null) {
            return ResponseEntity.badRequest().body("Erro: Não foi possível extrair a ID válida do vídeo ou URL fornecida.");
        }

        boolean isDuplicate = youtubePlaylist.stream()
                .anyMatch(info -> videoId.equals(info.getVideoId()));

        if (isDuplicate) {
            System.out.println("Tentativa de adicionar música duplicada: " + videoId);
            return ResponseEntity.badRequest().body("Erro: Esta música já está na fila da playlist.");
        }

        VideoDetails details = getDetailsFromYoutube(videoId);
        VideoInfo newVideo = new VideoInfo(videoId, details.title, details.statusMessage);

        boolean added = youtubePlaylist.offer(newVideo);

        if (!added) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Erro: A playlist atingiu o limite máximo de 200 músicas. Tente novamente mais tarde.");
        }

        this.metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("playlist_update").data("true"));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });

        return ResponseEntity.ok("Música adicionada à playlist (" + details.statusMessage + "): " + details.title);
    }

    public String searchYoutubeVideoId(String query) {
        String fullSearchArgument = "ytsearch1:" + query;

        String[] command = {
                "python3",
                "-m",
                "yt_dlp",
                "--get-id",
                fullSearchArgument
        };

        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String videoId = reader.readLine();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished || process.exitValue() != 0 || videoId == null || videoId.trim().isEmpty()) {
                return null;
            }
            return videoId.trim();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public Queue<VideoInfo> getPlaylist() {
        return youtubePlaylist;
    }

}