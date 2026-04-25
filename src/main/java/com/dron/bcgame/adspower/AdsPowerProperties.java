package com.dron.bcgame.adspower;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "adspower")
public class AdsPowerProperties {
    private boolean enabled = true;
    private String url = "http://local.adspower.net:50325";
    private List<String> profileIds;
}
