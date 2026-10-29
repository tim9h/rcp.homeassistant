module rcp.homeassistant {
	exports dev.tim9h.rcp.homeassistant;

	requires transitive rcp.api;
	requires com.google.guice;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.graphics;
	requires org.apache.logging.log4j;
}
