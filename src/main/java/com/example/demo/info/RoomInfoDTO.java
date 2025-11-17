package com.example.demo.info;

public record RoomInfoDTO(
        String roomId,
        int listeners,
        int playlistSize,
        String currentVideo
) {}

