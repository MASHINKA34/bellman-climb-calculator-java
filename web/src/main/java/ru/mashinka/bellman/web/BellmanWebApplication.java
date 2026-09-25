package ru.mashinka.bellman.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// точка входа веб-приложения
@SpringBootApplication
public class BellmanWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BellmanWebApplication.class, args);
    }
}
