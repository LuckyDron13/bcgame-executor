package com.dron.bcgame.adspower;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdsPowerClient {

    private final AdsPowerProperties props;
    private final RestTemplate restTemplate;

    public String startBrowser(String profileId) {
        String url = props.getUrl() + "/api/v1/browser/start?user_id=" + profileId;
        AdsPowerResponse response = restTemplate.getForObject(url, AdsPowerResponse.class);
        assertSuccess(response, "start browser");
        return response.getData().getWs().getPuppeteer();
    }

    public boolean isRunning(String profileId) {
        String url = props.getUrl() + "/api/v1/browser/active?user_id=" + profileId;
        AdsPowerResponse response = restTemplate.getForObject(url, AdsPowerResponse.class);
        return response != null && response.getCode() == 0;
    }

    public void stopBrowser(String profileId) {
        String url = props.getUrl() + "/api/v1/browser/stop?user_id=" + profileId;
        restTemplate.getForObject(url, AdsPowerResponse.class);
    }

    private void assertSuccess(AdsPowerResponse response, String op) {
        if (response == null || response.getCode() != 0) {
            String msg = response != null ? response.getMsg() : "null response";
            throw new AdsPowerException("AdsPower " + op + " failed: " + msg);
        }
    }
}
