package dev.tim9h.rcp.homeassistant.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;

import com.github.thibstars.jhaapi.Configuration;
import com.github.thibstars.jhaapi.client.calendars.CalendarServiceImpl;
import com.github.thibstars.jhaapi.client.calendars.response.CalendarEvent;
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

	private StatesServiceImpl statesService;

	private CalendarServiceImpl calendarService;

	private WebSocketServiceImpl websocketService;

	private EntityEventServiceImpl entityEventService;

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
			logger.error(() -> "Failed to get state for entity: " + entityId);
			eventManager.echo("Failed to get state for entity ", entityId);
			return false;
		}
	}

	public void listenToLight(String entityId, Consumer<Boolean> onStateChange) {
		entityEventService.onTurnedOff("light." + entityId, _ -> onStateChange.accept(false));
		entityEventService.onTurnedOn("light." + entityId, _ -> onStateChange.accept(true));
	}

	public Object[] getNextCalendarEvent() {
		var zone = ZoneId.systemDefault();
		var start = OffsetDateTime.now(zone);
		var end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toOffsetDateTime();

		return calendarService.getCalendars().stream()
				.flatMap(calendar -> calendarService.getCalendarEvents(calendar.entityId(), start, end).stream())
				.filter(event -> !event.start().isBefore(start)).min(Comparator.comparing(CalendarEvent::start))
				.map(event -> new Object[] { event.summary(), event.start(), isAllDay(event.start(), event.end()) })
				.orElse(null);
	}

	private boolean isAllDay(OffsetDateTime start, OffsetDateTime end) {
		return start.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalTime().equals(LocalTime.MIDNIGHT)
				&& end.toLocalDate().isAfter(start.toLocalDate());
	}

}
