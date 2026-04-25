package com.dron.bcgame.browser;

import com.dron.bcgame.adspower.AdsPowerClient;
import com.dron.bcgame.adspower.AdsPowerProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BrowserProvider {

    public record ProfilePage(String profileId, Page page) {}

    private final Playwright playwright;
    private final List<String> profileIds;
    private final List<Browser> browsers;
    private final AtomicInteger counter = new AtomicInteger(0);

    public BrowserProvider(AdsPowerProperties props, AdsPowerClient adsPowerClient) {
        this.playwright = Playwright.create();
        this.profileIds = props.getProfileIds();
        this.browsers = profileIds.stream()
                .map(id -> connectBrowser(id, adsPowerClient))
                .toList();
    }

    private Browser connectBrowser(String profileId, AdsPowerClient adsPowerClient) {
        log.info("Connecting AdsPower profile {}", profileId);
        if (!adsPowerClient.isRunning(profileId)) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        String wsEndpoint = adsPowerClient.startBrowser(profileId);
        log.info("CDP endpoint for {}: {}", profileId, wsEndpoint);
        return playwright.chromium().connectOverCDP(wsEndpoint);
    }

    public ProfilePage newPage() {
        int idx = Math.abs(counter.getAndIncrement() % browsers.size());
        return new ProfilePage(profileIds.get(idx), browsers.get(idx).contexts().get(0).newPage());
    }

    public int profileCount() {
        return browsers.size();
    }

    @PreDestroy
    public void destroy() {
        playwright.close();
    }
}
