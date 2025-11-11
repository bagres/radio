package com.example.demo.controller;

import com.example.demo.info.ChatMessage;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Controller
public class ChatController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_MESSAGE_LENGTH = 250;
    private static final Safelist CHAT_SAFELIST = Safelist.none();

    @MessageMapping("/sendMessage")
    @SendTo("/topic/public")
    public ChatMessage sendMessage(ChatMessage chatMessage) {

        String messageContent = chatMessage.getContent();
        String safeContent;

        if (messageContent == null || messageContent.trim().isEmpty()) {
            return null;
        }

        safeContent = Jsoup.clean(messageContent, CHAT_SAFELIST);

        if (safeContent.trim().isEmpty()) {
            return null;
        }

        if (messageContent.length() > MAX_MESSAGE_LENGTH) {
            return createErrorMessage(chatMessage.getSender(),
                    "' tentou fazer spam",
                    "ERROR");
        }

        chatMessage.setContent(safeContent);
        chatMessage.setTimestamp(LocalTime.now().format(FORMATTER));

        if (chatMessage.getType() == null || chatMessage.getType().equals("ERROR")) {
            chatMessage.setType("CHAT");
        }

        return chatMessage;
    }

    private ChatMessage createErrorMessage(String originalSender, String errorMessage, String type) {
        ChatMessage error = new ChatMessage();
        error.setSender("[SERVER]");
        error.setContent("O bagre '" + originalSender + errorMessage);
        error.setTimestamp(LocalTime.now().format(FORMATTER));
        error.setType(type);
        return error;
    }
}