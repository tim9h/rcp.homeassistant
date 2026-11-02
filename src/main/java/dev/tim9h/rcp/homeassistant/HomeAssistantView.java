package dev.tim9h.rcp.homeassistant;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.event.Event;
import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.homeassistant.service.HomeAssistantService;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CommandBuilder;
import dev.tim9h.rcp.spi.CommandNode;
import dev.tim9h.rcp.spi.Gravity;
import dev.tim9h.rcp.spi.Plugin;
import dev.tim9h.rcp.spi.Position;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.util.Pair;

public class HomeAssistantView implements Plugin {

	public static final String SETTING_URL = "homeassistant.url";

	public static final String SETTING_APIKEY = "homeassistant.apikey";

	public static final String SETTING_ENTITIES_LIGHTS = "homeassistant.entities.lights";

	@InjectLogger
	private Logger logger;

	@Inject
	private EventManager eventManager;

	@Inject
	private Settings settings;

	@Inject
	private HomeAssistantService service;

	private static final long CALENDAR_INTERVAL_MINUTES = 15;

	private ScheduledExecutorService executor;

	private ScheduledFuture<?> calendarTask;

	private volatile long lastCalendarPublish;

	private final Map<String, ToggleButton> lightButtons = new ConcurrentHashMap<>();

	private final ChangeListener<Boolean> focusListener = (_, _, _) -> eventManager
			.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));

	@Override
	public void init() {
		executor = Executors.newScheduledThreadPool(2, runnable -> {
			var thread = new Thread(runnable, "homeassistant-worker");
			thread.setDaemon(true);
			return thread;
		});
		executor.execute(() -> {
			try {
				if (service.connect()) {
					refreshLightStates();
				}
			} catch (Exception e) {
				logger.error(() -> "Failed to connect to Home Assistant", e);
			}
			startCalendarPolling();
		});
	}

	@Override
	public void onShown() {
		if (executor != null) {
			executor.execute(() -> {
				try {
					if (service.connect()) {
						refreshLightStates();
					}
				} catch (Exception e) {
					logger.error(() -> "Failed to connect to Home Assistant", e);
				}
				startCalendarPolling();
			});
		}
	}

	@Override
	public void onHidden() {
		stopCalendarPolling();
	}

	@Override
	public void onShutdown() {
		stopCalendarPolling();
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		}
		lightButtons.clear();
		try {
			service.shutdown();
		} catch (Exception e) {
			logger.warn(() -> "Failed to shut down Home Assistant service", e);
		}
	}

	private synchronized void startCalendarPolling() {
		if (executor == null || executor.isShutdown() || (calendarTask != null && !calendarTask.isDone())) {
			return;
		}
		var intervalMillis = TimeUnit.MINUTES.toMillis(CALENDAR_INTERVAL_MINUTES);
		var remaining = intervalMillis - (System.currentTimeMillis() - lastCalendarPublish);
		var delay = Math.clamp(remaining, 0, intervalMillis);
		calendarTask = executor.scheduleAtFixedRate(this::publishCalendarEventsSafely, delay, intervalMillis,
				TimeUnit.MILLISECONDS);
	}

	private synchronized void stopCalendarPolling() {
		if (calendarTask != null) {
			calendarTask.cancel(false);
			calendarTask = null;
		}
	}

	private void publishCalendarEvents() {
		if (!service.isConnected()) {
			return;
		}
		var event = service.getNextCalendarEvent().orElse(null);
		lastCalendarPublish = System.currentTimeMillis();
		Platform.runLater(() -> eventManager.post(new Event(Event.EVENT_CALENDAR_NEXT_EVENT, event)));
	}

	private void publishCalendarEventsSafely() {
		try {
			publishCalendarEvents();
		} catch (Exception e) {
			logger.error(() -> "Failed to publish Home Assistant calendar event", e);
		}
	}

	private Node createLightButton(String label, String entityId) {
		var button = new ToggleButton(label);
		button.setOnAction(_ -> {
			executor.execute(() -> {
				try {
					service.toggleLight(entityId);
				} catch (Exception e) {
					logger.error(() -> "Failed to toggle light " + entityId, e);
				}
			});
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
		});
		button.focusedProperty().addListener(focusListener);
		lightButtons.put(entityId, button);
		service.listenToLight(entityId, on -> Platform.runLater(() -> button.setSelected(on)));
		if (service.isConnected()) {
			executor.execute(() -> {
				try {
					refreshLightState(entityId, button);
				} catch (Exception e) {
					logger.error(() -> "Failed to read light state " + entityId, e);
				}
			});
		}

		var buttonWidth = 100;
		button.setPrefWidth(buttonWidth);
		button.setMinWidth(buttonWidth);
		button.setMaxWidth(buttonWidth);
		button.setTextOverrun(OverrunStyle.ELLIPSIS);

		return button;
	}

	private List<Pair<String, String>> getPersistedLights() {
		var lights = settings.getStringList(SETTING_ENTITIES_LIGHTS);
		if (lights.isEmpty()) {
			return Collections.emptyList();
		}
		return lights.stream().map(keyVal -> {
			var parts = keyVal.split(":");
			if (parts.length > 1) {
				return new Pair<String, String>(parts[0], parts[1]);
			} else {
				return new Pair<String, String>(parts[0], parts[0]);
			}
		}).toList();
	}

	private void refreshLightStates() {
		lightButtons.forEach((entityId, button) -> {
			try {
				refreshLightState(entityId, button);
			} catch (Exception e) {
				logger.error(() -> "Failed to read light state " + entityId, e);
			}
		});
	}

	private void refreshLightState(String entityId, ToggleButton button) {
		var on = service.isLightOn(entityId);
		Platform.runLater(() -> button.setSelected(on));
	}

	@Override
	public String getName() {
		return "Home Assistant";
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		return Map.of(SETTING_URL, "http://localhost:8123", SETTING_APIKEY, "");
	}

	@Override
	public Optional<CommandNode> getCommands() {
		return new CommandBuilder().command("homeassistant").child("url", _ -> {
			eventManager.echo("Home Assistant URL", settings.getString(SETTING_URL));
		}).argumentAction(url -> {
			var apiUrl = ensureApiSuffix(url);
			settings.persist(SETTING_URL, apiUrl);
			eventManager.echo("Updated Home Assistant URL", apiUrl);
		}).up().child("apikey", _ -> {
			eventManager.echo("Home Assistant ApiKey", settings.getString(SETTING_APIKEY));
		}).argumentAction(apikey -> {
			settings.persist(SETTING_APIKEY, apikey);
			eventManager.echo("Updated Home Assistant Api Key", apikey);
		}).build();
	}

	private static String ensureApiSuffix(String url) {
		if (url == null || url.isEmpty()) {
			return "";
		}
		var cleanedUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		if (!cleanedUrl.endsWith("/api")) {
			return cleanedUrl + "/api";
		}
		return cleanedUrl;
	}

	@Override
	public Optional<Node> getNode() throws IOException {
		var lights = getPersistedLights();
		if (lights.isEmpty()) {
			return Optional.empty();
		}

		lightButtons.clear();
		var pane = new FlowPane();
		pane.getStyleClass().addAll("ccCard", "centeredFlowPane");
		lights.stream().map(light -> createLightButton(light.getKey(), light.getValue()))
				.forEach(pane.getChildren()::add);

		return Optional.of(pane);
	}

	@Override
	public Gravity getGravity() {
		return new Gravity(5, Position.MIDDLE);
	}

}
