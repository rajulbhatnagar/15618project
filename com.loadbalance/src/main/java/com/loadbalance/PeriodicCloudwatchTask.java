package com.loadbalance;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.loadbalance.AutoScaleConfig.LaunchConfig;
import com.loadbalance.AutoScaleConfig.Policy_Down;
import com.loadbalance.AutoScaleConfig.Policy_Up;

public class PeriodicCloudwatchTask extends TimerTask {
	AmazonCloudWatchClient cloudWatchClient;
	Policy_Up policyUp;
	Policy_Down policyDown;
	private long startPeriod;
	LaunchConfig config;
	LaunchInstanceHelper instanceHelper;
	int launchConfigId;
	InstanceLaunchedCallback callback;

	public PeriodicCloudwatchTask(Policy_Up policyUp, Policy_Down policyDown, AmazonCloudWatchClient cloudWatchClient,
			LaunchConfig config, LaunchInstanceHelper instanceHelper, int launchConfigId,
			InstanceLaunchedCallback callback) {
		this.policyUp = policyUp;
		this.policyDown = policyDown;
		this.cloudWatchClient = cloudWatchClient;
		int period;
		if (policyUp != null) {
			period = Integer.parseInt(policyUp.period);
		} else {
			period = Integer.parseInt(policyDown.period);
		}
		startPeriod = period * 2 * 1000;
		this.config = config;
		this.instanceHelper = instanceHelper;
		this.launchConfigId = launchConfigId;
		this.callback = callback;
		if(config.scalingOperation == null){
			config.scalingOperation = new AtomicBoolean(false);
		}
	}

	@Override
	public void run() {
		if (config.current == config.desired) {
			double totalCpu = 0;
			for (int j = 0; j < config.current; j++) {
				GetMetricStatisticsRequest cloudWatchRequest = new GetMetricStatisticsRequest();
				int period;
				if (policyUp != null) {
					period = Integer.parseInt(policyUp.period);
					cloudWatchRequest.withStartTime(new Date(new Date().getTime() - startPeriod))
							.withNamespace("AWS/EC2").withPeriod(period * 1000)
							.withDimensions(new Dimension().withName("InstanceId").withValue(config.server_pool.get(j)))
							.withMetricName(policyUp.metric).withStatistics(policyUp.statistic).withEndTime(new Date());
				} else {
					period = Integer.parseInt(policyDown.period);
					cloudWatchRequest.withStartTime(new Date(new Date().getTime() - startPeriod))
							.withNamespace("AWS/EC2").withPeriod(period * 1000)
							.withDimensions(new Dimension().withName("ImageId").withValue(config.ami))
							.withMetricName(policyDown.metric).withStatistics(policyDown.statistic)
							.withEndTime(new Date());
				}
				GetMetricStatisticsResult result = cloudWatchClient.getMetricStatistics(cloudWatchRequest);

				List<Datapoint> datapoints = result.getDatapoints();
				if (datapoints.size() > 0 && config.current >= config.min) {
					double cpu = datapoints.get(datapoints.size() - 1).getAverage();
					totalCpu += cpu;
					LoadBalancer.logger.info(j + " CPU " + cpu);

				} else {
					LoadBalancer.logger.warn("No data found from cloudwatch for given period");
				}
			}
			totalCpu = totalCpu / config.current;
			LoadBalancer.logger.info("AVG CPU " + totalCpu);
			if (policyUp != null && config.current < config.max) {
				if (totalCpu >= policyUp.lowerThreshold && totalCpu <= policyUp.upperThreshold) {
					config.scalingOperation.set(true);
					int increment = Math.min(config.max - config.current, policyUp.instance);
					config.desired += increment;
					LoadBalancer.logger.info("Scale Out Desired " + config.desired + " " + increment);
					for (int i = 0; i < increment; i++) {
						instanceHelper.launchOnDemand(config.instanceType, config.ami, launchConfigId, callback);
					}
				}
			} else {
				if (totalCpu <= policyDown.upperThreshold) {
					config.scalingOperation.set(true);
					int decrement = Math.min(config.current - config.min, policyDown.instance);
					config.desired -= decrement;
					LoadBalancer.logger.info("Scale in Desired " + config.desired + " " + decrement);
					for (int i = config.current - 1; i >= config.current - decrement; i--) {
						List<String> temp = new ArrayList<String>();
						temp.add(config.server_pool.get(i));
						instanceHelper.terminateInstance(temp, launchConfigId, callback);
					}
				}
			}
		}
	}

}
