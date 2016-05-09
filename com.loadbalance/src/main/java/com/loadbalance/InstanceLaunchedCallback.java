package com.loadbalance;

public interface InstanceLaunchedCallback {
	public void instanceLaunched(int launchConfigId,String dnsName,String instanceId);
	public void instanceTerminated(int launchConfigId,String instanceId);
}
