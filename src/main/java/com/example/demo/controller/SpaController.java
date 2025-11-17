package com.example.demo.controller;

import com.example.demo.service.RadioStreamService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/radio")
public class SpaController {
    private final RadioStreamService radioStreamService;

    public SpaController(RadioStreamService radioStreamService) {
        this.radioStreamService = radioStreamService;
    }

    @GetMapping("/{room}/**")
    public String radioIndexWildcard(@PathVariable("room") String room) {
        radioStreamService.getRoom(room);
        return "forward:/spa.html";
    }


}
