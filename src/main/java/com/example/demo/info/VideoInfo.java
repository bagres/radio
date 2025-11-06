package com.example.demo.info;

public class VideoInfo {
    private String videoId;
    private String title;
    private String status;

    public VideoInfo(String videoId, String title, String status) {
        this.videoId = videoId;
        this.title = title;
        this.status = status;
    }
    public String getVideoId() {
        return videoId;
    }
    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }
}
