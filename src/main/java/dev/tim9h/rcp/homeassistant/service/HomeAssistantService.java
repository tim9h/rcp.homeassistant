package dev.tim9h.rcp.homeassistant.service;

import org.apache.logging.log4j.Logger;

import com.github.thibstars.jhaapi.Configuration;
import com.github.thibstars.jhaapi.client.services.LightServiceImpl;
import com.github.thibstars.jhaapi.client.status.StatusServiceImpl;
import com.github.thibstars.jhaapi.internal.exceptions.ClientException;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.homeassistant.HomeAssistantView;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;

@Singleton
public class HomeAssistantService {

	private static String DESK = "kajplats_e14_ws_globe_806lm"; // TODO: make this configurable

	@InjectLogger
	private Logger logger;

	@Inject
	private Settings settings;

	@Inject
	private EventManager eventManager;

	private LightServiceImpl lightService;

	public void connect() {
		var baseUrl = settings.getString(HomeAssistantView.SETTING_URL);
		var token = settings.getString(HomeAssistantView.SETTING_APIKEY);
		if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
			logger.warn(() -> "Home Assistant URL or API key is not set. Please configure them in the settings.");
			return;
		}
		var config = Configuration.builder().baseUrl(baseUrl).token(token).build();
		var statusService = new StatusServiceImpl(config);
		try {
			statusService.getStatus().ifPresentOrElse(status -> {
				logger.info(() -> "HomeAssistantService initialized: " + status.message());
			}, () -> {
				eventManager.echo("Home Assistant", "Failed to get status from Home Assistant");
				logger.error(() -> "Failed to get status from Home Assistant");
			});
		} catch (ClientException e) {
			eventManager.echo("Failed to get status from Home Assistant: ", e.getMessage());
			logger.error(() -> "Failed to get status from Home Assistant", e);
			return;
		}

		lightService = new LightServiceImpl(config);
	}

	public void shutdown() {
		// No explicit shutdown needed for the services, but you can add cleanup logic
		// here if necessary
	}

	public void toggleLight() {
		lightService.toggle(DESK);
	}

}
