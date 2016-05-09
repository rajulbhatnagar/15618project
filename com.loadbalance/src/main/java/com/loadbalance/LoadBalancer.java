package com.loadbalance;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.management.MBeanServerConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import com.google.gson.Gson;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.UndertowClient;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;

/**
 * Hello world!
 *
 */
public class LoadBalancer {
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

	public static AutoScaleConfig config;
	public static Logger logger;

	public static void main(String[] args) throws Exception {
		String hostname = getHostname();
		System.out.println("Starting Server " + hostname);
		System.out.println("Memory " + Runtime.getRuntime().totalMemory());
		System.out.println("Proc " + Runtime.getRuntime().availableProcessors());
		logger = LoggerFactory.getLogger(LoadBalancer.class);
		if (args.length < 1) {
			System.out.println("Set Scaling Path");
		}
		String configPath = args[0];
		Path path = Paths.get(configPath);
		Gson gson = new Gson();
		config = gson.fromJson(new String(Files.readAllBytes(path)), AutoScaleConfig.class);
		String instanceType[] = new String[config.launchConfig.size()];
		for (int i = 0; i < instanceType.length; i++) {
			instanceType[i] = config.launchConfig.get(i).instanceType;
		}
		LoadBalancerHandler loadBalancer = new LoadBalancerHandler(UndertowClient.getInstance(),
				new AwsScalingHostSelector()).setConnectionsPerThread(30).setMaxQueueSize(24);

		try {
			config.setupConfig(loadBalancer);
		} catch (Exception e) {
			e.printStackTrace();
		}

		Undertow reverseProxy = Undertow.builder().addHttpListener(80, hostname)
				.setIoThreads(Runtime.getRuntime().availableProcessors() * 2)
				.setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, true)
				.setSocketOption(Options.READ_TIMEOUT, 600000)
				.setHandler(new ProxyHandler(loadBalancer, 600000, ResponseCodeHandler.HANDLE_404)).build();
		reverseProxy.start();
	}
}
