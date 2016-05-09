package com.loadbalance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import com.loadbalance.AutoScaleConfig.LaunchConfig;
import com.loadbalance.AutoScaleConfig.Policy_Down;
import com.loadbalance.AutoScaleConfig.Policy_Up;

public class PeriodicLocalCpuUsageTask extends TimerTask {

	Policy_Up policyUp;
	Policy_Down policyDown;
	LaunchConfig config;
	LoadBalancerHandler handler;

	public PeriodicLocalCpuUsageTask(Policy_Up policyUp, Policy_Down policyDown, LaunchConfig config,
			LoadBalancerHandler handler) {
		this.policyUp = policyUp;
		this.policyDown = policyDown;
		this.config = config;
		this.handler = handler;
		if (config.scalingOperation == null) {
			config.scalingOperation = new AtomicBoolean(false);
		}
	}

	public double getCpu(String url) {
		try {
			URL obj = new URL("http://" + url + ":8080/cpu");
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
				return Double.parseDouble(response.toString());
			} else {
				return Double.NaN;
			}
		} catch (ConnectException e1) {
			e1.printStackTrace();
			LoadBalancer.logger.error("Connecting to host " + url + " failed ");
			// Connection timed out so we need to scale
			return 100;
		} catch (Exception e) {
			e.printStackTrace();
			LoadBalancer.logger.error(e.getMessage());
			return Double.NaN;
		}
	}

	@Override
	public void run() {
		// If no other operation in progress for this
		if (!config.scalingOperation.get()) {
			double totalCPU = 0;
			for (int i = 0; i < config.current; i++) {
				double cpu = getCpu(config.server_pool.get(i));
				if (cpu == Double.NaN) {
					LoadBalancer.logger.info("Some instance does not have cpu info");
					return;
				}
				totalCPU += cpu;

			}
			totalCPU = totalCPU / config.current;
			LoadBalancer.logger.info("CPU " + totalCPU);
			if (policyUp != null && config.current < config.max) {
				if (totalCPU >= policyUp.lowerThreshold && totalCPU <= policyUp.upperThreshold) {
					config.scalingOperation.set(true);
					int increment = Math.min(config.max - config.current, policyUp.instance);
					if (increment > 0) {
						// Simulate Adding
						try {
							Thread.sleep(25000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						for (int i = config.current; i < config.current + increment; i++) {
							try {
								handler.addHost(AutoScaleConfig.getURI(config.server_pool.get(i)), null, config.ami,
										config.instanceType, null);
								LoadBalancer.logger.info("Added host " + config.server_pool.get(i));
							} catch (URISyntaxException e) {
								e.printStackTrace();
							}
						}
						config.current += increment;
						config.desired += increment;
						LoadBalancer.logger.info(
								"Incremented by " + increment + " Current no. " + config.current + " CPU " + totalCPU);
					}
					config.scalingOperation.set(false);
				}
			} else {
				if (totalCPU <= policyDown.upperThreshold) {
					config.scalingOperation.set(true);
					// Simulate Deleting
					try {
						Thread.sleep(15000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					int decrement = Math.min(config.current - config.min, policyDown.instance);
					if (decrement > 0) {
						for (int i = config.current - 1; i >= config.current - decrement; i--) {
							try {
								handler.removeHost(AutoScaleConfig.getURI(config.server_pool.get(i)), null);
								LoadBalancer.logger.info("Removed host " + config.server_pool.get(i));
							} catch (URISyntaxException e) {
								e.printStackTrace();
							}
						}
						config.current -= decrement;
						config.desired -= decrement;
						LoadBalancer.logger.info(
								"Decremnted by " + decrement + " Current no. " + config.current + " CPU " + totalCPU);
					}
					config.scalingOperation.set(false);
				}
			}
		}
	}

}
