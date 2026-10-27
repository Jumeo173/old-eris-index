package com.example.olderisindex;

import com.example.olderisindex.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class OldErisIndexApplication {
    public static void main(String[] args) {
        SpringApplication.run(OldErisIndexApplication.class, args);
    }
}
