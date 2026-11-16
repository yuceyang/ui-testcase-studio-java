package com.yuceyang.uitest;

import com.yuceyang.uitest.storage.ProjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class UiTestcaseStudioApplication {
    private static final Logger log = LoggerFactory.getLogger(UiTestcaseStudioApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(UiTestcaseStudioApplication.class, args);
    }

    @Bean
    ApplicationRunner storageModeLogger(ProjectStore store) {
        return args -> log.info("数据存储模式：{}（local=JSON 文件，postgresql=PostgreSQL）", store.mode());
    }
}
