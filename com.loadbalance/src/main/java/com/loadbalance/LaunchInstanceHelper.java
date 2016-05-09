package com.loadbalance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.profile.ProfilesConfigFile;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.StopInstancesRequest;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;

public class LaunchInstanceHelper {
	private AmazonEC2 ec2;
	private ArrayList<String> instanceIds;
	private ArrayList<Tag> tags;

	private final String SECURITY_GROUP = "AWS_ELASTIC_WEBSERVER";
	private final String KEY_NAME = "p0";

	private final long RETRY_INTERVAL = 5 * 1000;
	private final int STATE_RUNNING = 16;
	private final int STATE_TERMINATE = 48;

	// Time for OS to boot and server to start. TODO pick from config
	private final long WARMUP_TIME = 90 * 1000;

	private Logger logger;
	ExecutorService instanceExecutorService;

	private class InstanceTerminateWorker implements Runnable {

		TerminateInstancesRequest request;
		String instanceId;
		int launchConfigId;
		InstanceLaunchedCallback callback;

		public InstanceTerminateWorker(TerminateInstancesRequest request, String instanceId, int launchConfigId,
				InstanceLaunchedCallback callback) {
			this.callback = callback;
			this.launchConfigId = launchConfigId;
			this.request = request;
			this.instanceId = instanceId;
		}

