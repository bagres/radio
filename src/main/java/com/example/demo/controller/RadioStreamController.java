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
    private final ExecutorService executor = Executors.newCachedThreadPool();

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

        youtubePlaylist.offer(videoId);

        metadataListeners.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("playlist_update").data("true"));
                return false;
            } catch (IOException e) {
                emitter.completeWithError(e);
                return true;
            }
        });

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

        if (service.getCurrentVideoId() != null) {
            try {
                String jsonPayload = String.format("{\"videoId\":\"%s\", \"startTime\":%d}",
                        service.getCurrentVideoId(),
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
                        emitter.completeWithError(e);
                        return true;
                    }
                });

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

// ENDPOINT 4: HEALTH CHECK / KEEP-ALIVE
    @GetMapping("/radio/status")
    public String statusCheck() {
        return "OK";
    }

    @GetMapping("/radio-lista")
    public Queue<String> listaMusicas(){
        return youtubePlaylist;
    }

    @GetMapping("/radio/listeners")
    public int getListenerCount() {
        return metadataListeners.size();
    }
}