package dev.tim9h.rcp.homeassistant;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	@Override
	public void init() {
		service.connect();
	}

	@Override
	public void onShutdown() {
		service.shutdown();
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

		var pane = new FlowPane();
		pane.getStyleClass().addAll("ccCard", "centeredFlowPane");
		lights.stream().map(light -> createLightButton(light.getKey(), light.getValue()))
				.forEach(button -> pane.getChildren().add(button));
		return Optional.of(pane);
	}

	@Override
	public Gravity getGravity() {
		return new Gravity(5, Position.MIDDLE);
	}

	private Node createLightButton(String label, String entityId) {
		var button = new ToggleButton(label);
		button.setSelected(service.isLightOn(entityId));
		button.setOnAction(_ -> {
			service.toggleLight(entityId);
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
		});
		button.focusedProperty().addListener((_, _, _) -> {
			eventManager.post(new Event(Event.EVENT_CLI_REQUEST_FOCUS));
		});
		service.listenToLight(entityId, button::setSelected);

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

}
