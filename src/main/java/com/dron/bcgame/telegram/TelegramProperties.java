package com.dron.bcgame.telegram;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "tg")
public class TelegramProperties {
    private String botToken;
    private String chatId;
}
