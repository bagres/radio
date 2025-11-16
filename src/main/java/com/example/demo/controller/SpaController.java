package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/radio")
public class SpaController {

    @GetMapping("/{room}/**")
    public String radioIndexWildcard() {
        return "forward:/spa.html";
    }


}
