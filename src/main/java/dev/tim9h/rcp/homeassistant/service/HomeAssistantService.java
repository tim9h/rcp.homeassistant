package dev.tim9h.rcp.homeassistant.service;

import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;

import com.github.thibstars.jhaapi.Configuration;
import com.github.thibstars.jhaapi.client.calendars.CalendarServiceImpl;
import com.github.thibstars.jhaapi.client.events.EntityEventServiceImpl;
import com.github.thibstars.jhaapi.client.services.LightServiceImpl;
import com.github.thibstars.jhaapi.client.states.StatesServiceImpl;
import com.github.thibstars.jhaapi.client.status.StatusServiceImpl;
import com.github.thibstars.jhaapi.client.websocket.WebSocketServiceImpl;
import com.github.thibstars.jhaapi.internal.exceptions.ClientException;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.homeassistant.HomeAssistantView;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;

@Singleton
public class HomeAssistantService {

	@InjectLogger
	private Logger logger;

	@Inject
	private Settings settings;

	@Inject
	private EventManager eventManager;

	private LightServiceImpl lightService;

	private static StatesServiceImpl statesService;

	private static CalendarServiceImpl calendarService;

	private static WebSocketServiceImpl websocketService;

	private static EntityEventServiceImpl entityEventService;

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
		statesService = new StatesServiceImpl(config);
		calendarService = new CalendarServiceImpl(config);
		websocketService = new WebSocketServiceImpl(config);
		entityEventService = new EntityEventServiceImpl(websocketService);

		entityEventService.start();
	}

	public void shutdown() {
		logger.info(() -> "Shutting down HomeAssistantService");
		entityEventService.stop();
		websocketService.close();
	}

	public void toggleLight(String entityId) {
		lightService.toggle(entityId);
	}

	public boolean isLightOn(String entityId) {
		var state = statesService.getState("light." + entityId);
		if (state.isPresent()) {
			return "on".equals(state.get().state());
		} else {
			throw new RuntimeException("Failed to get state for entity: " + entityId);
		}
	}

	public void listenToLight(String entityId, Consumer<Boolean> onStateChange) {
		entityEventService.onTurnedOff("light." + entityId, _ -> onStateChange.accept(false));
		entityEventService.onTurnedOn("light." + entityId, _ -> onStateChange.accept(true));
	}

}
