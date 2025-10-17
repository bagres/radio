package com.example.demo.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RestController
public class RadioStreamController {

    private final CopyOnWriteArrayList<SseEmitter> metadataListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Future<?> playlistFuture;
    private final Queue<String> youtubePlaylist = new ConcurrentLinkedQueue<>();

    private volatile String currentVideoId = null;

    // NOVO: Timestamp Unix em ms de quando o vídeo atual começou a tocar
    private volatile long currentVideoStartTimeMs = 0;

    @PostConstruct
    public void init() {
        // Inicia o loop de gerenciamento da playlist e ARMAZENA O FUTURE
        playlistFuture = executor.submit(this::playlistManagerLoop);
    }

    // ===============================================
    // ENDPOINT 1: Adicionar vídeo do YouTube
    // ===============================================
    @PostMapping("/radio/add-youtube")
    public String addYoutubeAudio(@RequestParam("videoId") String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return "Erro: ID do vídeo inválido.";
        }

        // Adiciona o ID à playlist
        youtubePlaylist.offer(videoId);
        return "ID do YouTube adicionado à playlist: " + videoId;
    }

    // ===============================================
    // ENDPOINT 2: Conexão para RECEBER o ID do vídeo (Metadados SSE)
    // ATUALIZADO: Envia JSON com ID e Tempo de Início (startTime)
    // ===============================================
    @GetMapping(value = "/radio/metadata", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetadata() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        // Lógica de remoção em caso de término ou timeout
        emitter.onCompletion(() -> this.metadataListeners.remove(emitter));
        emitter.onTimeout(() -> {
            this.metadataListeners.remove(emitter);
            emitter.complete();
        });

        this.metadataListeners.add(emitter);

        // Envia o estado atual da rádio (ID e Tempo de Início)
        if (currentVideoId != null) {
            try {
                // Cria o payload JSON para sincronização
                String jsonPayload = String.format("{\"videoId\":\"%s\", \"startTime\":%d}",
                        currentVideoId,
                        currentVideoStartTimeMs);

                // Envia o evento "sync"
                emitter.send(SseEmitter.event().name("sync").data(jsonPayload, MediaType.APPLICATION_JSON));

            } catch (IOException e) {
                // Falha ao enviar o primeiro evento (cliente desconectou rapidamente)
                System.err.println("Falha ao enviar evento inicial. Cliente desconectou.");
                this.metadataListeners.remove(emitter);
                emitter.completeWithError(e);
                return emitter;
            }
        }

        return emitter;
    }

    // ===============================================
    // ENDPOINT 3: PULAR MÚSICA (Skip)
    // ===============================================
    @PostMapping("/radio/skip")
    public String skipSong() {
        if (playlistFuture != null) {
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

    // ===============================================
    // THREAD PRINCIPAL: Gerenciamento da Playlist
    // ATUALIZADO: Toca a música até o skip.
    // ===============================================
    private void playlistManagerLoop() {
        try {
            while (true) {
                String nextVideoId = youtubePlaylist.poll();

                if (nextVideoId == null) {
                    // Fila vazia, espera por adições de usuários
                    System.out.println("Playlist vazia. Aguardando novos vídeos...");
                    Thread.sleep(5000);
                    continue;
                }

                currentVideoId = nextVideoId;

                // NOVO: Registra o tempo AGORA (Timestamp Unix em ms)
                currentVideoStartTimeMs = System.currentTimeMillis();

                // *** REMOVIDO: youtubePlaylist.offer(nextVideoId); ***
                // O vídeo toca apenas UMA VEZ até ser pulado.

                System.out.println("Trocando para novo vídeo: " + currentVideoId + " | Tempo de início: " + currentVideoStartTimeMs);

                // Cria o payload JSON para sincronização
                String jsonPayload = String.format("{\"videoId\":\"%s\", \"startTime\":%d}",
                        currentVideoId,
                        currentVideoStartTimeMs);

                // AJUSTE CRUCIAL: Notifica ouvintes com o novo JSON e evento "sync"
                metadataListeners.removeIf(emitter -> {
                    try {
                        // Tenta enviar o JSON como evento "sync"
                        emitter.send(SseEmitter.event().name("sync").data(jsonPayload, MediaType.APPLICATION_JSON));
                        return false; // Emitter OK
                    } catch (IOException e) {
                        // Cliente se desconectou (Conexão Anulada/Abortada)
                        System.out.println("Cliente SSE desconectado (ID: " + currentVideoId + "). Removendo emitter.");
                        emitter.completeWithError(e);
                        return true; // Remove este emitter da lista
                    }
                });

                // ESPERA: A thread fica aqui até ser INTERROMPIDA pelo método skipSong()
                Thread.sleep(Long.MAX_VALUE);
            }
        } catch (InterruptedException e) {
            // Exceção lançada por Thread.sleep(Long.MAX_VALUE) quando o skip é acionado
            System.out.println("O loop da playlist foi interrompido (Skip acionado). Indo para o próximo vídeo.");
            // A thread terminará e uma nova será iniciada por skipSong()
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}