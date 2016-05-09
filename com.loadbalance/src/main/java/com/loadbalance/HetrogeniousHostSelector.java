package com.loadbalance;

import java.util.concurrent.atomic.AtomicInteger;

import com.loadbalance.LoadBalancerHandler.Host;
import com.loadbalance.LoadBalancerHandler.HostSelector;

public class HetrogeniousHostSelector implements HostSelector {

	private final String COMPUTE_PRIME = "countprimes";
	private final String MEMORY_KILLER = "memorykiller";
	private final String TELL_ME_NOW = "tellmenow";
	private final String WISDOM_418 = "418wisdom";

	String instanceTypes[];
	AtomicInteger currentHost;

	public HetrogeniousHostSelector(String instanceTypes[]) {
		this.instanceTypes = instanceTypes;
		currentHost = new AtomicInteger(0);
	}

	public int selectHost(Host[] availableHosts) {
		return selectHost(availableHosts, null);
	}

	public int selectHost(Host[] availableHosts, String requestType) {
		// HealthCheckURL
		if (requestType == null) {
			LoadBalancer.logger.info("Type null request");
			return 2;
		}
		// LoadBalancer.logger.info("Type " + requestType + " request");
		switch (requestType) {
		case TELL_ME_NOW:
			return 0;
		case MEMORY_KILLER:
			return 1;
		default:
			int host = (currentHost.incrementAndGet() % (availableHosts.length - 2)) + 2;
			//LoadBalancer.logger.info("Host " + host);
			return host;
		}
	}

}
