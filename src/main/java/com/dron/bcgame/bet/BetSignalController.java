package com.dron.bcgame.bet;

import com.dron.bcgame.browser.BrowserProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api")
public class BetSignalController {

    private final BetExecutor betExecutor;
    private final ExecutorService executor;

    public BetSignalController(BetExecutor betExecutor, BrowserProvider browserProvider) {
        this.betExecutor = betExecutor;
        this.executor = Executors.newFixedThreadPool(browserProvider.profileCount());
    }

    @PostMapping("/bet-signal")
    public ResponseEntity<Void> receiveBetSignal(@RequestBody BetSignalRequest signal) {
        log.info("Received bet signal: {} {}", signal.getEvent(), signal.getMarket());
        executor.submit(() -> betExecutor.execute(signal));
        return ResponseEntity.accepted().build();
    }
}
