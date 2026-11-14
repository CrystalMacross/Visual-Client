package net.visualclient.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thin wrapper so the rest of the code does not care about the logging backend. */
public final class Log {
	private static final Logger LOGGER = LoggerFactory.getLogger("Visual Client");

	private Log() {
	}

	public static void info(String message, Object... args) {
		LOGGER.info(message, args);
	}

	public static void warn(String message, Object... args) {
		LOGGER.warn(message, args);
	}

	public static void error(String message, Throwable error) {
		LOGGER.error(message, error);
	}
}
