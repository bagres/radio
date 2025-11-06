package com.example.demo.info;

public class VideoInfo {
    private String videoId;
    private String title;

    public VideoInfo(String videoId, String title) {
        this.videoId = videoId;
        this.title = title;
    }

    public String getVideoId() {
        return videoId;
    }
    public String getTitle() {
        return title;
    }
}
