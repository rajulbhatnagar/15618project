package com.server;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public class HealthCheckHandler implements HttpHandler {

	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if (exchange.isInIoThread()) {
			exchange.dispatch(this);
			return;
		}
		exchange.getResponseSender().send("Healthy");
	}

}
