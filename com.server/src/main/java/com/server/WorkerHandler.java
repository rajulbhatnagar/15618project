package com.server;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

public class WorkerHandler implements HttpHandler {

	private final String COMPUTE_PRIME = "countprimes";
	private final String MEMORY_KILLER = "memorykiller";
	private final String TELL_ME_NOW = "tellmenow";
	private final String WISDOM_418 = "418wisdom";

	WorkEngine engine;

	public WorkerHandler() throws IOException {
		engine = new WorkEngine();
	}

	public void handleRequest(HttpServerExchange exchange) throws Exception {
		if (exchange.isInIoThread()) {
			exchange.dispatch(this);
			return;
		}
		Map<String, Deque<String>> params = exchange.getQueryParameters();
		String type,req_id,argument;
		String resp;
		try{
			type = params.get("type").getLast();
			req_id  = params.get("id").getLast();
			argument  = params.get("arg").getLast();
		} catch (Exception e) {
			//Ignore Exception
			resp = "INVALID";
			exchange.getResponseSender().send(resp);
			return;
		}

		
		switch (type) {
		case COMPUTE_PRIME:
			resp = engine.count_primes_job(Integer.parseInt(argument), req_id);
			break;
		case MEMORY_KILLER:
			resp = engine.high_memory_job(argument, req_id);
			break;
		case TELL_ME_NOW:
			resp = engine.mini_compute_job(Integer.parseInt(argument), req_id);
			break;
		case WISDOM_418:
			resp = engine.high_compute_job(Integer.parseInt(argument), req_id);
			break;
		default:
			resp = "INVALID";
			break;
		}

		exchange.getResponseSender().send(resp);
	}

}
