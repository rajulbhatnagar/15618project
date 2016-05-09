package com.loadbalance;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfilesConfigFile;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClient;

public class AutoScaleConfig implements InstanceLaunchedCallback {
	public List<LaunchConfig> launchConfig;

	transient LoadBalancerHandler handler;
	transient LaunchInstanceHelper instanceHelper;
	transient ExecutorService executorService;
	transient AmazonCloudWatchClient cloudWatchClient;

	private final String SCALING_LOCAL = "local";
	private final String SCALING_CLOUDWATCH = "aws_cloudwatch";

	public AutoScaleConfig() throws Exception {
		launchConfig = new ArrayList<LaunchConfig>();
		executorService = Executors.newFixedThreadPool(10);
		AWSCredentials credentials = null;
		try {
			credentials = new ProfilesConfigFile("/home/ubuntu/.aws/credentials").getCredentials("default");
		} catch (Exception e) {
			throw new AmazonClientException("Cannot load the credentials from the credential profiles file. "
					+ "Please make sure that your credentials file is at the correct "
					+ "location default, and is in valid format.", e);
		}
		cloudWatchClient = new AmazonCloudWatchClient(credentials);
		Region usEast = Region.getRegion(Regions.US_EAST_1);
		cloudWatchClient.setRegion(usEast);
		LoadBalancer.logger.info("Cloudwatch Client setup");
	}

	public static URI getURI(String url) throws URISyntaxException {
		if (!url.startsWith("http://")) {
			url = "http://" + url + ":8080";
		}
		return new URI(url);
	}

	@Override
	public void instanceLaunched(int launchConfigId, String dnsName, String instanceId) {
		LaunchConfig config = launchConfig.get(launchConfigId);
		LoadBalancer.logger.info("Instance launched callback");
		try {
			synchronized (config.server_pool) {
				config.server_pool.add(instanceId);
			}
			handler.addHost(getURI(dnsName), null, config.ami, config.instanceType, instanceId);
			config.current++;
			config.scalingOperation.set(false);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void instanceTerminated(int launchConfigId, String instanceId) {
		LoadBalancer.logger.info("Instance terminated callback");
		LaunchConfig config = launchConfig.get(launchConfigId);
		synchronized (config.server_pool) {
			config.server_pool.remove(instanceId);
		}
		handler.removeHost(null, instanceId);
		config.current--;
		config.scalingOperation.set(false);
	}

	public void setupScaleUpPolicy(List<Policy_Up> policies, String operator, String scaling, LaunchConfig config,
			int launchConfigId) {
		for (int i = 0; i < policies.size(); i++) {
			Policy_Up policy = policies.get(i);
			TimerTask task;
			if (scaling.contentEquals(SCALING_CLOUDWATCH)) {
				task = new PeriodicCloudwatchTask(policy, null, cloudWatchClient, config, instanceHelper,
						launchConfigId, this);
			} else {
				task = new PeriodicLocalCpuUsageTask(policy, null, config, handler);
			}
			Timer timer = new Timer();
			long period = (Long.parseLong(policy.period)) * 1000;
			timer.schedule(task, 0, period);

		}
	}

	public void setupScaleDownPolicy(List<Policy_Down> policies, String operator, String scaling, LaunchConfig config,
			int launchConfigId) {
		for (int i = 0; i < policies.size(); i++) {
			Policy_Down policy = policies.get(i);
			TimerTask task;
			if (scaling.contentEquals(SCALING_CLOUDWATCH)) {
				task = new PeriodicCloudwatchTask(null, policy, cloudWatchClient, config, instanceHelper,
						launchConfigId, this);
			} else {
				task = new PeriodicLocalCpuUsageTask(null, policy, config, handler);
			}
			Timer timer = new Timer();
			long period = (Long.parseLong(policy.period)) * 1000;
			timer.schedule(task, 0, period);
		}
	}

	public void setupConfig(LoadBalancerHandler handler) throws Exception {
		instanceHelper = new LaunchInstanceHelper();
		this.handler = handler;
		for (int i = launchConfig.size() - 1; i >= 0; i--) {
			LaunchConfig config = launchConfig.get(i);
			if (config.server_pool != null) {
				int hostAddCount = Math.min(config.server_pool.size(), config.min);
				for (int j = 0; j < hostAddCount; j++) {
					URI uri = getURI(config.server_pool.get(j));
					handler.addHost(uri, null, config.ami, config.instanceType, null);
					LoadBalancer.logger.info("Host " + uri.toString() + " added ");
				}
				int hostToLaunc = config.min - hostAddCount;
				for (int j = 0; j < hostToLaunc; j++) {
					instanceHelper.launchOnDemand(config.instanceType, config.ami, i, this);
				}
				config.current = hostAddCount;
				config.desired = config.min;
				LoadBalancer.logger.info("Configured " + config.current + " hosts of type " + config.instanceType);
			}
			if (config.scale) {
				if (config.scalingUpPolicy != null && config.scalingUpPolicy.policies != null) {
					setupScaleUpPolicy(config.scalingUpPolicy.policies, config.scalingUpPolicy.operator, config.scaling,
							config, i);
				}
				if (config.scalingDownPolicy != null && config.scalingDownPolicy.policies != null) {
					setupScaleDownPolicy(config.scalingDownPolicy.policies, config.scalingUpPolicy.operator,
							config.scaling, config, i);
				}
			}
		}
	}

	class LaunchConfig {
		public transient AtomicBoolean scalingOperation = new AtomicBoolean(false);
		public String ami;
		public String instanceType;
		public String scaling;
		public int min;
		public int max;
		public int desired;
		public int current;
		public List<String> server_pool;

		public LaunchConfig() {
			server_pool = new ArrayList<String>();
			scalingOperation = new AtomicBoolean(false);
		}

		public boolean scale;
		public ScalingUpPolicy scalingUpPolicy;
		public ScalingDownPolicy scalingDownPolicy;

	}

	class Policy_Up {

		public String metric;
		public String statistic;
		public String period;
		public int instance;
		public int lowerThreshold;
		public int upperThreshold;
		public int warmup;

	}

	class Policy_Down {

		public String metric;
		public String statistic;
		public String period;
		public int instance;
		public int upperThreshold;

	}

	class ScalingDownPolicy {
		public String operator;
		public List<Policy_Down> policies = new ArrayList<Policy_Down>();
	}

	class ScalingUpPolicy {

		public String operator;
		public List<Policy_Up> policies = new ArrayList<Policy_Up>();

	}

}
