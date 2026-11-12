package fr.aeria.backoffice;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackofficeApplication {

    public static void main(String[] args) {
        // Every date shown to users is local time in Normandy, whatever the server's time zone.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
        SpringApplication.run(BackofficeApplication.class, args);
    }
}
