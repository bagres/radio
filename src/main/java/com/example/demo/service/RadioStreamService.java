package com.example.demo.service;

import lombok.Data;
import org.springframework.stereotype.Service;


@Service
@Data
public class RadioStreamService {
    private volatile String currentVideoId = null;

}