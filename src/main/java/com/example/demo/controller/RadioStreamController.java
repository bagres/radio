package com.example.demo.controller;

import com.example.demo.info.VideoInfo;
import com.example.demo.service.RadioStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class RadioStreamController {

    private static final Set<String> ALLOWED_AUTHORS;

    static {
        String[] authorList = {
                "Ícaro e Gilmar",
                "Chitãozinho & Xororó",
                "Maiara & Maraisa",
                "Henrique e Juliano",
                "Zé Neto e Cristiano",
                "Bruno & Marrone"
        };

        ALLOWED_AUTHORS = Arrays.stream(authorList)
                .map(RadioStreamController::normalizeString)
                .collect(Collectors.toSet());
    }

    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Future<?> playlistFuture;
    private final Queue<VideoInfo> youtubePlaylist = new ConcurrentLinkedQueue<>();

    private final RestTemplate restTemplate = new RestTemplate();

    private final RadioStreamService service;

    public RadioStreamController(RadioStreamService service) {
        this.service = service;
    }
    private volatile long currentVideoStartTimeMs = 0;
    @PostConstruct
    public void init() {
        playlistFuture = executor.submit(this::playlistManagerLoop);
        startKeepAliveLoop();
    }

    // ENDPOINT 1: Adicionar vídeo do YouTube
    @PostMapping("/radio/add-youtube")
    public String addYoutubeAudio(@RequestParam("videoId") String urlOrVideoId) {
        if (urlOrVideoId == null || urlOrVideoId.isEmpty()) {
            return "Erro: ID do vídeo inválido.";
        }

        String videoId = extractVideoId(urlOrVideoId);

        if (videoId == null) {
            return "Erro: Não foi possível extrair a ID válida do vídeo ou URL fornecida.";
        }

        VideoDetails details = getDetailsFromYoutube(videoId);
        VideoInfo newVideo = new VideoInfo(videoId, details.title, details.statusMessage);

        youtubePlaylist.offer(newVideo);

        metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("playlist_update").data("true"));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });

        return "Música adicionada à playlist (" + details.statusMessage + "): " + details.title;
    }

    private VideoDetails getDetailsFromYoutube(String videoId) {
        String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";

        String title = "Título não encontrado";
        String authorName = null;
        String statusMessage = "Erro ao buscar detalhes.";

        try {
            Map<String, Object> result = restTemplate.getForObject(oembedUrl, Map.class);

            title = (String) result.get("title");
            authorName = (String) result.get("author_name");

            if (authorName != null && !authorName.isEmpty()) {

                String normalizedAuthor = normalizeString(authorName);

                if (ALLOWED_AUTHORS.contains(normalizedAuthor)) {
                    statusMessage = "Aí sim, essa é a pura";
                } else {
                    statusMessage = "Essa música é de bagre.";
                }
            } else {
                statusMessage = "Autor não encontrado para verificação.";
            }

        } catch (Exception e) {
            System.err.println("Erro ao buscar detalhes do vídeo " + videoId + ": " + e.getMessage());
            title = "Falha ao carregar título";
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
        } catch (MalformedURLException e) {
        }

        return null;
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

    // ATUALIZADO: Envia JSON com ID e Tempo de Início (startTime)
    @GetMapping(value = "/radio/metadata", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetadata() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> this.metadataListeners.remove(emitter));
        emitter.onTimeout(() -> {
            this.metadataListeners.remove(emitter);
            emitter.complete();
        });
        emitter.onError((e) -> {
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

        String currentVideoId = service.getCurrentVideoId();

        if (currentVideoId != null) {
            VideoDetails currentDetails = getDetailsFromYoutube(currentVideoId);
            String currentTitle = currentDetails.title;
            String currentStatus = currentDetails.statusMessage;

            try {
                notifyMetadataUpdate(currentVideoId, currentTitle, currentStatus, currentVideoStartTimeMs);

            } catch (Exception e) {
                System.err.println("Falha ao enviar evento de sincronização inicial. Cliente desconectou.");
                this.metadataListeners.remove(emitter);
                emitter.completeWithError(e);
                return emitter;
            }
        } else {
            notifyMetadataUpdate("RADIO_STOPPED_ID", null, null, 0);
        }

        return emitter;
    }

    // ENDPOINT 3: PULAR MÚSICA (Skip)
    @PostMapping("/radio/skip")
    public String skipSong(@RequestParam("videoId") String videoId) {
        if (playlistFuture != null && videoId.equals(service.getCurrentVideoId())) {
            // Cancela a thread atual. Isso lança a InterruptedException no loop e o acorda.
            boolean cancelled = playlistFuture.cancel(true);

            if (cancelled) {
                // Inicia uma nova thread para continuar imediatamente a playlist
                playlistFuture = executor.submit(this::playlistManagerLoop);
                return "Música pulada. O próximo vídeo será carregado imediatamente.";
            } else {
                return "Skip falhou. O rádio está em transição ou o skip já foi processado.";
            }
        }
        return "Erro: O serviço de rádio não está ativo.";
    }

    // ATUALIZADO: Toca a música até o skip.
    private void playlistManagerLoop() {
        try {
            while (true) {
                VideoInfo nextVideoInfo = youtubePlaylist.poll();
                String currentTitle;
                String currentStatus = null;

                if (nextVideoInfo == null) {
                    System.out.println("Playlist vazia. Aguardando novos vídeos...");
                    notifyMetadataUpdate("RADIO_STOPPED_ID", null, null,0);
                    Thread.sleep(5000);
                    continue;
                } else {
                    service.setCurrentVideoId(nextVideoInfo.getVideoId());
                    currentTitle = nextVideoInfo.getTitle();
                    currentStatus = nextVideoInfo.getStatus();
                    currentVideoStartTimeMs = System.currentTimeMillis();
                }


                System.out.println("Trocando para novo vídeo: " + service.getCurrentVideoId() + " | Tempo de início: " + currentVideoStartTimeMs);

                notifyMetadataUpdate(service.getCurrentVideoId(), currentTitle, currentStatus, currentVideoStartTimeMs);

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
                    // Interrompido pelo método skipSong(). Saímos do loop interno e do while(true).
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

    private void notifyMetadataUpdate(String videoId, String title, String status, long startTimeMs) {
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
                finalVideoId, escapedTitle, escapedStatus, startTimeMs); // <-- Adicionado escapedStatus

        metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("sync").data(payload, MediaType.APPLICATION_JSON));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });
    }

// ENDPOINT 4: HEALTH CHECK / KEEP-ALIVE
    @GetMapping("/radio/status")
    public String statusCheck() {
        return "OK";
    }

    @GetMapping("/radio-lista")
    public Queue<VideoInfo> listaMusicas(){
        return youtubePlaylist;
    }

    @GetMapping("/radio/listeners")
    public int getListenerCount() {
        return metadataListeners.size();
    }

    public class VideoDetails {
        public final String title;
        public final String authorName;
        public final String statusMessage;

        public VideoDetails(String title, String authorName, String statusMessage) {
            this.title = title;
            this.authorName = authorName;
            this.statusMessage = statusMessage;
        }
    }
}