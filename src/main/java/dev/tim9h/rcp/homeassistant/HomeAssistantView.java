package dev.tim9h.rcp.homeassistant;

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.Logger;

import com.google.inject.Inject;

import dev.tim9h.rcp.event.EventManager;
import dev.tim9h.rcp.homeassistant.service.HomeAssistantService;
import dev.tim9h.rcp.logging.InjectLogger;
import dev.tim9h.rcp.settings.Settings;
import dev.tim9h.rcp.spi.CommandBuilder;
import dev.tim9h.rcp.spi.CommandNode;
import dev.tim9h.rcp.spi.Plugin;

public class HomeAssistantView implements Plugin {

	public static final String SETTING_URL = "homeassistant.url";

	public static final String SETTING_APIKEY = "homeassistant.apikey";

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
		}).up().child("test", _ -> { // TODO: remove later
			service.toggleLight();
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

}
