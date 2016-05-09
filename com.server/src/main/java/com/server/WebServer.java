package com.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import org.xnio.Options;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.util.Headers;
import net.spy.memcached.MemcachedClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebServer {
	public static String getHostname() throws Exception {
		URL obj = new URL("http://169.254.169.254/latest/meta-data/public-hostname");

		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		int responseCode = con.getResponseCode();
		if (responseCode == HttpURLConnection.HTTP_OK) {

			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			return response.toString();
		} else {
			return "ERROR";
		}
	}

	public static void main(String[] args) throws Exception {
		String hostname = getHostname();
		Logger logger = LoggerFactory.getLogger(WebServer.class);
		logger.info("Starting Server " + hostname);
		logger.info("Memory " + Runtime.getRuntime().totalMemory());
		logger.info("Proc " + Runtime.getRuntime().availableProcessors());
		logger.debug("Test");
		Undertow.builder().addHttpListener(8080, hostname).setBufferSize(1024 * 16)
				.setIoThreads(Math.max(Runtime.getRuntime().availableProcessors(), 2))
				.setSocketOption(Options.BACKLOG, 10000).setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false)
				.setServerOption(UndertowOptions.ALWAYS_SET_DATE, true)
				.setServerOption(UndertowOptions.ENABLE_CONNECTOR_STATISTICS, false)
				.setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, false)
				.setHandler(Handlers.header(
						Handlers.path().addPrefixPath("/health", new HealthCheckHandler())
								.addPrefixPath("/work", new WorkerHandler()).addPrefixPath("/cpu", new CpuHandler()),
						Headers.SERVER_STRING, "U-tow"))
				.build().start();
	}
}
