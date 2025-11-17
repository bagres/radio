package com.example.demo.service;


public class RadioStreamServiceHolder {

    private static RadioStreamService service;

    public static void registerService(RadioStreamService srv) {
        service = srv;
    }

    public static void closeRoomExternally(String roomId) {
            service.closeRoom(roomId);

    }
}

