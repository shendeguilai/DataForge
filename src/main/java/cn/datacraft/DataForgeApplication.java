package cn.datacraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class DataForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataForgeApplication.class, args);
    }
}
