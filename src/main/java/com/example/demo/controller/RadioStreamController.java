package com.example.demo.controller;

import com.example.demo.info.RadioRoom;
import com.example.demo.info.RoomInfoDTO;
import com.example.demo.info.VideoInfo;
import com.example.demo.service.RadioStreamService;
import com.example.demo.service.RadioStreamService.SearchResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;

@RestController
@RequestMapping("/radio")
public class RadioStreamController {

    private final RadioStreamService service;

    public RadioStreamController(RadioStreamService service) {
        this.service = service;
    }
    @GetMapping("/list-rooms")
    public List<RoomInfoDTO> listRooms(){
        return service.listRooms();
    }
    @PostMapping("/add-youtube/{room}")
    public ResponseEntity<String> addYoutubeAudio(
            @PathVariable String room,
            @RequestParam("videoId") String input) {

        if (input == null || input.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Erro: Input vazio.");
        }

        String videoId = service.resolveQueryToVideoId(input);

        if (videoId == null) return ResponseEntity.badRequest().body("Erro: Não foi possível encontrar um vídeo para o input fornecido.");


        service.addMusicToPlaylist(room,videoId);
        return ResponseEntity.ok().body(videoId);
    }
    @PostMapping("/add/{room}")
    public ResponseEntity<String> addByUrlOrSearch(@PathVariable("room") String room,@RequestParam("input") String input) {
        if (input == null || input.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Erro: Input vazio.");
        }

        String videoId = service.resolveQueryToVideoId(input);

        if (videoId == null) {
            return ResponseEntity.badRequest().body("Erro: Não foi possível encontrar um vídeo para o input fornecido.");
        }
        return ResponseEntity.ok(service.addMusicToPlaylist(room,videoId));
    }

    @GetMapping(value = "/metadata/{room}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetadata(@PathVariable String room) {
        SseEmitter emitter = service.createSseEmitter(room);
        try {
            service.sendInitialSync(room, emitter);
        } catch (IOException e) {
        }
        return emitter;
    }

    @PostMapping("/skip/{room}")
    public String skipSong(@PathVariable String room,
                           @RequestParam("videoId") String videoId) {
        return service.skipCurrentSong(room, videoId);
    }

    //Apagar na proxima versao
    @GetMapping("/status")
    public String statusCheck() {
        return "OK";
    }

    @GetMapping("/playlist/{room}")
    public Queue<VideoInfo> listaMusicas(@PathVariable String room) {
        return service.getPlaylist(room);
    }

    @GetMapping("/listeners/{room}")
    public int getListenerCount(@PathVariable String room) {
        return service.getListenerCount(room);
    }

}
