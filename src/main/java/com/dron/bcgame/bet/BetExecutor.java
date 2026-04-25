package com.dron.bcgame.bet;

import com.dron.bcgame.browser.BrowserProvider;
import com.dron.bcgame.browser.BrowserProvider.ProfilePage;
import com.dron.bcgame.telegram.TelegramNotifier;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class BetExecutor {

    private static final double AMOUNT = 1.0;
    private static final double ODDS_TOLERANCE = 0.15;

    private final BrowserProvider browserProvider;
    private final TelegramNotifier telegramNotifier;

    public void execute(BetSignalRequest signal) {
        if (signal.getEventUrl() != null && signal.getEventUrl().contains("/en/bti")) {
            log.info("[EXEC] skip BTI event: {} | {}", signal.getEvent(), signal.getMarket());
            return;
        }
        ProfilePage pp = browserProvider.newPage();
        log.info("[{}] Executing bet: {} | {} @ {}", pp.profileId(), signal.getEvent(), signal.getMarket(), signal.getOdds());
        try {
            placeBet(pp.profileId(), pp.page(), signal);
            telegramNotifier.sendBetPlaced(signal, AMOUNT);
        } catch (Exception e) {
            log.error("[{}] Bet failed [{}] {}: {}", pp.profileId(), signal.getEvent(), signal.getMarket(), e.getMessage(), e);
            telegramNotifier.sendBetFailed(signal, e.getMessage());
        } finally {
            pp.page().close();
        }
    }

    private void placeBet(String profileId, Page page, BetSignalRequest signal) {
        log.info("[{}] navigating to: {}", profileId, signal.getEventUrl());
        page.navigate(signal.getEventUrl());
        page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15_000));

        page.locator("[data-editor-id=\"tableMarketWrapper\"]")
                .first()
                .waitFor(new Locator.WaitForOptions().setTimeout(25_000));
        log.info("[{}] markets rendered, looking for section '{}'", profileId, signal.getMarketName());

        Locator marketWrapper = page
                .locator("[data-editor-id=\"tableMarketWrapper\"]")
                .filter(new Locator.FilterOptions().setHasText(signal.getMarketName()))
                .first();
        marketWrapper.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

        Locator plates = marketWrapper.locator("[data-editor-id=\"tableOutcomePlate\"]");
        int count = plates.count();
        Locator target = null;
        for (int i = 0; i < count; i++) {
            Locator plate = plates.nth(i);
            String plateText = plate.locator("[data-editor-id=\"tableOutcomePlateName\"]")
                    .textContent().trim();
            if (outcomeMatches(plateText, signal.getMarket())) {
                double currentOdds = extractOdds(plate);
                if (currentOdds > 0 && Math.abs(currentOdds - signal.getOdds()) > ODDS_TOLERANCE) {
                    throw new RuntimeException(String.format(
                            "Odds moved: expected %.2f, got %.2f", signal.getOdds(), currentOdds));
                }
                target = plate;
                break;
            }
        }
        if (target == null) {
            throw new RuntimeException("Outcome not found: '" + signal.getMarket() + "' in [" + signal.getMarketName() + "]");
        }

        target.click();

        Locator stakeInput = page.locator("label[data-editor-id=\"betslipStakeInput\"] input");
        stakeInput.waitFor(new Locator.WaitForOptions().setTimeout(8_000));
        stakeInput.fill(String.valueOf(AMOUNT));

        page.locator("button[data-editor-id=\"betslipPlaceBetButton\"]").click();

        Locator notification = page.locator("[data-editor-id=\"betslipNotification\"]");
        notification.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        if (!notification.locator("[data-editor-id=\"successIcon\"]").isVisible()) {
            String notifText = "";
            try { notifText = notification.textContent().trim(); } catch (Exception ignored) {}
            log.error("[{}] bet rejected by BC.Game, notification: '{}'", profileId, notifText);
            throw new RuntimeException("Bet placement failed: " + notifText);
        }

        log.info("[{}] Bet placed OK: {} | {} @ {} x${}", profileId, signal.getEvent(), signal.getMarket(), signal.getOdds(), AMOUNT);
    }

    private double extractOdds(Locator plate) {
        try {
            Object result = plate.evaluate("""
                    el => {
                        const spans = [...el.querySelectorAll('span')];
                        const s = spans.find(s => /^\\d+(\\.\\d+)?$/.test(s.textContent.trim()));
                        return s ? parseFloat(s.textContent.trim()) : 0;
                    }
                    """);
            return result instanceof Number ? ((Number) result).doubleValue() : 0.0;
        } catch (Exception e) {
            log.warn("extractOdds failed: {}", e.getMessage());
            return 0.0;
        }
    }

    private boolean outcomeMatches(String pageText, String signalMarket) {
        String page   = normalize(pageText);
        String signal = normalize(signalMarket);
        return page.contains(signal) || signal.contains(page);
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
