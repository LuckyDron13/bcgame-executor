package com.dron.bcgame.telegram;

import com.dron.bcgame.bet.BetSignalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotifier {

    private final TelegramProperties props;
    private TelegramClient telegramClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.telegramClient = new OkHttpTelegramClient(props.getBotToken());
    }

    public void sendBetPlaced(BetSignalRequest signal, double amount) {
        String text = String.format(
                "BC.Game — ставка поставлена\n%s\n%s @ %.2f\nСумма: $%.2f",
                signal.getEvent(), signal.getMarket(), signal.getOdds(), amount
        );
        send(text);
    }

    public void sendBetFailed(BetSignalRequest signal, String reason) {
        String text = String.format(
                "BC.Game — ОШИБКА ставки\n%s\n%s @ %.2f\n%s",
                signal.getEvent(), signal.getMarket(), signal.getOdds(), reason
        );
        send(text);
    }

    private void send(String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(props.getChatId())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }
}
