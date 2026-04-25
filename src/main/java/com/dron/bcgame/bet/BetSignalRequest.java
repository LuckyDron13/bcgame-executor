package com.dron.bcgame.bet;

import lombok.Data;

@Data
public class BetSignalRequest {
    private String event;
    /** Категория маркета на BC.game, например "Total corners", "Asian corners" */
    private String marketName;
    /** Исход внутри маркета, например "over 7.5" */
    private String market;
    private double odds;
    private double amount;
    private String eventUrl;
}
