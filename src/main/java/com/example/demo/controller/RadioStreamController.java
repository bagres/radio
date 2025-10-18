package com.example.demo.controller;

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
    public String addYoutubeAudio(@RequestParam("videoId") String urlOrVideoId) {
        if (urlOrVideoId == null || urlOrVideoId.isEmpty()) {
            return "Erro: ID do vídeo inválido.";
        }

        String videoId = extractVideoId(urlOrVideoId);

        if (videoId == null) {
            return "Erro: Não foi possível extrair a ID válida do vídeo ou URL fornecida.";
        }

        // Adiciona o ID à playlist
        // Supondo que 'youtubePlaylist' é uma estrutura de fila (Queue) ou lista
        youtubePlaylist.offer(videoId);
        return "ID do YouTube adicionada à playlist: " + videoId;
    }

    private String extractVideoId(String urlOrVideoId) {
        // 1. Caso a entrada JÁ SEJA a ID do vídeo (e não uma URL)
        if (urlOrVideoId.length() <= 11 && !urlOrVideoId.contains("/")) {
            return urlOrVideoId;
        }

        // 2. Tentar extrair de formatos curtos ou de compartilhamento (youtu.be, /embed/)
        String regexShort = "(?:youtu\\.be\\/|\\/embed\\/|\\/v\\/|watch\\?v=|v%3D|v\\=|youtu\\.be\\/)([^#\\&\\?]{11})";

        Pattern pattern = Pattern.compile(regexShort, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(urlOrVideoId);

        if (matcher.find()) {
            // O grupo 1 ([^#\\&\\?]{11}) captura os 11 caracteres da ID
            return matcher.group(1);
        }

        // 3. Tentar extrair de URLs completas usando a classe URL (mais seguro, mas mais complexo)
        try {
            URL url = new URL(urlOrVideoId);
            String query = url.getQuery();
            if (query != null) {
                // Busca o parâmetro 'v=' na query string
                for (String param : query.split("&")) {
                    if (param.startsWith("v=")) {
                        return param.substring(2);
                    }
                }
            }
        } catch (MalformedURLException e) {
            // Se a entrada não for uma URL válida, ignora este bloco e prossegue para a falha
        }

        return null;
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
    // ===============================================
// ENDPOINT 4: HEALTH CHECK / KEEP-ALIVE
// ===============================================
    @GetMapping("/radio/status")
    public String statusCheck() {
        return "OK";
    }
}