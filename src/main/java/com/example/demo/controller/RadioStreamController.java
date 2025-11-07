package com.example.demo.controller;

import com.example.demo.info.VideoInfo;
import com.example.demo.service.RadioStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Queue;


@RestController
public class RadioStreamController {

    private final RadioStreamService service;

    public RadioStreamController(RadioStreamService service) {
        this.service = service;
    }

    @PostMapping("/radio/add-youtube")
    public String addYoutubeAudio(@RequestParam("videoId") String urlOrVideoId) {
        return service.addMusicToPlaylist(urlOrVideoId);
    }

    @GetMapping(value = "/radio/metadata", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMetadata() {
        SseEmitter emitter = service.createSseEmitter();

        try {
            service.sendInitialSync(emitter);
        } catch (IOException e) {
            return emitter;
        }

        return emitter;
    }

    @PostMapping("/radio/skip")
    public String skipSong(@RequestParam("videoId") String videoId) {
        return service.skipCurrentSong(videoId);
    }

    @GetMapping("/radio/status")
    public String statusCheck() {
        return "OK";
    }

    @GetMapping("/radio-lista")
    public Queue<VideoInfo> listaMusicas(){
        return service.getPlaylist();
    }

    @GetMapping("/radio/listeners")
    public int getListenerCount() {
        return service.getListenerCount();
    }

}