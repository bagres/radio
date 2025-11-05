package com.example.demo.controller;

import com.example.demo.service.RadioStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class RadioStreamController {

    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Future<?> playlistFuture;
    private final Queue<String> youtubePlaylist = new ConcurrentLinkedQueue<>();

    private final RadioStreamService service;

    public RadioStreamController(RadioStreamService service) {
        this.service = service;
    }
    private volatile long currentVideoStartTimeMs = 0;
    @PostConstruct
    public void init() {
        playlistFuture = executor.submit(this::playlistManagerLoop);
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

        youtubePlaylist.offer(videoId);
        return "ID do YouTube adicionada à playlist: " + videoId;
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

    // ATUALIZADO: Envia JSON com ID e Tempo de Início (startTime)
    @GetMapping(value = "/radio/metadata", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetadata() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        emitter.onCompletion(() -> this.metadataListeners.remove(emitter));
        emitter.onTimeout(() -> {
            this.metadataListeners.remove(emitter);
            emitter.complete();
        });

        this.metadataListeners.add(emitter);

        if (service.getCurrentVideoId() != null) {
            try {
                String jsonPayload = String.format("{\"videoId\":\"%s\", \"startTime\":%d}",
                        service,
                        currentVideoStartTimeMs);

                emitter.send(SseEmitter.event().name("sync").data(jsonPayload, MediaType.APPLICATION_JSON));

            } catch (IOException e) {
                System.err.println("Falha ao enviar evento inicial. Cliente desconectou.");
                this.metadataListeners.remove(emitter);
                emitter.completeWithError(e);
                return emitter;
            }
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
                String nextVideoId = youtubePlaylist.poll();

                if (nextVideoId == null) {
                    System.out.println("Playlist vazia. Aguardando novos vídeos...");
                    Thread.sleep(5000);
                    continue;
                }

                service.setCurrentVideoId(nextVideoId);

                currentVideoStartTimeMs = System.currentTimeMillis();


                System.out.println("Trocando para novo vídeo: " + service.getCurrentVideoId() + " | Tempo de início: " + currentVideoStartTimeMs);

                String jsonPayload = String.format("{\"videoId\":\"%s\", \"startTime\":%d}",
                        service.getCurrentVideoId(),
                        currentVideoStartTimeMs);

                metadataListeners.removeIf(emitter -> {
                    try {
                        emitter.send(SseEmitter.event().name("sync").data(jsonPayload, MediaType.APPLICATION_JSON));
                        return false;
                    } catch (IOException e) {
                        System.out.println("Cliente SSE desconectado (ID: " + service.getCurrentVideoId() + "). Removendo emitter.");
                        emitter.completeWithError(e);
                        return true;
                    }
                });

                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException e) {
            System.out.println("O loop da playlist foi interrompido (Skip acionado). Indo para o próximo vídeo.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

// ENDPOINT 4: HEALTH CHECK / KEEP-ALIVE
    @GetMapping("/radio/status")
    public String statusCheck() {
        return "OK";
    }

    @GetMapping("/radio-lista")
    public Queue<String> listaMusicas(){
        return youtubePlaylist;
    }
}