# rcp.homeassistant

Home Assistant plugin for RCP.

## Build

	mvn -B verify

## Usage

Copy the built jar into the plugins directory of the RCP runtime:

	target/rcp.homeassistant-<version>.jar

The RCP runtime loads every `*.jar` found in the `plugins` directory next to
the application.
