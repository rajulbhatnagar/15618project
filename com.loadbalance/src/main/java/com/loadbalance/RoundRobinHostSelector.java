package com.loadbalance;

import java.util.concurrent.atomic.AtomicInteger;

import com.loadbalance.LoadBalancerHandler.Host;
import com.loadbalance.LoadBalancerHandler.HostSelector;

class RoundRobinHostSelector implements HostSelector {

    private final AtomicInteger currentHost;
    
    public RoundRobinHostSelector() {
		currentHost = new AtomicInteger(0);
	}

    public int selectHost(Host[] availableHosts) {
        return selectHost(availableHosts,null);
    }

    public int selectHost(Host[] availableHosts, String requestType) {
		return currentHost.incrementAndGet() % availableHosts.length;
	}
}