		@Override
		public void run() {
			TerminateInstancesResult terminateResult = ec2.terminateInstances(request);

			int state = -1;
			while (state != STATE_TERMINATE) {
				state = getInstanceStatus(instanceId);
				// System.out.println("State "+state);
				try {
					Thread.sleep(RETRY_INTERVAL);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			logger.info("Instance Terminated " + instanceId);
			callback.instanceTerminated(launchConfigId, instanceId);
		}

	}

	private class InstanceLauncherWorker implements Runnable {

		InstanceLaunchedCallback callback;
		int launchConfigId;
		RunInstancesRequest runInstancesRequest;

		public InstanceLauncherWorker(RunInstancesRequest request, int launchConfigId,
				InstanceLaunchedCallback callback) {
			this.launchConfigId = launchConfigId;
			this.callback = callback;
			this.runInstancesRequest = request;
		}

		@Override
		public void run() {
			// Launch the instance.
			RunInstancesResult runResult = ec2.runInstances(runInstancesRequest);

			// Add the instance id into the instance id list, so we can
			// potentially
			// later
			// terminate that list.
			Instance launchedInstance = null;
			for (Instance instance : runResult.getReservation().getInstances()) {
				logger.info("Starting Server Ondemand: " + instance.getInstanceId());
				instanceIds.add(instance.getInstanceId());
				launchedInstance = instance; // We launch only one at a time per
												// thread.Its faster this way
			}
			String dnsName = null;

			int state = -1;
			while (state != STATE_RUNNING) {
				state = getInstanceStatus(launchedInstance.getInstanceId());
				// System.out.println("State "+state);
				try {
					Thread.sleep(RETRY_INTERVAL);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			logger.info("Instance Running " + launchedInstance.getInstanceId());
			dnsName = getDnsName(launchedInstance.getInstanceId());
			logger.info("Hostname is " + dnsName);
			tagResources(instanceIds, tags);

			logger.info("Waiting for boot..");
			try {
				Thread.sleep(WARMUP_TIME);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			callback.instanceLaunched(launchConfigId, dnsName, launchedInstance.getInstanceId());
		}
	}

	/**
	 * Public constructor.
	 * 
	 * @throws Exception
	 */
	public LaunchInstanceHelper() throws Exception {
		logger = LoggerFactory.getLogger(LaunchInstanceHelper.class);
		init();
	}

	public Integer getInstanceStatus(String instanceId) {
		DescribeInstancesRequest describeInstanceRequest = new DescribeInstancesRequest().withInstanceIds(instanceId);
		DescribeInstancesResult describeInstanceResult = ec2.describeInstances(describeInstanceRequest);
		InstanceState state = describeInstanceResult.getReservations().get(0).getInstances().get(0).getState();
		return state.getCode();
	}

	public String getDnsName(String instanceId) {
		DescribeInstancesRequest describeInstanceRequest = new DescribeInstancesRequest().withInstanceIds(instanceId);
		DescribeInstancesResult describeInstanceResult = ec2.describeInstances(describeInstanceRequest);
		String publicDns = describeInstanceResult.getReservations().get(0).getInstances().get(0).getPublicDnsName();
		return publicDns;
	}

	/**
	 * The only information needed to create a client are security credentials
	 * consisting of the AWS Access Key ID and Secret Access Key. All other
	 * configuration, such as the service endpoints, are performed
	 * automatically. Client parameters, such as proxies, can be specified in an
	 * optional ClientConfiguration object when constructing a client.
	 *
	 * @see com.amazonaws.auth.BasicAWSCredentials
	 * @see com.amazonaws.auth.PropertiesCredentials
	 * @see com.amazonaws.ClientConfiguration
	 */
	private void init() throws Exception {
		AWSCredentials credentials = null;
		try {
			credentials = new ProfilesConfigFile("/home/ubuntu/.aws/credentials").getCredentials("default");
		} catch (Exception e) {
			throw new AmazonClientException("Cannot load the credentials from the credential profiles file. "
					+ "Please make sure that your credentials file is at the correct "
					+ "location default, and is in valid format.", e);
		}

		ec2 = new AmazonEC2Client(credentials);
		Region usEast = Region.getRegion(Regions.US_EAST_1);
		ec2.setRegion(usEast);
		tags = new ArrayList<>();
		tags.add(new Tag("Name", "Server"));

		// Create a new security group.
		try {
			CreateSecurityGroupRequest securityGroupRequest = new CreateSecurityGroupRequest(SECURITY_GROUP,
					"Elastic Webserver");
			CreateSecurityGroupResult result = ec2.createSecurityGroup(securityGroupRequest);
			logger.info(String.format("Security group created: [%s]", result.getGroupId()));
		} catch (AmazonServiceException ase) {
			// Likely this means that the group is already created, so ignore.
			logger.warn(ase.getMessage());
		}

		String ipAddr = "0.0.0.0/0";

		// Create a range that you would like to populate.
		List<String> ipRanges = Collections.singletonList(ipAddr);

		// Open up All ports.
		IpPermission ipPermission = new IpPermission().withIpProtocol("tcp").withFromPort(new Integer(0))
				.withToPort(new Integer(65535)).withIpRanges(ipRanges);

		List<IpPermission> ipPermissions = Collections.singletonList(ipPermission);

		try {
			// Authorize the ports to the used.
			AuthorizeSecurityGroupIngressRequest ingressRequest = new AuthorizeSecurityGroupIngressRequest(
					SECURITY_GROUP, ipPermissions);
			ec2.authorizeSecurityGroupIngress(ingressRequest);
			logger.info(String.format("Ingress port authroized: [%s]", ipPermissions.toString()));
		} catch (AmazonServiceException ase) {
			// Ignore because this likely means the zone has already been
			// authorized.
			logger.warn(ase.getMessage());
		}
		instanceIds = new ArrayList<>();
		instanceExecutorService = Executors.newFixedThreadPool(10);
	}

	/**
	 * Launches instance sets security group, tags and return dns name
	 * 
	 * @param instanceType
	 * @param amiID
	 * @param securityGroup
	 * @return DNS name
	 */
	public void launchOnDemand(String instanceType, String amiID, int launchConfigId,
			InstanceLaunchedCallback callback) {

		RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
		runInstancesRequest.setInstanceType(instanceType);
		runInstancesRequest.setImageId(amiID);
		runInstancesRequest.setMinCount(Integer.valueOf(1));
		runInstancesRequest.setMaxCount(Integer.valueOf(1));
		runInstancesRequest.setKeyName(KEY_NAME);
		runInstancesRequest.setMonitoring(true);

		// Add the security group to the request.
		ArrayList<String> securityGroups = new ArrayList<String>();
		securityGroups.add(SECURITY_GROUP);
		runInstancesRequest.setSecurityGroups(securityGroups);

		// Launch in background and send callback when launched
		instanceExecutorService.execute(new InstanceLauncherWorker(runInstancesRequest, launchConfigId, callback));
	}

	public void terminateInstance(List<String> instanceIds, int launchConfigId, InstanceLaunchedCallback callback) {
		TerminateInstancesRequest terminateInstanceRequest = new TerminateInstancesRequest(instanceIds);
		instanceExecutorService.execute(
				new InstanceTerminateWorker(terminateInstanceRequest, instanceIds.get(0), launchConfigId, callback));
	}

	/**
	 * Tag any of the resources we specify.
	 * 
	 * @param resources
	 * @param tags
	 */
	private void tagResources(List<String> resources, List<Tag> tags) {
		// Create a tag request.
		CreateTagsRequest createTagsRequest = new CreateTagsRequest();
		createTagsRequest.setResources(resources);
		createTagsRequest.setTags(tags);

		// Try to tag the Spot request submitted.
		try {
			ec2.createTags(createTagsRequest);
		} catch (AmazonServiceException e) {
			// Write out any exceptions that may have occurred.
			logger.warn("Error tagging instances");
			logger.warn("Caught Exception: " + e.getMessage());
			logger.warn("Reponse Status Code: " + e.getStatusCode());
			logger.warn("Error Code: " + e.getErrorCode());
			logger.warn("Request ID: " + e.getRequestId());
		}

	}
}