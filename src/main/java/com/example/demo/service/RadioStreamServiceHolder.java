package com.example.demo.service;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RadioStreamServiceHolder {

    private RadioStreamService service;

    @Autowired
    public RadioStreamServiceHolder(RadioStreamService service) {
        this.service = service;
    }

    public void closeRoomExternally(String roomId) {
        service.closeRoom(roomId);
    }
}


