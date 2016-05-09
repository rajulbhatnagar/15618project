package com.traceplayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class TracePlayer {

	private static Logger logger;
	
	private static AtomicInteger remainingRequests;

	public static class WorkgenThread implements Runnable {
		Request request;
		String baseUrl;

		public WorkgenThread(Request request, String baseUrl) {
			this.request = request;
			this.baseUrl = baseUrl;
		}

		public void run() {
			try {
				long start = System.nanoTime();
				URL obj = new URL(baseUrl+"/work?id="+request.request_id+"&type="+request.type+"&arg="+request.argument);
				StringBuffer response = new StringBuffer();
				HttpURLConnection con = (HttpURLConnection) obj.openConnection();
				con.setReadTimeout(0);
				int responseCode = con.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_OK) {

					BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
					String inputLine;
					

					while ((inputLine = in.readLine()) != null) {
						response.append(inputLine);
					}
					in.close();
					long end = System.nanoTime();
					StringBuilder logMessage = new StringBuilder();
					logMessage.append(request.request_id);
					logMessage.append("\t");
					logMessage.append(request.type);
					logMessage.append("\t");
					logMessage.append(((end-start)/1000000));
					logMessage.append(" ms\t");
					logMessage.append(response.toString());
					logger.info(logMessage.toString());
				} else {
					long end = System.nanoTime();
					StringBuilder logMessage = new StringBuilder();
					logMessage.append(request.request_id);
					logMessage.append("\t");
					logMessage.append(request.type);
					logMessage.append("\t");
					logMessage.append(((end-start)/1000000));
					logMessage.append(" ms\t");
					logMessage.append("ERROR "+responseCode);
					logger.info(logMessage.toString());
				}
				if(remainingRequests.decrementAndGet() == 0){
					logger.info("Trace Complete!!!!!!!!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				logger.error(e.getMessage());
			}
		}
	}

	public static void main(String[] args) throws InterruptedException {
		ArrayList<Request> requests = new ArrayList<Request>();
		Gson gson = new Gson();
		logger = LoggerFactory.getLogger(TracePlayer.class);
		logger.info("Started Reading Trace From Disk");
		if(args.length < 2){
			logger.error("Pass Proxy URI and Tracefile as arg");
			return;
		}
		File trace = new File(args[1]);		
		Scanner scanTrace = null;
		try {
			scanTrace = new Scanner(trace);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}
		while (scanTrace.hasNextLine()) {
			Request temp = gson.fromJson(scanTrace.nextLine(), Request.class);
			requests.add(temp);
		}
		scanTrace.close();
		String baseUrl = args[0];
		if(!baseUrl.startsWith("http://")){
			baseUrl="http://"+baseUrl;
		}
		
		logger.info("Finished Reading Trace From Disk");
		remainingRequests = new AtomicInteger(requests.size());
		logger.info("Started Trace Playback");
		long prevTime = requests.get(0).time;
		Thread.sleep(prevTime);
		for (int i = 0; i < requests.size(); i++) {
			Request request = requests.get(i);
			Thread t = new Thread(new WorkgenThread(request, baseUrl));
			t.start();
			if (i + 1 < requests.size()) {
				long nextTime = requests.get(i + 1).time;
				Thread.sleep(nextTime - prevTime);
				prevTime = nextTime;
			}
		}

	}
}
