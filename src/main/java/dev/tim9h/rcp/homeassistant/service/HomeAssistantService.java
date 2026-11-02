package dev.tim9h.rcp.homeassistant.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;

import com.github.thibstars.jhaapi.Configuration;
import com.github.thibstars.jhaapi.client.calendars.CalendarServiceImpl;
import com.github.thibstars.jhaapi.client.calendars.response.Calendar;
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

	private static final Duration CALENDAR_CACHE_TTL = Duration.ofHours(1);

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

	private volatile boolean connected;

	private final Map<String, Consumer<Boolean>> lightListeners = new ConcurrentHashMap<>();

	private final Map<String, Boolean> registeredEntities = new ConcurrentHashMap<>();

	private volatile List<String> cachedCalendarIds = List.of();

	private volatile long calendarCacheTimestamp;

	public synchronized boolean connect() {
		if (connected) {
			return true;
		}
		var baseUrl = settings.getString(HomeAssistantView.SETTING_URL);
		var token = settings.getString(HomeAssistantView.SETTING_APIKEY);
		if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
			logger.warn(() -> "Home Assistant URL or API key is not set. Please configure them in the settings.");
			return false;
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
			return false;
		}

		try {
			lightService = new LightServiceImpl(config);
			statesService = new StatesServiceImpl(config);
			calendarService = new CalendarServiceImpl(config);
			websocketService = new WebSocketServiceImpl(config);
			entityEventService = new EntityEventServiceImpl(websocketService);
			entityEventService.start();
		} catch (Exception e) {
			logger.error(() -> "Failed to start Home Assistant event service", e);
			closeQuietly();
			return false;
		}
		connected = true;
		lightListeners.keySet().forEach(this::registerEntity);
		return true;
	}

	public boolean isConnected() {
		return connected;
	}

	public synchronized void shutdown() {
		logger.info(() -> "Shutting down HomeAssistantService");
		connected = false;
		lightListeners.clear();
		registeredEntities.clear();
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			if (entityEventService != null) {
				entityEventService.stop();
			}
		} catch (Exception e) {
			logger.warn(() -> "Failed to stop entity event service", e);
		}
		try {
			if (websocketService != null) {
				websocketService.close();
			}
		} catch (Exception e) {
			logger.warn(() -> "Failed to close websocket service", e);
		}
		entityEventService = null;
		websocketService = null;
	}

	public void toggleLight(String entityId) {
		if (!connected) {
			return;
		}
		lightService.toggle(entityId);
	}

	public boolean isLightOn(String entityId) {
		if (!connected) {
			return false;
		}
		var state = statesService.getState("light." + entityId);
		if (state.isPresent()) {
			return "on".equals(state.get().state());
		} else {
			logger.error(() -> "Failed to get state for entity: " + entityId);
			eventManager.echo("Failed to get state for entity ", entityId);
			return false;
		}
	}

	/**
	 * Registers the (single) listener for a light. A previous listener for the same
	 * entity is replaced. The callback is invoked on the websocket thread.
	 */
	public void listenToLight(String entityId, Consumer<Boolean> onStateChange) {
		lightListeners.put(entityId, onStateChange);
		if (connected) {
			registerEntity(entityId);
		}
	}

	private synchronized void registerEntity(String entityId) {
		if (!connected || registeredEntities.putIfAbsent(entityId, Boolean.TRUE) != null) {
			return;
		}
		entityEventService.onTurnedOff("light." + entityId, _ -> notifyListener(entityId, false));
		entityEventService.onTurnedOn("light." + entityId, _ -> notifyListener(entityId, true));
	}

	private void notifyListener(String entityId, boolean on) {
		var listener = lightListeners.get(entityId);
		if (listener != null) {
			listener.accept(on);
		}
	}

	public Optional<Object[]> getNextCalendarEvent() {
		if (!connected) {
			return Optional.empty();
		}
		var zone = ZoneId.systemDefault();
		var start = OffsetDateTime.now(zone);
		var end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toOffsetDateTime();

		return getCalendarIds().stream().flatMap(id -> calendarService.getCalendarEvents(id, start, end).stream())
				.filter(event -> !event.start().isBefore(start)).min(Comparator.comparing(CalendarEvent::start))
				.map(event -> new Object[] { event.summary(), event.start(), isAllDay(event.start(), event.end()) });
	}

	private List<String> getCalendarIds() {
		var now = System.currentTimeMillis();
		if (cachedCalendarIds.isEmpty() || now - calendarCacheTimestamp > CALENDAR_CACHE_TTL.toMillis()) {
			cachedCalendarIds = calendarService.getCalendars().stream().map(Calendar::entityId).toList();
			calendarCacheTimestamp = now;
		}
		return cachedCalendarIds;
	}

	private boolean isAllDay(OffsetDateTime start, OffsetDateTime end) {
		return start.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalTime().equals(LocalTime.MIDNIGHT)
				&& end.toLocalDate().isAfter(start.toLocalDate());
	}

}
